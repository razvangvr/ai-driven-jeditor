package dev.jeditor.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Creating, renaming and deleting files and folders for the file viewer. Problems the user can
 * fix (bad name, name already taken) are reported as {@link IllegalArgumentException} with a
 * message fit to show; real I/O failures stay {@link IOException}s.
 */
final class FileOperations {

    private FileOperations() {
    }

    /**
     * Creates a file in {@code directory}. {@code name} may contain {@code /} to create folders on
     * the way ({@code docs/notes.txt}). Never overwrites.
     */
    static Path createFile(Path directory, String name, String content) throws IOException {
        Path file = resolveNew(directory, name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return file;
    }

    /** Creates a folder in {@code directory}; {@code name} may contain {@code /} for nested folders. */
    static Path createFolder(Path directory, String name) throws IOException {
        Path folder = resolveNew(directory, name);
        Files.createDirectories(folder);
        return folder;
    }

    /** Renames a file or folder within its parent folder and returns its new path. */
    static Path rename(Path target, String newName) throws IOException {
        List<String> segments = segments(newName);
        if (segments.size() != 1) {
            throw new IllegalArgumentException("A new name cannot contain '/'.");
        }
        Path renamed = target.resolveSibling(segments.get(0));
        if (renamed.equals(target)) {
            return target;
        }
        // Allow a pure change of letter case on case-insensitive file systems, where the "other" file is this one.
        if (Files.exists(renamed) && !Files.isSameFile(renamed, target)) {
            throw new IllegalArgumentException("'" + segments.get(0) + "' already exists.");
        }
        return Files.move(target, renamed);
    }

    /** True if {@link #delete} moves things to the system trash rather than erasing them. */
    static boolean deletesToTrash() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
    }

    /** Deletes a file, or a folder with everything in it: to the trash where the system has one, permanently otherwise. */
    static void delete(Path target) throws IOException {
        if (deletesToTrash() && Desktop.getDesktop().moveToTrash(target.toFile())) {
            return;
        }
        deletePermanently(target);
    }

    static void deletePermanently(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { // children before parents
                Files.delete(path);
            }
        }
    }

    /** Where an open file ends up when {@code oldPath} (the file itself or a folder above it) becomes {@code newPath}. */
    static Path relocated(Path file, Path oldPath, Path newPath) {
        return file.startsWith(oldPath) ? newPath.resolve(oldPath.relativize(file)) : file;
    }

    private static Path resolveNew(Path directory, String name) throws FileAlreadyExistsException {
        Path target = directory;
        for (String segment : segments(name)) {
            target = target.resolve(segment);
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException("'" + name.strip() + "' already exists.");
        }
        return target;
    }

    /** Splits a typed name into path segments, rejecting anything that could escape the folder or is not a legal file name. */
    private static List<String> segments(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Enter a name.");
        }
        List<String> segments = List.of(trimmed.split("[/\\\\]", -1));
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.chars().anyMatch(c -> c < 32 || "<>:\"|?*".indexOf(c) >= 0)) {
                throw new IllegalArgumentException("'" + trimmed + "' is not a valid name.");
            }
        }
        return segments;
    }
}
