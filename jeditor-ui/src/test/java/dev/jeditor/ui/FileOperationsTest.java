package dev.jeditor.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileOperationsTest {

    @TempDir
    Path dir;

    @Test
    void createsFileWithContent() throws IOException {
        Path file = FileOperations.createFile(dir, " notes.txt ", "hello");
        assertEquals(dir.resolve("notes.txt"), file);
        assertEquals("hello", Files.readString(file));
    }

    @Test
    void createsMissingFoldersForNestedNames() throws IOException {
        Path file = FileOperations.createFile(dir, "docs/api/index.md", "");
        assertEquals(dir.resolve("docs/api/index.md"), file);
        assertTrue(Files.isRegularFile(file));

        Path folder = FileOperations.createFolder(dir, "a/b/c");
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void neverOverwrites() throws IOException {
        Files.writeString(dir.resolve("keep.txt"), "precious");
        Files.createDirectory(dir.resolve("taken"));

        assertThrows(IllegalArgumentException.class, () -> FileOperations.createFile(dir, "keep.txt", "new"));
        assertThrows(IllegalArgumentException.class, () -> FileOperations.createFile(dir, "taken", "new"));
        assertThrows(IllegalArgumentException.class, () -> FileOperations.createFolder(dir, "taken"));
        assertEquals("precious", Files.readString(dir.resolve("keep.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "..", "../escape.txt", "a/../../b", "/abs", "a//b", "bad:name", "what?", "tab\tname", "trailing/"})
    void rejectsNamesThatAreInvalidOrEscapeTheFolder(String name) {
        assertThrows(IllegalArgumentException.class, () -> FileOperations.createFile(dir, name, ""));
        assertThrows(IllegalArgumentException.class, () -> FileOperations.createFolder(dir, name));
    }

    @Test
    void renamesFileAndFolderInPlace() throws IOException {
        Path file = FileOperations.createFile(dir, "pkg/Old.java", "class Old {}");
        Path renamed = FileOperations.rename(file, "New.java");
        assertEquals(dir.resolve("pkg/New.java"), renamed);
        assertFalse(Files.exists(file));
        assertEquals("class Old {}", Files.readString(renamed));

        Path folder = FileOperations.rename(dir.resolve("pkg"), "moved");
        assertTrue(Files.isRegularFile(folder.resolve("New.java")), "contents travel with the folder");
    }

    @Test
    void renameRefusesTakenNamesAndPaths() throws IOException {
        Path a = FileOperations.createFile(dir, "a.txt", "A");
        FileOperations.createFile(dir, "b.txt", "B");

        assertThrows(IllegalArgumentException.class, () -> FileOperations.rename(a, "b.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileOperations.rename(a, "sub/a.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileOperations.rename(a, ""));
        assertEquals("B", Files.readString(dir.resolve("b.txt")));
        assertEquals(a, FileOperations.rename(a, "a.txt"), "same name is a no-op");
    }

    // ---- moving and copying (drag and drop) ---------------------------------------------------

    @Test
    void movesAFileIntoAFolder() throws IOException {
        Path file = FileOperations.createFile(dir, "Loose.java", "class Loose {}");
        Path folder = FileOperations.createFolder(dir, "pkg");

        Path moved = FileOperations.moveInto(file, folder);

        assertEquals(folder.resolve("Loose.java"), moved);
        assertFalse(Files.exists(file));
        assertEquals("class Loose {}", Files.readString(moved));
    }

    @Test
    void movesAFolderWithItsContents() throws IOException {
        FileOperations.createFile(dir, "a/deep/File.txt", "x");
        Path target = FileOperations.createFolder(dir, "b");

        Path moved = FileOperations.moveInto(dir.resolve("a"), target);

        assertEquals(target.resolve("a"), moved);
        assertTrue(Files.isRegularFile(target.resolve("a/deep/File.txt")));
        assertFalse(Files.exists(dir.resolve("a")));
    }

    @Test
    void movingToWhereItAlreadyIsDoesNothing() throws IOException {
        Path file = FileOperations.createFile(dir, "pkg/Same.java", "x");
        assertEquals(file, FileOperations.moveDestination(file, dir.resolve("pkg")));
        assertEquals(file, FileOperations.moveInto(file, dir.resolve("pkg")));
        assertEquals("x", Files.readString(file));
    }

    @Test
    void refusesMovesThatWouldOverwriteOrMakeNoSense() throws IOException {
        Path file = FileOperations.createFile(dir, "A.java", "mine");
        FileOperations.createFile(dir, "taken/A.java", "theirs");
        Path folder = FileOperations.createFolder(dir, "outer/inner");

        assertThrows(IllegalArgumentException.class, () -> FileOperations.moveInto(file, dir.resolve("taken")));
        assertThrows(IllegalArgumentException.class, () -> FileOperations.moveInto(dir.resolve("outer"), folder),
                "a folder into its own subfolder");
        assertThrows(IllegalArgumentException.class, () -> FileOperations.moveInto(dir.resolve("outer"), dir.resolve("outer")),
                "a folder into itself");
        assertThrows(IllegalArgumentException.class, () -> FileOperations.moveInto(file, dir.resolve("taken/A.java")),
                "onto a file");
        assertEquals("mine", Files.readString(file));
        assertEquals("theirs", Files.readString(dir.resolve("taken/A.java")));
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void siblingFoldersWithACommonNamePrefixAreNotMistakenForNesting() throws IOException {
        Path src = FileOperations.createFolder(dir, "src");
        Path srcOld = FileOperations.createFolder(dir, "src-old");
        assertEquals(srcOld.resolve("src"), FileOperations.moveInto(src, srcOld));
    }

    @Test
    void copiesFilesAndFoldersWithoutTouchingTheOriginal() throws IOException {
        Path file = FileOperations.createFile(dir, "outside/Note.txt", "note");
        FileOperations.createFile(dir, "outside/tree/a/b.txt", "b");
        Path target = FileOperations.createFolder(dir, "project");

        assertEquals(target.resolve("Note.txt"), FileOperations.copyInto(file, target));
        assertEquals(target.resolve("tree"), FileOperations.copyInto(dir.resolve("outside/tree"), target));

        assertEquals("note", Files.readString(target.resolve("Note.txt")));
        assertEquals("b", Files.readString(target.resolve("tree/a/b.txt")));
        assertTrue(Files.exists(file), "the original stays");
        assertThrows(IllegalArgumentException.class, () -> FileOperations.copyInto(file, target), "never overwrites");
        assertThrows(IllegalArgumentException.class, () -> FileOperations.copyInto(dir.resolve("outside"), dir.resolve("outside/tree")));
    }

    @Test
    void deletesFolderWithEverythingInIt() throws IOException {
        FileOperations.createFile(dir, "top/mid/leaf.txt", "x");
        FileOperations.createFile(dir, "top/other.txt", "y");
        Path survivor = FileOperations.createFile(dir, "survivor.txt", "z");

        FileOperations.deletePermanently(dir.resolve("top"));

        assertFalse(Files.exists(dir.resolve("top")));
        assertTrue(Files.exists(survivor));
        FileOperations.deletePermanently(dir.resolve("top")); // already gone: fine
    }

    @Test
    void openFilesFollowARenamedFileOrFolder() {
        Path oldFolder = dir.resolve("src/old");
        Path newFolder = dir.resolve("src/new");
        assertEquals(newFolder.resolve("a/B.java"), FileOperations.relocated(oldFolder.resolve("a/B.java"), oldFolder, newFolder));
        assertEquals(newFolder, FileOperations.relocated(oldFolder, oldFolder, newFolder));
        Path unrelated = dir.resolve("src/older/C.java");
        assertEquals(unrelated, FileOperations.relocated(unrelated, oldFolder, newFolder), "prefix match is by path segment, not by text");
    }
}
