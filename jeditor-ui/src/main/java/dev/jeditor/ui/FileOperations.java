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
        Path renamed = renameDestination(target, newName);
        return renamed.equals(target) ? target : Files.move(target, renamed);
    }

    /** Where {@link #rename} would put {@code target}; checks the new name without touching anything. */
    static Path renameDestination(Path target, String newName) throws IOException {
        List<String> segments = segments(newName);
        if (segments.size() != 1) {
            throw new IllegalArgumentException("A new name cannot contain '/'.");
        }
        Path renamed = target.resolveSibling(segments.get(0));
        // Allow a pure change of letter case on case-insensitive file systems, where the "other" file is this one.
        if (!renamed.equals(target) && Files.exists(renamed) && !Files.isSameFile(renamed, target)) {
            throw new IllegalArgumentException("'" + segments.get(0) + "' already exists.");
        }
        return renamed;
    }

    /** Moves a file, or a folder with its contents, into {@code directory}, keeping its name. Returns its new path. */
    static Path moveInto(Path source, Path directory) throws IOException {
        Path destination = moveDestination(source, directory);
        return destination.equals(source) ? source : Files.move(source, destination);
    }

    /**
     * Where {@link #moveInto} would put {@code source}; checks the move without touching anything.
     * Equal to {@code source} when it is already in {@code directory}.
     */
    static Path moveDestination(Path source, Path directory) {
        Path from = source.toAbsolutePath().normalize();
        Path into = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(into)) {
            throw new IllegalArgumentException("'" + into.getFileName() + "' is not a folder.");
        }
        if (into.startsWith(from)) {
            throw new IllegalArgumentException("A folder cannot be moved into itself.");
        }
        Path destination = into.resolve(from.getFileName());
        if (!destination.equals(from) && Files.exists(destination)) {
            throw new IllegalArgumentException("'" + into.getFileName() + "' already contains '" + from.getFileName() + "'.");
        }
        return destination;
    }

    /**
     * Copies a file, or a folder with its contents, into {@code directory}, keeping its name.
     * Never overwrites. Returns the path of the copy.
     */
    static Path copyInto(Path source, Path directory) throws IOException {
        Path from = source.toAbsolutePath().normalize();
        Path into = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(into)) {
            throw new IllegalArgumentException("'" + into.getFileName() + "' is not a folder.");
        }
        if (into.startsWith(from)) {
            throw new IllegalArgumentException("A folder cannot be copied into itself.");
        }
        Path destination = into.resolve(from.getFileName().toString());
        if (Files.exists(destination)) {
            throw new IllegalArgumentException("'" + into.getFileName() + "' already contains '" + from.getFileName() + "'.");
        }
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) { // parents come before their children
                Files.copy(path, destination.resolve(from.relativize(path).toString()));
            }
        }
        return destination;
    }

    /** True if {@link #delete} moves things to the system trash rather than erasing them. */
    static boolean deletesToTrash() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
    }

    /** Deletes a file, or a folder with everything in it: to the trash where the system has one, permanently otherwise. */
    static void delete(Path target) throws IOException {
        try {
            if (deletesToTrash() && Desktop.getDesktop().moveToTrash(target.toFile()) && !Files.exists(target)) {
                return;
            }
        } catch (RuntimeException e) {
            // the system refused to trash it (permissions, unsupported location): fall through
        }
        deletePermanently(target);
        if (Files.exists(target)) {
            throw new IOException("'" + target.getFileName() + "' is still there; it may be in use or protected.");
        }
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
