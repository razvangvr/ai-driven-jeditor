package dev.jeditor.compiler.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRegistryTest {

    @TempDir
    Path dir;

    private Path settings;
    private Path java8;
    private Path java17;

    @BeforeEach
    void installations() throws IOException {
        settings = dir.resolve("config/runtimes.properties");
        java8 = dir.resolve("jre8");
        java17 = dir.resolve("jdk17");
        JavaRuntimeTest.fakeInstallation(java8, "JAVA_VERSION=\"1.8.0_392\"\n", false);
        JavaRuntimeTest.fakeInstallation(java17, "JAVA_VERSION=\"17.0.9\"\n", true);
    }

    @Test
    void startsEmpty() {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        assertEquals(List.of(), registry.runtimes());
        assertEquals(Optional.empty(), registry.selected());
        assertFalse(Files.exists(settings), "nothing is written until something is added");
    }

    @Test
    void firstAddedRuntimeIsSelected() {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        JavaRuntime jre = registry.add(java8);
        JavaRuntime jdk = registry.add(java17);

        assertEquals(List.of(jre, jdk), registry.runtimes());
        assertEquals(Optional.of(jre), registry.selected());
    }

    @Test
    void addingTwiceDoesNotDuplicate() {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        JavaRuntime first = registry.add(java17);
        assertSame(first, registry.add(java17.resolve("bin")));
        assertEquals(1, registry.runtimes().size());
    }

    @Test
    void rejectsDirectoriesThatAreNotJava() {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> registry.add(dir));
        assertTrue(e.getMessage().contains("Not a Java installation"), e.getMessage());
        assertEquals(List.of(), registry.runtimes());
    }

    @Test
    void runtimesAndSelectionSurviveRestart() {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        registry.add(java8);
        JavaRuntime jdk = registry.add(java17);
        registry.select(jdk);

        RuntimeRegistry reopened = new RuntimeRegistry(settings);
        assertEquals(registry.runtimes(), reopened.runtimes());
        assertEquals(Optional.of(jdk), reopened.selected());
    }

    @Test
    void removingTheSelectedRuntimeSelectsAnother() {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        JavaRuntime jre = registry.add(java8);
        JavaRuntime jdk = registry.add(java17);

        registry.remove(jre);
        assertEquals(Optional.of(jdk), registry.selected());
        registry.remove(jdk);
        assertEquals(Optional.empty(), registry.selected());
        assertEquals(List.of(), new RuntimeRegistry(settings).runtimes());
    }

    @Test
    void cannotSelectAnUnknownRuntime() {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        JavaRuntime jdk = registry.add(java17);
        registry.remove(jdk);
        assertThrows(IllegalArgumentException.class, () -> registry.select(jdk));
    }

    @Test
    void uninstalledRuntimesAreDroppedOnLoad() throws IOException {
        RuntimeRegistry registry = new RuntimeRegistry(settings);
        JavaRuntime jre = registry.add(java8);
        JavaRuntime jdk = registry.add(java17);
        registry.select(jdk);

        Files.delete(java17.resolve("bin/java"));

        RuntimeRegistry reopened = new RuntimeRegistry(settings);
        assertEquals(List.of(jre), reopened.runtimes());
        assertEquals(Optional.of(jre), reopened.selected(), "falls back when the selected runtime is gone");
    }

    @Test
    void corruptSettingsFileIsTreatedAsEmpty() throws IOException {
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "runtime.0=" + dir.resolve("nowhere") + "\nselected=\\u00zz\n");
        assertEquals(List.of(), new RuntimeRegistry(settings).runtimes());
    }
}
