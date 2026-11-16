package dev.jeditor.compiler.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchRecordTest {

    @TempDir
    Path project;

    private final JavaRuntime runtime = new JavaRuntime(Path.of("/opt/jdk-21"), "21.0.2", 21, true);

    private LaunchRecord launch(List<Path> libraries) throws IOException {
        Path temp = Files.createDirectories(project.resolve(".tmp/jeditor-run42"));
        for (Path library : libraries) {
            Files.createDirectories(library.getParent());
            if (!Files.exists(library)) {
                Files.createFile(library);
            }
        }
        List<Path> classpath = new java.util.ArrayList<>(List.of(temp));
        classpath.addAll(libraries);
        return new LaunchRecord(project.resolve("src/app/Main.java"), "app.Main", runtime, project, project, temp,
                classpath, JavaLauncher.command(runtime, classpath, "app.Main", List.of()), project.resolve("out"), null, null);
    }

    @Test
    void describesMainClassRuntimeAndWorkingDirectory() throws IOException {
        String text = launch(List.of()).describe();

        assertTrue(text.startsWith("MAIN CLASS\n  app.Main\n  declared in " + Path.of("src", "app", "Main.java") + "\n"), text);
        assertTrue(text.contains("JAVA RUNTIME\n  Java 21.0.2 (JDK)\n  " + runtime.javaExecutable() + "\n"), text);
        assertTrue(text.contains("WORKING DIRECTORY\n  " + project + "\n"), text);
    }

    @Test
    void listsEveryClasspathEntryInOrderWithTheReasonItIsThere() throws IOException {
        Path lang = project.resolve("lib/commons-lang3-3.14.0.jar");
        Path io = project.resolve("lib/commons-io-2.15.1.jar");
        LaunchRecord launch = launch(List.of(io, lang));

        String text = launch.describe();

        assertTrue(text.contains("CLASSPATH  (3 entries, searched in this order; the first one that has a class wins)\n"
                + "  1. " + launch.compiledClasses() + "\n"
                + "       your project's own classes, compiled just now from the sources (a temporary folder)\n"
                + "  2. " + io + "\n"
                + "       a library: every .jar in the \"lib\" folder is added automatically\n"
                + "  3. " + lang + "\n"
                + "       a library: every .jar in the \"lib\" folder is added automatically\n"), text);
        assertEquals(launch.compiledClasses() + File.pathSeparator + io + File.pathSeparator + lang, launch.classpathString());
    }

    @Test
    void explainsThatLibIsTheEditorsDoingNotJavas() throws IOException {
        String text = launch(List.of(project.resolve("lib/a.jar"), project.resolve("lib/b.jar"))).describe();

        assertTrue(text.contains("Java does not look for libraries by itself, and has no rule about a \"lib\""), text);
        assertTrue(text.contains("it compiled your sources into a temporary folder and put it first"), text);
        assertTrue(text.contains("it added each of the 2 JARs it found in " + project.resolve("lib")), text);
    }

    @Test
    void withNoLibrariesItSaysWhereToPutThem() throws IOException {
        String text = launch(List.of()).describe();
        assertTrue(text.contains("CLASSPATH  (1 entry,"), text);
        assertTrue(text.contains("it found no library JARs to add. Put JARs in " + project.resolve("lib")), text);
        assertFalse(text.contains("every JAR in it"), text);
    }

    @Test
    void otherProjectEntriesAreCountedSeparately() throws IOException {
        Path mavenOutput = Files.createDirectories(project.resolve("core/target/classes"));
        LaunchRecord launch = launch(List.of(project.resolve("lib/a.jar")));
        List<Path> classpath = new java.util.ArrayList<>(launch.classpath());
        classpath.add(mavenOutput);
        launch = new LaunchRecord(launch.sourceFile(), launch.mainClass(), runtime, project, project, launch.compiledClasses(),
                classpath, launch.command(), launch.buildOutput(), null, null);

        String text = launch.describe();
        assertTrue(text.contains("  3. " + mavenOutput + "\n       Maven build output of module core\n"), text);
        assertTrue(text.contains("it added the JAR it found in " + project.resolve("lib")), text);
        assertTrue(text.contains("it added 1 other entry that belongs to the project"), text);
    }

    @Test
    void showsTheExactCommandAndTheResult() throws IOException {
        LaunchRecord running = launch(List.of(project.resolve("lib/a.jar")));
        if (File.separatorChar == '/') {
            String java = runtime.javaExecutable().toString();
            assertTrue(running.describe().contains("EXACT COMMAND THE EDITOR RAN\n"
                    + "  " + java + " \\\n"
                    + "      -Dfile.encoding=UTF-8 \\\n"
                    + "      -Dstdout.encoding=UTF-8 \\\n"
                    + "      -Dstderr.encoding=UTF-8 \\\n"
                    + "      -cp " + running.classpathString() + " \\\n"
                    + "      app.Main\n"), running.describe());
            // Laid out over several lines, it is still the same command once the shell joins them.
            assertEquals(running.commandLine(), running.commandForDisplay("  ").replaceAll(" \\\\\n\\s+", " ").strip());
        }
        assertTrue(running.commandLine().contains(" -cp " + running.classpathString() + " app.Main"), running.commandLine());
        assertTrue(running.describe().endsWith("RESULT\n  still running\n"));

        assertTrue(running.finished(0, Duration.ofMillis(1340)).describe().endsWith("RESULT\n  exit code 0 (success) after 1.3 s\n"));
        assertTrue(running.finished(3, Duration.ofMillis(200)).describe().endsWith("RESULT\n  exit code 3 (failure) after 0.2 s\n"));
    }

    @Test
    void commandToRunByHandUsesTheBuildOutputAndAWildcardPerLibraryFolder() throws IOException {
        LaunchRecord launch = launch(List.of(project.resolve("lib/a.jar"), project.resolve("lib/b.jar")));

        assertEquals("java -cp '" + "out" + File.pathSeparator + "lib" + File.separator + "*' app.Main", launch.commandToRunByHand());
        String text = launch.describe();
        assertTrue(text.contains("1. Build > Build Project   (compiles into out)"), text);
        assertTrue(text.contains("       " + launch.commandToRunByHand() + "\n"), text);
    }

    @Test
    void theCommandToRunByHandReallyWorks() throws Exception {
        // A real project with a real library, built the way "Build Project" does it.
        Path source = project.resolve("src/app/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package app; public class Main { public static void main(String[] a) { System.out.println(lib.Tool.name()); } }");
        Path libSource = project.resolve(".fixture/lib/Tool.java");
        Files.createDirectories(libSource.getParent());
        Files.writeString(libSource, "package lib; public class Tool { public static String name() { return \"tool works\"; } }");
        Path libClasses = Files.createDirectories(project.resolve(".fixture/classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", libClasses.toString(), libSource.toString()));
        Path jar = project.resolve("lib/tool.jar");
        Files.createDirectories(jar.getParent());
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("lib/Tool.class"));
            out.write(Files.readAllBytes(libClasses.resolve("lib/Tool.class")));
            out.closeEntry();
        }
        BuildPlanner.BuildTarget build = BuildPlanner.forProject(project).get(0);
        assertTrue(new ProgramBuilder().build(build, Runtime.version().feature()).success());

        JavaRuntime current = JavaRuntime.current().orElseThrow();
        LaunchRecord launch = new LaunchRecord(source, "app.Main", current, project, project, project.resolve(".tmp/run"),
                List.of(project.resolve(".tmp/run"), jar), List.of(), build.outputDir(), null, null);

        // Run exactly what the user is told to type, through a shell, from the working directory.
        String command = launch.commandToRunByHand().replaceFirst("^java", LaunchRecord.quote(current.javaExecutable().toString()));
        Process process = new ProcessBuilder("sh", "-c", command).directory(project.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));

        assertTrue(output.contains("tool works"), command + "\n" + output);
        assertEquals(0, process.exitValue());
    }

    @Test
    void quotingOnlyWhereAShellNeedsIt() {
        assertEquals("/usr/bin/java", LaunchRecord.quote("/usr/bin/java"));
        assertEquals("-Dfile.encoding=UTF-8", LaunchRecord.quote("-Dfile.encoding=UTF-8"));
        if (File.separatorChar == '/') {
            assertEquals("'/my projects/app'", LaunchRecord.quote("/my projects/app"));
            assertEquals("'out:lib/*'", LaunchRecord.quote("out:lib/*"));
            assertEquals("'it'\\''s'", LaunchRecord.quote("it's"));
            assertEquals("''", LaunchRecord.quote(""));
        }
    }
}
