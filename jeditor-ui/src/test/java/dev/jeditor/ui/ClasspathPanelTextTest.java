package dev.jeditor.ui;

import dev.jeditor.compiler.run.ClasspathInspector.Entry;
import dev.jeditor.compiler.run.ClasspathInspector.Kind;
import dev.jeditor.compiler.run.ClasspathInspector.LibraryFolder;
import dev.jeditor.compiler.run.ClasspathInspector.Report;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The lines the Classpath Inspector shows. */
class ClasspathPanelTextTest {

    private static final Path PROJECT = Path.of("/work/shop");

    @Test
    void entryLineIsJustPositionAndName() {
        Entry jar = new Entry(PROJECT.resolve("lib/commons-lang3-3.14.0.jar"), Kind.JAR, "library folder lib",
                "org.apache.commons:commons-lang3", "3.14.0", 657_952, 404, List.of("org.apache.commons.lang3"));
        assertEquals("2. commons-lang3-3.14.0.jar", ClasspathPanel.entryLine(2, jar));
        Entry own = new Entry(null, Kind.COMPILED_ON_RUN, "compiled from the sources each time the program is run", null, null, 0, 0, List.of());
        assertEquals("1. this project's classes", ClasspathPanel.entryLine(1, own));
    }

    @Test
    void jarDetailsListIdentityVersionContentsLocationAndReason() {
        Entry jar = new Entry(PROJECT.resolve("lib/commons-lang3-3.14.0.jar"), Kind.JAR, "library folder lib",
                "org.apache.commons:commons-lang3", "3.14.0", 657_952, 404, List.of("org.apache.commons.lang3"));
        assertEquals(List.of(
                "Library: org.apache.commons:commons-lang3",
                "Version: 3.14.0",
                "Contains: 404 classes, 643 KB",
                "Location: " + Path.of("lib", "commons-lang3-3.14.0.jar"),
                "Here because: library folder lib"), ClasspathPanel.entryDetails(jar, PROJECT));
    }

    @Test
    void detailsLeaveOutWhatIsNotKnown() {
        Entry unknown = new Entry(PROJECT.resolve("lib/local.jar"), Kind.JAR, "library folder lib", null, null, 512, 1, List.of("a"));
        assertEquals(List.of("Contains: 1 class, 512 B", "Location: " + Path.of("lib", "local.jar"), "Here because: library folder lib"),
                ClasspathPanel.entryDetails(unknown, PROJECT));

        Entry folder = new Entry(PROJECT.resolve("target/classes"), Kind.CLASSES_FOLDER, "Maven build output of module shop", null, null, 0, 12, List.of());
        assertEquals(List.of("Contains: 12 classes", "Location: " + Path.of("target", "classes"), "Here because: Maven build output of module shop"),
                ClasspathPanel.entryDetails(folder, PROJECT));

        Entry own = new Entry(null, Kind.COMPILED_ON_RUN, "x", null, null, 0, 0, List.of());
        assertEquals(List.of("Compiled from the sources on each Run"), ClasspathPanel.entryDetails(own, PROJECT));
    }

    @Test
    void programLine() {
        Path file = PROJECT.resolve("src/app/Main.java");
        assertEquals("Runs app.Main  (Main.java)", ClasspathPanel.programLine(report(file, List.of("app.Main"))));
        assertEquals("Main.java — no main method to run", ClasspathPanel.programLine(report(file, List.of())));
        assertEquals("Libraries of the opened folder", ClasspathPanel.programLine(report(null, List.of())));
    }

    @Test
    void libraryFolderLine() {
        Path lib = PROJECT.resolve("lib");
        assertEquals("lib — not created yet (Add JAR…)", ClasspathPanel.libraryFolderLine(new LibraryFolder(lib, false, 0, true), PROJECT));
        assertEquals("lib — no JARs yet; new JARs go here", ClasspathPanel.libraryFolderLine(new LibraryFolder(lib, true, 0, true), PROJECT));
        assertEquals("lib — 1 JAR; new JARs go here", ClasspathPanel.libraryFolderLine(new LibraryFolder(lib, true, 1, true), PROJECT));
        assertEquals("libs — 4 JARs", ClasspathPanel.libraryFolderLine(new LibraryFolder(PROJECT.resolve("libs"), true, 4, false), PROJECT));
    }

    @Test
    void pathsInsideTheProjectAreShownRelative() {
        assertEquals(Path.of("src", "main").toString(), ClasspathPanel.shorten(PROJECT.resolve("src/main"), PROJECT));
        assertEquals(Path.of("/elsewhere/x.jar").toString(), ClasspathPanel.shorten(Path.of("/elsewhere/x.jar"), PROJECT));
        assertEquals(PROJECT.toString(), ClasspathPanel.shorten(PROJECT, PROJECT));
        assertEquals(Path.of("/a/b").toString(), ClasspathPanel.shorten(Path.of("/a/b"), null));
    }

    @Test
    void sizes() {
        assertEquals("0 B", ClasspathPanel.size(0));
        assertEquals("1023 B", ClasspathPanel.size(1023));
        assertEquals("1 KB", ClasspathPanel.size(1024));
        assertEquals("1.0 MB", ClasspathPanel.size(1024 * 1024));
    }

    private static Report report(Path file, List<String> mainClasses) {
        return new Report(file, mainClasses, List.of(), List.of(), List.of(), List.of());
    }
}
