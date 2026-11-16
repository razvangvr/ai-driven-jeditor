package dev.jeditor.compiler.run;

import dev.jeditor.compiler.ProjectLayout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Explains the classpath a program gets when the editor compiles and runs it: every entry in
 * search order with what it is and where it comes from, what each JAR contains, which folders
 * new JARs can be dropped into, and things that look wrong.
 *
 * <p>The classpath itself is not computed here; it is the one {@link ProjectLayout} produces and
 * that error checking, Build and Run all use, so what this reports is what they see.
 */
public final class ClasspathInspector {

    private static final System.Logger LOG = System.getLogger(ClasspathInspector.class.getName());

    /** What kind of thing a classpath entry is. */
    public enum Kind {
        /** The program's own classes, compiled fresh into a temporary folder each time it is run. */
        COMPILED_ON_RUN,
        /** A folder of {@code .class} files. */
        CLASSES_FOLDER,
        /** A JAR file. */
        JAR
    }

    /**
     * One classpath entry.
     *
     * @param path        the folder or JAR; null for {@link Kind#COMPILED_ON_RUN}, whose location changes per run
     * @param kind        what it is
     * @param origin      why it is on the classpath, e.g. "library folder lib" or "Maven output of module core"
     * @param coordinates for a JAR, its Maven coordinates {@code group:artifact} if it declares them, else its title; may be null
     * @param version     for a JAR, its version if it can be told; may be null
     * @param sizeBytes   size of a JAR; 0 for folders
     * @param classCount  number of classes inside
     * @param packages    the packages inside, sorted
     */
    public record Entry(Path path, Kind kind, String origin, String coordinates, String version, long sizeBytes,
                        int classCount, List<String> packages) {
        public Entry {
            packages = List.copyOf(packages);
        }

        /** File or folder name, or a description for the entry that has no fixed path. */
        public String name() {
            return path == null ? "this project's classes" : path.getFileName().toString();
        }
    }

    /**
     * A folder whose JARs are put on the classpath.
     *
     * @param path      the folder
     * @param exists    whether it is there yet
     * @param jarCount  how many JARs it holds
     * @param forNewJars true for the one folder new JARs should be added to
     */
    public record LibraryFolder(Path path, boolean exists, int jarCount, boolean forNewJars) {
    }

    /**
     * @param file           the source file the report is about; null when it is about the opened folder alone
     * @param mainClasses    the runnable classes of that file
     * @param classpath      the entries, in the order they are searched
     * @param sourceFolders  where sources of other classes are looked up
     * @param libraryFolders the folders JARs are picked up from
     * @param warnings       things that look wrong, in plain sentences
     */
    public record Report(Path file, List<String> mainClasses, List<Entry> classpath, List<Path> sourceFolders,
                         List<LibraryFolder> libraryFolders, List<String> warnings) {
        public Report {
            mainClasses = List.copyOf(mainClasses);
            classpath = List.copyOf(classpath);
            sourceFolders = List.copyOf(sourceFolders);
            libraryFolders = List.copyOf(libraryFolders);
            warnings = List.copyOf(warnings);
        }

        /** The entries that exist on disk, joined the way {@code java -cp} wants them. */
        public String classpathString() {
            return classpath.stream().filter(entry -> entry.path() != null).map(entry -> entry.path().toString())
                    .collect(Collectors.joining(java.io.File.pathSeparator));
        }

        public List<Entry> jars() {
            return classpath.stream().filter(entry -> entry.kind() == Kind.JAR).toList();
        }
    }

    private ClasspathInspector() {
    }

