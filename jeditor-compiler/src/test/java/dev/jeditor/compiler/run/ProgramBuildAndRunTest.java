package dev.jeditor.compiler.run;

import dev.jeditor.compiler.ProjectLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramBuildAndRunTest {

    private static final int CURRENT = Runtime.version().feature();

    private final ProgramBuilder builder = new ProgramBuilder();
    private final JavaRuntime runtime = JavaRuntime.current().orElseThrow();

    @TempDir
    Path dir;

    @Test
    void buildsAndRunsAMainClassWithItsDependencies() throws Exception {
        write("src/app/util/Greeter.java", """
                package app.util;
                public class Greeter {
                    public static String greet(String who) { return "Hello, " + who + "!"; }
                }
                """);
        String source = """
                package app;
                import app.util.Greeter;
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(Greeter.greet(args[0]) + " \\u00e9\\u4e16");
                        System.err.println("java " + Runtime.version().feature());
                        System.exit(3);
                    }
                }
                """;
        Path main = write("src/app/Main.java", source);
        Path out = dir.resolve("out");

        ProgramBuilder.BuildResult build = builder.build(main, ProjectLayout.detect(main, source), out, CURRENT);

        assertTrue(build.success(), build.messages().toString());
        assertTrue(Files.isRegularFile(out.resolve("app/Main.class")));
        assertTrue(Files.isRegularFile(out.resolve("app/util/Greeter.class")), "dependency compiled implicitly");

        Process process = JavaLauncher.start(runtime, List.of(out), "app.Main", List.of("world"), dir);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));

        assertEquals(List.of("Hello, world! \u00e9\u4e16", "java " + CURRENT), programLines(output),
                "stdout and stderr are both captured, as UTF-8");
        assertEquals(3, process.exitValue());
    }

    @Test
    void programCanReadStandardInputAndSeesWorkingDirectory() throws Exception {
        String source = """
                public class Echo {
                    public static void main(String[] args) throws Exception {
                        var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                        System.out.println("got " + in.readLine());
                        System.out.println(new java.io.File("marker.txt").exists());
                    }
                }
                """;
        Path file = write("Echo.java", source);
        write("work/marker.txt", "");
        Path out = dir.resolve("out");
        assertTrue(builder.build(file, ProjectLayout.NONE, out, CURRENT).success());

        Process process = JavaLauncher.start(runtime, List.of(out), "Echo", List.of(), dir.resolve("work"));
        process.getOutputStream().write("ping\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(List.of("got ping", "true"), programLines(output));
    }

    @Test
    void compileErrorsFailTheBuildWithLocations() throws IOException {
        Path file = write("Broken.java", """
                public class Broken {
                    public static void main(String[] args) {
                        int x = "text";
                    }
                }
                """);

        ProgramBuilder.BuildResult build = builder.build(file, ProjectLayout.NONE, dir.resolve("out"), CURRENT);

        assertFalse(build.success());
        assertEquals(1, build.messages().size());
        String message = build.messages().get(0);
        assertTrue(message.startsWith(file + ":3: error: incompatible types"), message);
    }

    @Test
    void warningsDoNotFailTheBuildOrProduceMessages() throws IOException {
        Path file = write("Raw.java", "public class Raw { java.util.List raw = new java.util.ArrayList(); }");
        ProgramBuilder.BuildResult build = builder.build(file, ProjectLayout.NONE, dir.resolve("out"), CURRENT);
        assertTrue(build.success());
        assertEquals(List.of(), build.messages());
    }

    @Test
    void targetsTheReleaseOfAnOlderRuntime() throws IOException {
        Path file = write("Old.java", "public class Old { public static void main(String[] a) {} }");
        Path out = dir.resolve("out");

        assertTrue(builder.build(file, ProjectLayout.NONE, out, 8).success());

        byte[] classFile = Files.readAllBytes(out.resolve("Old.class"));
        int major = ((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF);
        assertEquals(52, major, "class file version of Java 8");
    }

    @Test
    void languageFeaturesNewerThanTheTargetRuntimeAreRejected() throws IOException {
        Path file = write("Modern.java", "public class Modern { record Point(int x, int y) {} }");
        ProgramBuilder.BuildResult build = builder.build(file, ProjectLayout.NONE, dir.resolve("out"), 11);
        assertFalse(build.success());
        assertTrue(build.messages().get(0).contains("records"), build.messages().toString());
    }

    @Test
    void apisNewerThanTheTargetRuntimeAreRejected() throws IOException {
        // String.isBlank() arrived in Java 11.
        Path file = write("Api.java", "public class Api { boolean b = \"\".isBlank(); }");
        assertFalse(builder.build(file, ProjectLayout.NONE, dir.resolve("out8"), 8).success());
        assertTrue(builder.build(file, ProjectLayout.NONE, dir.resolve("out11"), 11).success());
    }

    @Test
    void runtimeNewerThanTheCompilerIsFine() throws IOException {
        Path file = write("Fine.java", "public class Fine {}");
        assertTrue(builder.build(file, ProjectLayout.NONE, dir.resolve("out"), CURRENT + 5).success());
    }

    @Test
    void runtimesTooOldToTargetAreReported() throws IOException {
        Path file = write("Ancient.java", "public class Ancient {}");
        ProgramBuilder.BuildResult build = builder.build(file, ProjectLayout.NONE, dir.resolve("out"), 6);
        assertFalse(build.success());
        assertTrue(build.messages().get(0).contains("Java 6"), build.messages().toString());
    }

    @Test
    void withoutACompilerTheBuildFailsWithAnExplanation() throws IOException {
        Path file = write("A.java", "public class A {}");
        ProgramBuilder.BuildResult build = new ProgramBuilder(null).build(file, ProjectLayout.NONE, dir.resolve("out"), CURRENT);
        assertFalse(build.success());
        assertTrue(build.messages().get(0).contains("JDK"), build.messages().toString());
    }

    @Test
    void commandLineShape() {
        JavaRuntime fake = new JavaRuntime(Path.of("/opt/java"), "17.0.9", 17, false);
        List<String> command = JavaLauncher.command(fake, List.of(Path.of("/a"), Path.of("/b.jar")), "p.Main", List.of("x", "y z"));

        assertEquals(fake.javaExecutable().toString(), command.get(0));
        int cp = command.indexOf("-cp");
        assertEquals(Path.of("/a") + java.io.File.pathSeparator + Path.of("/b.jar"), command.get(cp + 1));
        assertEquals(List.of("p.Main", "x", "y z"), command.subList(cp + 2, command.size()));
    }

    /** Output lines minus the JVM's own "Picked up JAVA_TOOL_OPTIONS" banner, present in some environments. */
    private static List<String> programLines(String output) {
        return output.lines().filter(line -> !line.startsWith("Picked up ")).toList();
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
