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

    // ---- remembering the folder a file belongs to ---------------------------------------------

    @Test
    void remembersTheFolderEachFileWasUsedInAcrossRestarts() throws IOException {
        Path one = Files.createDirectories(dir.resolve("one")), two = Files.createDirectories(dir.resolve("two"));
        Path a = file("one/src/A.java"), b = file("two/B.java"), loose = file("loose/C.java");
        RecentFiles recent = new RecentFiles(store, null);
        recent.touch(a, one);
        recent.touch(b, two);
        recent.touch(loose, null);

        RecentFiles reopened = new RecentFiles(store, null);
        assertEquals(List.of(loose, b, a), reopened.list());
        assertEquals(one, reopened.folderOf(a));
        assertEquals(two, reopened.folderOf(b));
        assertEquals(dir.resolve("loose"), reopened.folderOf(loose), "no folder recorded: the file's own folder");
    }

    @Test
    void theLatestFolderWinsWhenAFileIsUsedFromAnother() throws IOException {
        Path outer = Files.createDirectories(dir.resolve("outer")), inner = Files.createDirectories(dir.resolve("outer/inner"));
        Path a = file("outer/inner/A.java");
        RecentFiles recent = new RecentFiles(store, null);
        recent.touch(a, outer);
        recent.touch(a, inner);
        assertEquals(inner, new RecentFiles(store, null).folderOf(a));
        assertEquals(1, recent.list().size());
    }

    @Test
    void aRecordedFolderThatIsGoneOrNoLongerContainsTheFileIsNotTrusted() throws IOException {
        Path a = file("project/src/A.java");
        RecentFiles recent = new RecentFiles(store, null);
        recent.touch(a, dir.resolve("deleted-project"));
        assertEquals(dir.resolve("project/src"), recent.folderOf(a));
    }

    @Test
    void foldersOfEntriesThatDropOffTheListAreForgotten() throws IOException {
        RecentFiles recent = new RecentFiles(store, null);
        Path first = file("p/First.java");
        recent.touch(first, dir.resolve("p"));
        for (int i = 0; i < RecentFiles.LIMIT; i++) {
            recent.touch(file("p/F" + i + ".java"), dir.resolve("p"));
        }
        assertFalse(Files.readString(store).contains("First.java"));
        assertEquals(RecentFiles.LIMIT, Files.readAllLines(store).size());
    }

    @Test
    void guessesTheProjectFolderFromMarkersOnDisk() throws IOException {
        Path inGit = file("repo/src/deep/A.java");
        Files.createDirectories(dir.resolve("repo/.git"));
        assertEquals(dir.resolve("repo"), RecentFiles.guessProjectFolder(inGit));

        Path inMaven = file("build/app/src/main/java/p/B.java");
        file("build/pom.xml");
        file("build/app/pom.xml");
        assertEquals(dir.resolve("build"), RecentFiles.guessProjectFolder(inMaven), "the outermost pom, not the module");

        Path usedBefore = file("repo/tools/sub/C.java");
        Files.createDirectories(dir.resolve("repo/tools/.jeditor"));
        assertEquals(dir.resolve("repo/tools"), RecentFiles.guessProjectFolder(usedBefore),
                "a folder the editor was used in beats the repository root");

        Path plain = file("plain/D.java");
        assertEquals(dir.resolve("plain"), RecentFiles.guessProjectFolder(plain));
    }

    @Test
    void mergedListPutsTheFolderFirstWithoutDuplicatesUpToTheLimit() {
        Path a = Path.of("/a"), b = Path.of("/b"), c = Path.of("/c"), d = Path.of("/d");
        assertEquals(List.of(a, b, c, d), RecentFiles.merged(List.of(a, b), List.of(c, b, d, a), 10));
        assertEquals(List.of(a, b, c), RecentFiles.merged(List.of(a, b), List.of(c, d), 3));
        assertEquals(List.of(a), RecentFiles.merged(List.of(a, b), List.of(c), 1));
        assertEquals(List.of(), RecentFiles.merged(List.of(), List.of(), 10));
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