    /**
     * Reports on the classpath of a source file.
     *
     * @param file          the file; null to report on the opened folder alone (its library folders and their JARs)
     * @param source        the file's current text; ignored when {@code file} is null
     * @param projectFolder the folder opened in the editor; may be null
     * @param mainClasses   the runnable classes found in the file
     */
    public static Report inspect(Path file, String source, Path projectFolder, List<String> mainClasses) {
        ProjectLayout layout;
        if (file != null) {
            layout = ProjectLayout.detect(file, source, projectFolder);
        } else {
            List<Path> jars = new ArrayList<>();
            ProjectLayout.libraryFolders(null, projectFolder).forEach(folder -> jars.addAll(jarsIn(folder)));
            layout = new ProjectLayout(List.of(), jars);
        }

        List<Entry> entries = new ArrayList<>();
        if (file != null) {
            entries.add(new Entry(null, Kind.COMPILED_ON_RUN, "compiled from the sources each time the program is run",
                    null, null, 0, 0, List.of()));
        }
        for (Path path : layout.classpath()) {
            entries.add(Files.isDirectory(path) ? classesFolder(path) : jar(path));
        }

        List<LibraryFolder> libraryFolders = libraryFolders(file, projectFolder);
        return new Report(file, mainClasses, entries, layout.sourceRoots(), libraryFolders,
                warnings(file, projectFolder, entries));
    }

    /**
     * The folder a new JAR should be copied into so that it lands on the classpath: an existing
     * library folder if there is one, otherwise {@code lib} in the file's Maven module or the
     * opened folder. Empty when there is neither a file nor an opened folder to anchor it.
     */
    public static java.util.Optional<Path> folderForNewJars(Path file, Path projectFolder) {
        return libraryFolders(file, projectFolder).stream().filter(LibraryFolder::forNewJars).map(LibraryFolder::path).findFirst();
    }

    private static List<LibraryFolder> libraryFolders(Path file, Path projectFolder) {
        List<Path> candidates = ProjectLayout.libraryFolders(file, projectFolder);
        // New JARs go where JARs already are; failing that, into the first candidate ("lib" of the module or folder).
        Path preferred = candidates.stream().filter(Files::isDirectory).findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
        List<LibraryFolder> result = new ArrayList<>();
        for (Path candidate : candidates) {
            boolean exists = Files.isDirectory(candidate);
            if (exists || candidate.equals(preferred)) { // do not list every folder that merely could exist
                result.add(new LibraryFolder(candidate, exists, jarsIn(candidate).size(), candidate.equals(preferred)));
            }
        }
        return result;
    }

