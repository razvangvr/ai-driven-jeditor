package dev.jeditor.compiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where the compiler should look for the other classes a source file refers to.
 *
 * @param sourceRoots directories containing {@code .java} sources (the compiler's source path)
 * @param classpath   directories and jars containing compiled classes
 */
public record ProjectLayout(List<Path> sourceRoots, List<Path> classpath) {

    private static final System.Logger LOG = System.getLogger(ProjectLayout.class.getName());

    public static final ProjectLayout NONE = new ProjectLayout(List.of(), List.of());

    public ProjectLayout {
        sourceRoots = List.copyOf(sourceRoots);
        classpath = List.copyOf(classpath);
    }

    /**
     * Works out a sensible layout for {@code file} from what is on disk:
     * <ul>
     *   <li>the source root implied by the file's {@code package} declaration, so sibling classes resolve;</li>
     *   <li>for Maven projects (nearest {@code pom.xml}), {@code src/main/java} and {@code target/classes} of
     *       the module and of the other modules of the same multi-module build, the module's own test
     *       sources/classes, and any jars in {@code target/dependency}, {@code lib} or {@code libs}.</li>
     * </ul>
     * Only existing locations are returned.
     */
    public static ProjectLayout detect(Path file, String source) {
        return detect(file, source, null);
    }

    /** Names a folder of library JARs may have; JARs in it are put on the classpath. */
    public static final List<String> LIBRARY_FOLDER_NAMES = List.of("lib", "libs");

    /**
     * Like {@link #detect(Path, String)}, also taking the JARs in the library folders
     * ({@code lib} or {@code libs}) of the opened folder, which is the only way a plain,
     * non-Maven folder gets libraries.
     *
     * @param projectFolder the folder opened in the editor; may be null
     */
    public static ProjectLayout detect(Path file, String source, Path projectFolder) {
        Path absolute = file.toAbsolutePath().normalize();
        Set<Path> sources = new LinkedHashSet<>();
        Set<Path> classes = new LinkedHashSet<>();

        sourceRootOf(absolute, PackageNameExtractor.extract(source).orElse("")).ifPresent(sources::add);

        Path module = nearestMavenModule(absolute);
        if (module != null) {
            addModule(module, true, sources, classes);
            Path parent = module.getParent();
            if (parent != null && Files.isRegularFile(parent.resolve("pom.xml"))) {
                for (Path sibling : childModules(parent)) {
                    if (!sibling.equals(module)) {
                        addModule(sibling, false, sources, classes);
                    }
                }
            }
            for (Path child : childModules(module)) {
                addModule(child, false, sources, classes);
            }
        }
        for (Path libraries : libraryFolders(absolute, projectFolder)) {
            addJars(classes, libraries);
        }
        return new ProjectLayout(new ArrayList<>(sources), new ArrayList<>(classes));
    }

    /**
     * The folders whose JARs are put on the classpath of {@code file}, whether or not they exist
     * yet, most specific first: {@code lib} and {@code libs} of the file's Maven module (if it is
     * in one) and of the opened folder.
     *
     * @param file          a source file; may be null to ask about the opened folder alone
     * @param projectFolder the folder opened in the editor; may be null
     */
    public static List<Path> libraryFolders(Path file, Path projectFolder) {
        Set<Path> folders = new LinkedHashSet<>();
        Path module = file == null ? null : nearestMavenModule(file.toAbsolutePath().normalize());
        for (Path base : new Path[] {module, projectFolder == null ? null : projectFolder.toAbsolutePath().normalize()}) {
            if (base != null) {
                LIBRARY_FOLDER_NAMES.forEach(name -> folders.add(base.resolve(name)));
            }
        }
        return new ArrayList<>(folders);
    }

    /** The Maven module (nearest folder with a {@code pom.xml}) a file belongs to, if any. */
    public static Optional<Path> mavenModuleOf(Path file) {
        return Optional.ofNullable(nearestMavenModule(file.toAbsolutePath().normalize()));
    }

    /**
     * The directory that is the root of the package hierarchy {@code file} lives in, i.e. the file's
     * directory with the package's directories stripped off. Empty when the directory structure does
     * not match the package name.
     */
    public static Optional<Path> sourceRootOf(Path file, String packageName) {
        Path dir = file.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return Optional.empty();
        }
        if (packageName.isEmpty()) {
            return Optional.of(dir);
        }
        String[] segments = packageName.split("\\.");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (dir == null || dir.getFileName() == null || !dir.getFileName().toString().equals(segments[i])) {
                return Optional.empty();
            }
            dir = dir.getParent();
        }
        return Optional.ofNullable(dir);
    }

    private static Path nearestMavenModule(Path file) {
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
        }
        return null;
    }

    private static List<Path> childModules(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(child -> Files.isRegularFile(child.resolve("pom.xml")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list " + dir + "; its Maven modules are ignored", e);
            return List.of();
        }
    }

    private static void addModule(Path module, boolean includeTests, Set<Path> sources, Set<Path> classes) {
        addIfDirectory(sources, module.resolve("src/main/java"));
        addIfDirectory(classes, module.resolve("target/classes"));
        if (includeTests) {
            addIfDirectory(sources, module.resolve("src/test/java"));
            addIfDirectory(classes, module.resolve("target/test-classes"));
        }
        addJars(classes, module.resolve("target/dependency"));
        LIBRARY_FOLDER_NAMES.forEach(name -> addJars(classes, module.resolve(name)));
    }

    private static void addIfDirectory(Set<Path> target, Path dir) {
        if (Files.isDirectory(dir)) {
            target.add(dir);
        }
    }

    private static void addJars(Set<Path> target, Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().forEach(target::add);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list " + dir + "; its jars are left off the classpath", e);
        }
    }
}
