package dev.jeditor.compiler.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanTest {

    @TempDir
    Path root;

    @Test
    void plainFolderCleansOutOnly() throws IOException {
        write("src/app/Main.java", "class Main {}");
        write("out/app/Main.class", "12345");
        write("out/app/util/Helper.class", "123");
        write("target/keep.txt", "not ours in a plain folder");
        write("lib/library.jar", "jar");

        List<Path> targets = BuildPlanner.cleanTargets(root);
        assertEquals(List.of(root.resolve("out")), targets);

        BuildPlanner.CleanResult result = BuildPlanner.clean(targets);

        assertEquals(List.of(root.resolve("out")), result.removed());
        assertEquals(2, result.files());
        assertEquals(8, result.bytes());
        assertFalse(Files.exists(root.resolve("out")));
        assertTrue(Files.exists(root.resolve("src/app/Main.java")));
        assertTrue(Files.exists(root.resolve("target/keep.txt")));
        assertTrue(Files.exists(root.resolve("lib/library.jar")));
    }

    @Test
    void mavenProjectCleansTargetOfEveryModuleLikeMvnClean() throws IOException {
        write("pom.xml", "<project/>");
        write("core/pom.xml", "<project/>");
        write("app/pom.xml", "<project/>");
        write("docs/target/not-a-module.txt", "no pom.xml here");
        write("target/root.txt", "x");
        write("core/target/classes/core/Engine.class", "x");
        write("app/target/classes/app/Main.class", "x");
        write("app/target/dependency/lib.jar", "x");
        write("out/old.class", "not Maven's output");
        write("app/src/main/java/app/Main.java", "class Main {}");

        List<Path> targets = BuildPlanner.cleanTargets(root);
        assertEquals(List.of(root.resolve("target"), root.resolve("app/target"), root.resolve("core/target")), targets);

        assertEquals(4, BuildPlanner.clean(targets).files());
        for (Path target : targets) {
            assertFalse(Files.exists(target), target.toString());
        }
        assertTrue(Files.exists(root.resolve("docs/target/not-a-module.txt")));
        assertTrue(Files.exists(root.resolve("out/old.class")));
        assertTrue(Files.exists(root.resolve("app/src/main/java/app/Main.java")));
    }

    @Test
    void nothingToCleanAndCleaningTwice() throws IOException {
        assertEquals(List.of(), BuildPlanner.cleanTargets(root));
        write("out/A.class", "x");
        List<Path> targets = BuildPlanner.cleanTargets(root);
        BuildPlanner.clean(targets);
        BuildPlanner.CleanResult again = BuildPlanner.clean(targets);
        assertEquals(List.of(), again.removed());
        assertEquals(0, again.files());
    }

    @Test
    void linksAreRemovedButWhatTheyPointToIsNeverTouched() throws IOException {
        Path precious = write("elsewhere/precious.txt", "keep me");
        write("out/A.class", "x");
        Files.createSymbolicLink(root.resolve("out/link-to-elsewhere"), root.resolve("elsewhere"));
        // An output folder that is itself a link is not taken at all.
        write("pom-project/pom.xml", "<project/>");
        Files.createSymbolicLink(root.resolve("pom-project/target"), root.resolve("elsewhere"));

        BuildPlanner.clean(BuildPlanner.cleanTargets(root));

        assertFalse(Files.exists(root.resolve("out")));
        assertEquals("keep me", Files.readString(precious));
        assertEquals(List.of(), BuildPlanner.cleanTargets(root.resolve("pom-project")));
    }

    @Test
    void buildThenCleanLeavesTheProjectAsBefore() throws IOException {
        write("src/app/Main.java", "package app; public class Main { util.Helper h; }");
        write("src/util/Helper.java", "package util; public class Helper {}");
        BuildPlanner.BuildTarget build = BuildPlanner.forProject(root).get(0);
        assertTrue(new ProgramBuilder().build(build, Runtime.version().feature()).success());
        assertTrue(Files.exists(root.resolve("out/util/Helper.class")));

        BuildPlanner.clean(BuildPlanner.cleanTargets(root));

        try (var files = Files.walk(root)) {
            assertEquals(List.of(), files.filter(f -> f.toString().endsWith(".class")).toList());
        }
        assertTrue(Files.exists(root.resolve("src/util/Helper.java")));
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
