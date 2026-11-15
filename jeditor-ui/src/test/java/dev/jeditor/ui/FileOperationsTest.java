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
