package dev.jeditor.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLayoutTest {

    @TempDir
    Path root;

    @Test
    void sourceRootIsDirectoryAbovePackageDirectories() {
        Path file = root.resolve("src/com/example/A.java");
        assertEquals(Optional.of(root.resolve("src")), ProjectLayout.sourceRootOf(file, "com.example"));
    }

    @Test
    void sourceRootOfUnnamedPackageIsTheFilesDirectory() {
        assertEquals(Optional.of(root), ProjectLayout.sourceRootOf(root.resolve("A.java"), ""));
    }

    @Test
    void noSourceRootWhenDirectoriesDoNotMatchPackage() {
        Path file = root.resolve("src/com/other/A.java");
        assertEquals(Optional.empty(), ProjectLayout.sourceRootOf(file, "com.example"));
    }

    @Test
    void plainFolderUsesPackageDerivedRootOnly() throws IOException {
        Path file = write("stuff/p/q/A.java", "package p.q; class A {}");
        ProjectLayout layout = ProjectLayout.detect(file, Files.readString(file));
        assertEquals(List.of(root.resolve("stuff")), layout.sourceRoots());
        assertTrue(layout.classpath().isEmpty());
    }

    @Test
    void mavenModuleContributesMainAndTestLocations() throws IOException {
        write("pom.xml", "<project/>");
        Path file = write("src/test/java/p/ATest.java", "package p; class ATest {}");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("target/classes"));
        Files.createDirectories(root.resolve("target/test-classes"));
        write("target/dependency/lib-b.jar", "");
        write("target/dependency/lib-a.jar", "");
        write("target/dependency/notes.txt", "");

        ProjectLayout layout = ProjectLayout.detect(file, Files.readString(file));

        assertEquals(List.of(root.resolve("src/test/java"), root.resolve("src/main/java")), layout.sourceRoots());
        assertEquals(List.of(
                root.resolve("target/classes"),
                root.resolve("target/test-classes"),
                root.resolve("target/dependency/lib-a.jar"),
                root.resolve("target/dependency/lib-b.jar")), layout.classpath());
    }

    @Test
    void siblingModulesOfAMultiModuleBuildAreVisibleButTheirTestsAreNot() throws IOException {
        write("pom.xml", "<project/>");
        write("core/pom.xml", "<project/>");
        write("app/pom.xml", "<project/>");
        Files.createDirectories(root.resolve("core/src/main/java"));
        Files.createDirectories(root.resolve("core/src/test/java"));
        Files.createDirectories(root.resolve("core/target/classes"));
        Path file = write("app/src/main/java/p/Main.java", "package p; class Main {}");

        ProjectLayout layout = ProjectLayout.detect(file, Files.readString(file));

        assertEquals(List.of(root.resolve("app/src/main/java"), root.resolve("core/src/main/java")), layout.sourceRoots());
        assertEquals(List.of(root.resolve("core/target/classes")), layout.classpath());
        assertFalse(layout.sourceRoots().contains(root.resolve("core/src/test/java")));
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
