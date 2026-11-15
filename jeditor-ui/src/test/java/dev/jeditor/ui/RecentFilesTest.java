package dev.jeditor.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecentFilesTest {

    @TempDir
    Path dir;

    private Path store;

    @BeforeEach
    void store() {
        store = dir.resolve("config/recent-files.txt");
    }

    @Test
    void startsEmptyAndWritesNothing() {
        assertEquals(List.of(), new RecentFiles(store, null).list());
        assertFalse(Files.exists(store));
    }

    @Test
    void newestFirst() throws IOException {
        RecentFiles recent = new RecentFiles(store, null);
        Path a = file("A.java"), b = file("B.java"), c = file("C.java");
        recent.touch(a);
        recent.touch(b);
        recent.touch(c);
        assertEquals(List.of(c, b, a), recent.list());
    }

    @Test
    void touchingAgainMovesToFrontWithoutDuplicating() throws IOException {
        RecentFiles recent = new RecentFiles(store, null);
        Path a = file("A.java"), b = file("B.java"), c = file("C.java");
        recent.touch(a);
        recent.touch(b);
        recent.touch(c);
        recent.touch(a);
        assertEquals(List.of(a, c, b), recent.list());
        recent.touch(a);
        assertEquals(List.of(a, c, b), recent.list());
    }

    @Test
    void keepsOnlyTheTenMostRecent() throws IOException {
        RecentFiles recent = new RecentFiles(store, null);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            files.add(file("F" + i + ".java"));
            recent.touch(files.get(i));
        }
        List<Path> expected = new ArrayList<>(files.subList(3, 13));
        java.util.Collections.reverse(expected);

        assertEquals(10, RecentFiles.LIMIT);
        assertEquals(expected, recent.list());
        assertEquals(expected, new RecentFiles(store, null).list(), "the dropped entries are gone from disk too");
    }

    @Test
    void survivesRestart() throws IOException {
        Path a = file("A.java"), b = file("sub dir/B.java");
        RecentFiles recent = new RecentFiles(store, null);
        recent.touch(a);
        recent.touch(b);

        assertEquals(List.of(b, a), new RecentFiles(store, null).list());
    }

    @Test
    void equivalentPathsAreTheSameEntry() throws IOException {
        Path a = file("A.java");
        RecentFiles recent = new RecentFiles(store, null);
        recent.touch(a);
        recent.touch(file("B.java"));
        recent.touch(dir.resolve("x/../A.java"));
        assertEquals(2, recent.list().size());
        assertEquals(a, recent.list().get(0));
    }

    @Test
    void deletedFilesAreNotListedButComeBackIfRestored() throws IOException {
        Path a = file("A.java"), b = file("B.java");
        RecentFiles recent = new RecentFiles(store, null);
        recent.touch(a);
        recent.touch(b);

        Files.delete(b);
        assertEquals(List.of(a), recent.list());
        file("B.java");
        assertEquals(List.of(b, a), recent.list());
    }

    @Test
    void damagedStoreLosesOnlyTheBadLines() throws IOException {
        Path a = file("A.java"), b = file("B.java");
        Files.createDirectories(store.getParent());
        Files.writeString(store, a + "\n\n   \nbad\u0000path\n" + b + "\n" + a + "\n");

        assertEquals(List.of(a, b), new RecentFiles(store, null).list());
    }

    // ---- per-folder list, stored inside the folder --------------------------------------------

    @Test
    void folderListLivesInsideTheFolderAndSurvivesRestart() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("project"));
        Path main = file("project/src/app/Main.java"), pom = file("project/pom.xml");

        RecentFiles recent = RecentFiles.forFolder(folder);
        recent.touch(pom);
        recent.touch(main);

        Path stored = folder.resolve(".jeditor/recent-files.txt");
        assertEquals(List.of("src/app/Main.java", "pom.xml"), Files.readAllLines(stored),
                "paths inside the folder are stored relative to it");
        assertEquals(List.of(main, pom), RecentFiles.forFolder(folder).list(), "a new editor session sees the same list");
    }

    @Test
    void folderListStillWorksAfterTheFolderIsMoved() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("before"));
        RecentFiles recent = RecentFiles.forFolder(folder);
        recent.touch(file("before/src/A.java"));
        recent.touch(file("before/B.java"));

        Path moved = Files.move(folder, dir.resolve("after"));

        assertEquals(List.of(moved.resolve("B.java"), moved.resolve("src/A.java")), RecentFiles.forFolder(moved).list());
    }

    @Test
    void filesOutsideTheFolderAreKeptWithTheirFullPath() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("project"));
        Path outside = file("elsewhere/Notes.java"), inside = file("project/In.java");
        RecentFiles recent = RecentFiles.forFolder(folder);
        recent.touch(outside);
        recent.touch(inside);

        assertEquals(List.of("In.java", outside.toString()), Files.readAllLines(folder.resolve(".jeditor/recent-files.txt")));
        assertEquals(List.of(inside, outside), RecentFiles.forFolder(folder).list());
    }

    @Test
    void eachFolderHasItsOwnList() throws IOException {
        Path one = Files.createDirectories(dir.resolve("one")), two = Files.createDirectories(dir.resolve("two"));
        RecentFiles.forFolder(one).touch(file("one/A.java"));
        RecentFiles.forFolder(two).touch(file("two/B.java"));

        assertEquals(List.of(one.resolve("A.java")), RecentFiles.forFolder(one).list());
        assertEquals(List.of(two.resolve("B.java")), RecentFiles.forFolder(two).list());
    }

    @Test
    void locationIsRelativeToTheOpenFolder() {
        Path folder = dir.resolve("project");
        assertEquals(Path.of("src", "app").toString(),
                RecentFilesDialog.locationOf(folder.resolve("src/app/Main.java"), folder));
        assertEquals(".", RecentFilesDialog.locationOf(folder.resolve("pom.xml"), folder));
        assertEquals(dir.resolve("elsewhere").toString(),
                RecentFilesDialog.locationOf(dir.resolve("elsewhere/X.java"), folder));
        assertEquals(dir.toString(), RecentFilesDialog.locationOf(dir.resolve("X.java"), null));
    }

    private Path file(String name) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        return file;
    }
}