    private static List<Path> jarsIn(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list the JARs in " + folder, e);
            return List.of();
        }
    }

    // ---- describing entries -------------------------------------------------------------------

    private static Entry classesFolder(Path folder) {
        TreeSet<String> packages = new TreeSet<>();
        int[] classes = new int[1];
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(f -> f.getFileName().toString().endsWith(".class")).forEach(file -> {
                classes[0]++;
                Path relative = folder.relativize(file).getParent();
                packages.add(relative == null ? "" : relative.toString().replace(java.io.File.separatorChar, '.'));
            });
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read the classes in " + folder, e);
        }
        return new Entry(folder, Kind.CLASSES_FOLDER, originOf(folder), null, null, 0, classes[0], named(packages));
    }

    private static Entry jar(Path path) {
        String coordinates = null;
        String version = null;
        TreeSet<String> packages = new TreeSet<>();
        int classes = 0;
        long size = 0;
        try (JarFile jar = new JarFile(path.toFile())) {
            size = Files.size(path);
            Properties maven = null;
            for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class")) {
                    classes++;
                    int slash = name.lastIndexOf('/');
                    packages.add(slash < 0 ? "" : name.substring(0, slash).replace('/', '.'));
                } else if (maven == null && name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                    maven = new Properties();
                    try (InputStream in = jar.getInputStream(entry)) {
                        maven.load(in);
                    }
                }
            }
            // Best source first: what the build tool recorded, then the manifest, then the file name.
            if (maven != null && maven.getProperty("artifactId") != null) {
                coordinates = maven.getProperty("groupId", "?") + ":" + maven.getProperty("artifactId");
                version = maven.getProperty("version");
            }
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                Attributes main = manifest.getMainAttributes();
                if (coordinates == null) {
                    coordinates = firstNonBlank(main.getValue("Implementation-Title"), main.getValue("Bundle-SymbolicName"),
                            main.getValue("Automatic-Module-Name"));
                }
                if (version == null) {
                    version = firstNonBlank(main.getValue("Implementation-Version"), main.getValue("Bundle-Version"));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read " + path + "; it may not be a valid JAR", e);
        }
        if (version == null) {
            version = baseNameAndVersion(path.getFileName().toString())[1];
        }
        return new Entry(path, Kind.JAR, originOf(path), coordinates, version, size, classes, named(packages));
    }

    private static List<String> named(TreeSet<String> packages) {
        return packages.stream().map(name -> name.isEmpty() ? "(unnamed package)" : name).toList();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /** Why a path is on the classpath, judged from where it sits. */
    public static String originOf(Path path) {
        Path parent = path.getParent();
        String parentName = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        String name = path.getFileName().toString();
        if (Files.isDirectory(path) || !name.endsWith(".jar")) {
            Path module = parent == null ? null : parent.getParent();
            String moduleName = module == null || module.getFileName() == null ? "" : " of module " + module.getFileName();
            if (parentName.equals("target") && name.equals("classes")) {
                return "Maven build output" + moduleName;
            }
            if (parentName.equals("target") && name.equals("test-classes")) {
                return "Maven test build output" + moduleName;
            }
            return "folder of classes";
        }
        if (ProjectLayout.LIBRARY_FOLDER_NAMES.contains(parentName)) {
            return "library folder " + parentName;
        }
        if (parentName.equals("dependency") && parent.getParent() != null
                && String.valueOf(parent.getParent().getFileName()).equals("target")) {
            return "Maven dependency copied to target/dependency";
        }
        return "JAR";
    }

    // ---- warnings -----------------------------------------------------------------------------

    private static List<String> warnings(Path file, Path projectFolder, List<Entry> entries) {
        List<String> warnings = new ArrayList<>();

        // The same library twice: only the first one on the classpath is used, which surprises people.
        Map<String, List<Entry>> byLibrary = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.JAR) {
                String key = entry.coordinates() != null && entry.coordinates().contains(":")
                        ? entry.coordinates()
                        : baseNameAndVersion(entry.name())[0];
                byLibrary.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
        }
        byLibrary.forEach((library, copies) -> {
            if (copies.size() > 1) {
                warnings.add(library + " is on the classpath " + copies.size() + " times ("
                        + copies.stream().map(Entry::name).collect(Collectors.joining(", "))
                        + "). Only the first, " + copies.get(0).name() + ", is used.");
            }
        });

        for (Entry entry : entries) {
            if (entry.kind() == Kind.JAR && entry.classCount() == 0) {
                warnings.add(entry.name() + " contains no classes (a sources or javadoc JAR, or not a valid JAR).");
            }
        }

        // Maven projects: dependencies declared in the pom are not on this classpath unless copied.
        Path module = file == null ? null : ProjectLayout.mavenModuleOf(file).orElse(null);
        if (module == null && projectFolder != null && Files.isRegularFile(projectFolder.resolve("pom.xml"))) {
            module = projectFolder;
        }
        if (module != null && !Files.isDirectory(module.resolve("target/dependency"))) {
            warnings.add("This is a Maven project, but the dependencies declared in pom.xml are not resolved by the editor. "
                    + "Run 'mvn dependency:copy-dependencies' in " + module + " to put them on this classpath.");
        }
        return Collections.unmodifiableList(warnings);
    }

    private static final Pattern VERSIONED_JAR = Pattern.compile("^(.+?)-(\\d[\\w.]*)(?:[-.][A-Za-z][\\w.-]*)?\\.jar$");

    /** Splits {@code commons-lang3-3.14.0.jar} into {@code {"commons-lang3", "3.14.0"}}; the version is null if there is none. */
    static String[] baseNameAndVersion(String jarName) {
        Matcher matcher = VERSIONED_JAR.matcher(jarName);
        if (matcher.matches()) {
            return new String[] {matcher.group(1), matcher.group(2)};
        }
        return new String[] {jarName.endsWith(".jar") ? jarName.substring(0, jarName.length() - 4) : jarName, null};
    }
}
