package dev.jeditor.compiler.run;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.JavaSourceAnalyzer;
import dev.jeditor.compiler.ProjectLayout;
import dev.jeditor.compiler.run.ClasspathInspector.Entry;
import dev.jeditor.compiler.run.ClasspathInspector.Kind;
import dev.jeditor.compiler.run.ClasspathInspector.LibraryFolder;
import dev.jeditor.compiler.run.ClasspathInspector.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathInspectorTest {

    private static final String USES_GREETER = """
            package app;
            public class Main {
                public static void main(String[] args) {
                    System.out.println(greet.Greeter.hello());
                }
            }
            """;

    @TempDir
    Path project;

    // ---- the library folder: dropping a JAR in makes it usable --------------------------------

    @Test
    void aJarAddedToTheLibFolderOfAPlainProjectIsUsedForCheckingBuildingAndRunning() throws Exception {
        Path main = write("src/app/Main.java", USES_GREETER);
        JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

        // Without the library the code does not compile...
        assertTrue(analyzer.analyze(main, USES_GREETER, ProjectLayout.detect(main, USES_GREETER, project)).hasErrors());
        Path libFolder = ClasspathInspector.folderForNewJars(main, project).orElseThrow();
        assertEquals(project.resolve("lib"), libFolder, "offered even though it does not exist yet");

        // ...dropping the JAR into the folder the inspector names fixes that, with nothing else to configure.
        greeterJar(libFolder.resolve("greeter-1.2.0.jar"), "1.2.0", true);

        ProjectLayout layout = ProjectLayout.detect(main, USES_GREETER, project);
        AnalysisResult result = analyzer.analyze(main, USES_GREETER, layout);
        assertEquals(List.of(), result.diagnostics());

        Path out = project.resolve("out");
        assertTrue(new ProgramBuilder().build(main, layout, out, Runtime.version().feature()).success());
        Process process = JavaLauncher.start(JavaRuntime.current().orElseThrow(),
                Stream.concat(Stream.of(out), layout.classpath().stream()).toList(), "app.Main", List.of(), project);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertTrue(output.contains("hello from greeter"), output);

        BuildPlanner.BuildTarget projectBuild = BuildPlanner.forProject(project).get(0);
        assertTrue(new ProgramBuilder().build(projectBuild, Runtime.version().feature()).success(), "Build Project sees it too");
    }

    @Test
    void aFolderNamedLibsWorksToo() throws IOException {
        Path main = write("Main.java", "class Main { greet.Greeter g; }");
        greeterJar(project.resolve("libs/greeter.jar"), null, false);

        assertEquals(List.of(project.resolve("libs/greeter.jar")),
                ProjectLayout.detect(main, "class Main {}", project).classpath());
        assertEquals(Optional.of(project.resolve("libs")), ClasspathInspector.folderForNewJars(main, project),
                "new JARs go where JARs already are");
    }

    @Test
    void withoutAnOpenedFolderOrMavenModuleThereIsNoLibraryFolder() throws IOException {
        Path loose = write("elsewhere/Solo.java", "class Solo {}");
        assertEquals(Optional.empty(), ClasspathInspector.folderForNewJars(loose, null));
        assertEquals(Optional.empty(), ClasspathInspector.folderForNewJars(null, null));
        assertEquals(List.of(), ClasspathInspector.inspect(loose, "class Solo {}", null, List.of()).libraryFolders());
    }

    // ---- the report ---------------------------------------------------------------------------

    @Test
    void reportListsEntriesInSearchOrderWithWhatTheyAre() throws IOException {
        Path main = write("src/app/Main.java", USES_GREETER);
        greeterJar(project.resolve("lib/greeter-1.2.0.jar"), "1.2.0", true);
        plainJar(project.resolve("lib/alpha.jar"), "util/A.class");

        Report report = ClasspathInspector.inspect(main, USES_GREETER, project, List.of("app.Main"));

        assertEquals(main, report.file());
        assertEquals(List.of("app.Main"), report.mainClasses());
        assertEquals(List.of(project.resolve("src")), report.sourceFolders());
        assertEquals(List.of(Kind.COMPILED_ON_RUN, Kind.JAR, Kind.JAR), report.classpath().stream().map(Entry::kind).toList());
        assertEquals(List.of("this project's classes", "alpha.jar", "greeter-1.2.0.jar"),
                report.classpath().stream().map(Entry::name).toList());
        assertEquals(project.resolve("lib/alpha.jar") + java.io.File.pathSeparator + project.resolve("lib/greeter-1.2.0.jar"),
                report.classpathString());
        assertEquals(2, report.jars().size());
        assertEquals(List.of(), report.warnings());
    }

    @Test
    void jarDetailsComeFromMavenMetadataThenManifestThenFileName() throws IOException {
        Path main = write("Main.java", "class Main {}");
        greeterJar(project.resolve("lib/a-maven.jar"), "1.2.0", true);
        greeterJar(project.resolve("lib/b-manifest.jar"), "4.5", false);
        plainJar(project.resolve("lib/c-plain-7.1.3.jar"), "x/y/Z.class", "x/W.class", "Top.class", "META-INF/versions/9/x/W.class");

        List<Entry> jars = ClasspathInspector.inspect(main, "class Main {}", project, List.of()).jars();

        Entry maven = jars.get(0);
        assertEquals("org.example:greeter", maven.coordinates());
        assertEquals("1.2.0", maven.version());
        assertEquals("library folder lib", maven.origin());
        assertEquals(1, maven.classCount());
        assertEquals(List.of("greet"), maven.packages());
        assertTrue(maven.sizeBytes() > 0);

        Entry manifest = jars.get(1);
        assertEquals("Greeter Library", manifest.coordinates());
        assertEquals("4.5", manifest.version());

        Entry plain = jars.get(2);
        assertNull(plain.coordinates());
        assertEquals("7.1.3", plain.version(), "taken from the file name");
        assertEquals(3, plain.classCount(), "multi-release copies under META-INF are not counted twice");
        assertEquals(List.of("(unnamed package)", "x", "x.y"), plain.packages());
    }

    @Test
    void mavenModuleReportsItsOutputFoldersCopiedDependenciesAndLib() throws IOException {
        write("pom.xml", "<project/>");
        Path main = write("src/main/java/app/Main.java", "package app; public class Main {}");
        write("target/classes/app/Main.class", "");
        write("target/classes/app/util/Helper.class", "");
        plainJar(project.resolve("target/dependency/dep-2.0.jar"), "d/D.class");
        plainJar(project.resolve("lib/local.jar"), "l/L.class");

        Report report = ClasspathInspector.inspect(main, "package app; public class Main {}", project, List.of());

        assertEquals(List.of("compiled from the sources each time the program is run",
                        "Maven build output of module " + project.getFileName(),
                        "Maven dependency copied to target/dependency",
                        "library folder lib"),
                report.classpath().stream().map(Entry::origin).toList());
        Entry output = report.classpath().get(1);
        assertEquals(Kind.CLASSES_FOLDER, output.kind());
        assertEquals(2, output.classCount());
        assertEquals(List.of("app", "app.util"), output.packages());
        assertEquals(List.of(new LibraryFolder(project.resolve("lib"), true, 1, true)), report.libraryFolders());
        assertEquals(List.of(), report.warnings(), "dependencies were copied, so nothing to warn about");
    }

    @Test
    void reportForTheOpenedFolderAloneListsItsLibraries() throws IOException {
        plainJar(project.resolve("lib/one.jar"), "o/O.class");

        Report report = ClasspathInspector.inspect(null, null, project, List.of());

        assertNull(report.file());
        assertEquals(List.of("one.jar"), report.classpath().stream().map(Entry::name).toList());
        assertEquals(List.of(new LibraryFolder(project.resolve("lib"), true, 1, true)), report.libraryFolders());
    }

    // ---- warnings -----------------------------------------------------------------------------

    @Test
    void warnsWhenTheSameLibraryIsPresentTwice() throws IOException {
        Path main = write("Main.java", "class Main {}");
        plainJar(project.resolve("lib/commons-lang3-3.12.0.jar"), "a/A.class");
        plainJar(project.resolve("lib/commons-lang3-3.14.0.jar"), "a/A.class");
        plainJar(project.resolve("lib/commons-io-2.15.1.jar"), "b/B.class");

        List<String> warnings = ClasspathInspector.inspect(main, "class Main {}", project, List.of()).warnings();

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("commons-lang3 is on the classpath 2 times"), warnings.get(0));
        assertTrue(warnings.get(0).endsWith("Only the first, commons-lang3-3.12.0.jar, is used."), warnings.get(0));
    }

    @Test
    void warnsAboutJarsWithoutClassesAndBrokenJars() throws IOException {
        Path main = write("Main.java", "class Main {}");
        plainJar(project.resolve("lib/thing-1.0-sources.jar"), "a/A.java");
        write("lib/broken.jar", "this is not a zip file");

        Report report = ClasspathInspector.inspect(main, "class Main {}", project, List.of());

        assertEquals(2, report.jars().size(), "a broken JAR is still listed, since it is on the classpath");
        assertEquals(2, report.warnings().size(), report.warnings().toString());
        assertTrue(report.warnings().stream().allMatch(w -> w.contains("contains no classes")), report.warnings().toString());
    }

    @Test
    void warnsThatPomDependenciesAreNotResolvedUntilCopied() throws IOException {
        write("pom.xml", "<project/>");
        Path main = write("src/main/java/Main.java", "class Main {}");

        List<String> warnings = ClasspathInspector.inspect(main, "class Main {}", project, List.of()).warnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("mvn dependency:copy-dependencies"), warnings.get(0));

        Files.createDirectories(project.resolve("target/dependency"));
        assertEquals(List.of(), ClasspathInspector.inspect(main, "class Main {}", project, List.of()).warnings());
    }

    // ---- small helpers under test -------------------------------------------------------------

    @Test
    void splitsJarNamesIntoNameAndVersion() {
        assertArrayEquals(new String[] {"commons-lang3", "3.14.0"}, ClasspathInspector.baseNameAndVersion("commons-lang3-3.14.0.jar"));
        assertArrayEquals(new String[] {"guava", "33.0.0"}, ClasspathInspector.baseNameAndVersion("guava-33.0.0-jre.jar"));
        assertArrayEquals(new String[] {"thing", "1.0"}, ClasspathInspector.baseNameAndVersion("thing-1.0-SNAPSHOT.jar"));
        assertArrayEquals(new String[] {"junit-jupiter-api", "5.11.4"}, ClasspathInspector.baseNameAndVersion("junit-jupiter-api-5.11.4.jar"));
        assertArrayEquals(new String[] {"local", null}, ClasspathInspector.baseNameAndVersion("local.jar"));
        assertArrayEquals(new String[] {"my-tool", null}, ClasspathInspector.baseNameAndVersion("my-tool.jar"));
    }

    @Test
    void originOfUnrecognisedLocations() throws IOException {
        Path jar = project.resolve("somewhere/x.jar");
        plainJar(jar, "a/A.class");
        assertEquals("JAR", ClasspathInspector.originOf(jar));
        assertEquals("folder of classes", ClasspathInspector.originOf(Files.createDirectories(project.resolve("bin"))));
        assertFalse(ClasspathInspector.originOf(Files.createDirectories(project.resolve("m/target/test-classes"))).isEmpty());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** A real library: class {@code greet.Greeter} with {@code static String hello()}. */
    private void greeterJar(Path jar, String version, boolean withMavenMetadata) throws IOException {
        Path sources = Files.createDirectories(project.resolve(".fixture/src/greet"));
        Path classes = Files.createDirectories(project.resolve(".fixture/classes"));
        Path source = Files.writeString(sources.resolve("Greeter.java"),
                "package greet; public class Greeter { public static String hello() { return \"hello from greeter\"; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()));

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (!withMavenMetadata) {
            manifest.getMainAttributes().putValue("Implementation-Title", "Greeter Library");
            if (version != null) {
                manifest.getMainAttributes().putValue("Implementation-Version", version);
            }
        }
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out, manifest)) {
            zip.putNextEntry(new JarEntry("greet/Greeter.class"));
            zip.write(Files.readAllBytes(classes.resolve("greet/Greeter.class")));
            zip.closeEntry();
            if (withMavenMetadata) {
                zip.putNextEntry(new JarEntry("META-INF/maven/org.example/greeter/pom.properties"));
                zip.write(("groupId=org.example\nartifactId=greeter\nversion=" + version + "\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** A JAR with the given entries, all empty: enough for everything that only reads names. */
    private static void plainJar(Path jar, String... entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new JarEntry(entry));
                zip.closeEntry();
            }
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
