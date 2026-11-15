package dev.jeditor.compiler.run;

import dev.jeditor.compiler.run.BuildPlanner.BuildTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildPlannerTest {

    private static final int CURRENT = Runtime.version().feature();

    private final ProgramBuilder builder = new ProgramBuilder();

    @TempDir
    Path root;

    // ---- single file --------------------------------------------------------------------------

    @Test
    void fileInAPlainFolderGoesToOutUnderTheProjectFolder() throws IOException {
        String source = "package app; public class Main { util.Helper h; }";
        Path main = write("src/app/Main.java", source);
        write("src/util/Helper.java", "package util; public class Helper {}");

        BuildTarget target = BuildPlanner.forFile(main, source, root);

        assertEquals(root.resolve("out"), target.outputDir());
        assertEquals(List.of(main), target.sources());
        assertEquals("Main.java", target.name());

        assertTrue(builder.build(target, CURRENT).success());
        assertTrue(Files.isRegularFile(root.resolve("out/app/Main.class")));
        assertTrue(Files.isRegularFile(root.resolve("out/util/Helper.class")), "what the file uses is compiled with it");
    }

    @Test
    void fileOutsideTheProjectFolderGoesToOutNextToIt() throws IOException {
        Path file = write("elsewhere/Solo.java", "class Solo {}");
        assertEquals(root.resolve("elsewhere/out"), BuildPlanner.forFile(file, "class Solo {}", root.resolve("project")).outputDir());
        assertEquals(root.resolve("elsewhere/out"), BuildPlanner.forFile(file, "class Solo {}", null).outputDir());
    }

    @Test
    void mavenMainAndTestSourcesGoToTheirMavenOutputs() throws IOException {
        write("pom.xml", "<project/>");
        String source = "package p; public class A {}";
        Path main = write("src/main/java/p/A.java", source);
        Path test = write("src/test/java/p/ATest.java", "package p; class ATest { A a; }");

        assertEquals(root.resolve("target/classes"), BuildPlanner.forFile(main, source, root).outputDir());
        assertEquals(root.resolve("target/test-classes"),
                BuildPlanner.forFile(test, Files.readString(test), root).outputDir());
    }

    // ---- whole project ------------------------------------------------------------------------

    @Test
    void plainFolderCompilesEverySourceIntoOut() throws IOException {
        write("src/app/Main.java", "package app; public class Main { lib.Tool t; }");
        write("other-root/lib/Tool.java", "package lib; public class Tool {}");
        write("Top.java", "class Top {}");
        // must be ignored: previous outputs and tool folders
        write("out/stale/Old.java", "this is not java");
        write("target/gen/Gen.java", "nor this");
        write(".hidden/H.java", "nor this");

        List<BuildTarget> targets = BuildPlanner.forProject(root);

        assertEquals(1, targets.size());
        BuildTarget target = targets.get(0);
        assertEquals(root.resolve("out"), target.outputDir());
        assertEquals(List.of(root.resolve("Top.java"), root.resolve("other-root/lib/Tool.java"), root.resolve("src/app/Main.java")),
                target.sources());

        ProgramBuilder.BuildResult result = builder.build(target, CURRENT);
        assertTrue(result.success(), result.messages().toString());
        assertTrue(Files.isRegularFile(root.resolve("out/app/Main.class")));
        assertTrue(Files.isRegularFile(root.resolve("out/lib/Tool.class")));
        assertTrue(Files.isRegularFile(root.resolve("out/Top.class")));
    }

    @Test
    void mavenProjectCompilesMainSourcesOnlyIntoTargetClasses() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/p/A.java", "package p; public class A {}");
        write("src/main/java/p/q/B.java", "package p.q; public class B { p.A a; }");
        write("src/test/java/p/ATest.java", "package p; class ATest { org.junit.Missing m; }"); // would not compile

        List<BuildTarget> targets = BuildPlanner.forProject(root);

        assertEquals(1, targets.size());
        assertEquals(root.resolve("target/classes"), targets.get(0).outputDir());
        assertEquals(2, targets.get(0).sources().size());
        assertTrue(builder.build(targets.get(0), CURRENT).success());
        assertTrue(Files.isRegularFile(root.resolve("target/classes/p/q/B.class")));
        assertFalse(Files.exists(root.resolve("target/classes/p/ATest.class")));
    }

    @Test
    void multiModuleProjectBuildsEachModuleIntoItsOwnOutput() throws IOException {
        write("pom.xml", "<project/>");
        write("core/pom.xml", "<project/>");
        write("app/pom.xml", "<project/>");
        write("docs/readme.txt", "not a module");
        write("core/src/main/java/core/Engine.java", "package core; public class Engine {}");
        write("app/src/main/java/app/Main.java", "package app; public class Main { core.Engine e; }");

        List<BuildTarget> targets = BuildPlanner.forProject(root);

        assertEquals(List.of("app", "core"), targets.stream().map(BuildTarget::name).toList());
        for (BuildTarget target : targets) {
            ProgramBuilder.BuildResult result = builder.build(target, CURRENT);
            assertTrue(result.success(), target.name() + ": " + result.messages());
        }
        assertTrue(Files.isRegularFile(root.resolve("app/target/classes/app/Main.class")));
        assertTrue(Files.isRegularFile(root.resolve("core/target/classes/core/Engine.class")));
        assertFalse(Files.exists(root.resolve("app/target/classes/core")),
                "app builds against core's sources (in either order) without copying core's classes into its own output");
    }

    @Test
    void nothingToBuild() throws IOException {
        assertEquals(List.of(), BuildPlanner.forProject(root));
        write("pom.xml", "<project/>");
        write("notes.txt", "x");
        assertEquals(List.of(), BuildPlanner.forProject(root));
    }

    @Test
    void allKindsOfErrorsAreReportedInOneBuildAndNothingIsWritten() throws IOException {
        write("Mixed.java", "class Mixed {\n    int a = ;\n    String s = 42;\n    int f() { }\n}\n");
        write("Fine.java", "class Fine {}");

        ProgramBuilder.BuildResult result = builder.build(BuildPlanner.forProject(root).get(0), CURRENT);

        assertFalse(result.success());
        assertEquals(3, result.messages().size(), result.messages().toString());
        assertFalse(Files.exists(root.resolve("out/Fine.class")), "a failed build leaves no partial output");
    }

    @Test
    void errorsInAProjectBuildNameTheirFiles() throws IOException {
        write("Good.java", "class Good {}");
        Path bad = write("pkg/Bad.java", "package pkg;\nclass Bad {\n    int x = \"no\";\n}\n");

        ProgramBuilder.BuildResult result = builder.build(BuildPlanner.forProject(root).get(0), CURRENT);

        assertFalse(result.success());
        assertEquals(1, result.messages().size());
        assertTrue(result.messages().get(0).startsWith(bad + ":3: error:"), result.messages().get(0));
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
