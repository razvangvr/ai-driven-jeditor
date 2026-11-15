package dev.jeditor.compiler.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRuntimeTest {

    @TempDir
    Path dir;

    @Test
    void featureVersionFromVersionStrings() {
        assertEquals(8, JavaRuntime.featureVersionOf("1.8.0_392"));
        assertEquals(21, JavaRuntime.featureVersionOf("21.0.2"));
        assertEquals(17, JavaRuntime.featureVersionOf("17"));
        assertEquals(22, JavaRuntime.featureVersionOf("22-ea"));
        assertEquals(11, JavaRuntime.featureVersionOf("11.0.21+9"));
        assertEquals(0, JavaRuntime.featureVersionOf("unknown"));
        assertEquals(0, JavaRuntime.featureVersionOf(""));
    }

    @Test
    void detectsTheRunningJdk() {
        JavaRuntime current = JavaRuntime.current().orElseThrow();
        assertEquals(Runtime.version().feature(), current.featureVersion());
        assertTrue(current.jdk(), "tests run on a JDK");
        assertTrue(Files.isExecutable(current.javaExecutable()));
        assertTrue(current.displayName().matches("Java \\d+.* \\(JDK\\)"), current.displayName());
    }

    @Test
    void acceptsBinDirectoryOrExecutableInsteadOfHome() {
        JavaRuntime current = JavaRuntime.current().orElseThrow();
        assertEquals(Optional.of(current), JavaRuntime.detect(current.home().resolve("bin")));
        assertEquals(Optional.of(current), JavaRuntime.detect(current.javaExecutable()));
    }

    @Test
    void jreIsAnInstallationWithoutCompiler() throws IOException {
        fakeInstallation(dir.resolve("jre"), "JAVA_VERSION=\"1.8.0_392\"\n", false);
        JavaRuntime jre = JavaRuntime.detect(dir.resolve("jre")).orElseThrow();
        assertFalse(jre.jdk());
        assertEquals("1.8.0_392", jre.version());
        assertEquals(8, jre.featureVersion());
        assertEquals("Java 1.8.0_392 (JRE)", jre.displayName());

        fakeInstallation(dir.resolve("jdk"), "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"17.0.9\"\n", true);
        assertTrue(JavaRuntime.detect(dir.resolve("jdk")).orElseThrow().jdk());
    }

    @Test
    void macOsBundleResolvesToContentsHome() throws IOException {
        Path home = dir.resolve("temurin-21.jdk/Contents/Home");
        fakeInstallation(home, "JAVA_VERSION=\"21.0.1\"\n", true);
        assertEquals(home, JavaRuntime.detect(dir.resolve("temurin-21.jdk")).orElseThrow().home());
    }

    @Test
    void ordinaryDirectoriesAreNotRuntimes() throws IOException {
        assertEquals(Optional.empty(), JavaRuntime.detect(dir));
        assertEquals(Optional.empty(), JavaRuntime.detect(dir.resolve("missing")));
        Files.createDirectories(dir.resolve("empty/bin"));
        assertEquals(Optional.empty(), JavaRuntime.detect(dir.resolve("empty")));
    }

    /** A directory that looks like a Java home; the executables are empty files, never run. */
    static void fakeInstallation(Path home, String releaseFile, boolean withCompiler) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin").resolve(windows ? "java.exe" : "java"));
        if (withCompiler) {
            Files.createFile(home.resolve("bin").resolve(windows ? "javac.exe" : "javac"));
        }
        Files.writeString(home.resolve("release"), releaseFile);
    }
}
