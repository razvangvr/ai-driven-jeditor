package dev.jeditor.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The most recently used files, newest first, capped at {@link #LIMIT} and remembered between
 * editor restarts in a plain text file (one path per line).
 *
 * <p>Each opened folder keeps its own list inside the folder itself, in
 * {@code .jeditor/recent-files.txt}. Files inside the folder are stored relative to it, so the
 * list stays valid when the folder is moved, renamed or copied to another machine.
 */
final class RecentFiles {

    static final int LIMIT = 10;

    /** Folder, inside an opened folder, where the editor keeps what it remembers about that folder. */
    static final String SETTINGS_DIRECTORY = ".jeditor";

    private final Path store;
    private final Path base;
    private final List<Path> files = new ArrayList<>();

    /**
     * Opens the list kept in {@code store}; a missing or unreadable file means an empty list.
     *
     * @param base files under this directory are stored as paths relative to it; null stores every path in full
     */
    RecentFiles(Path store, Path base) {
        this.store = store;
        this.base = base == null ? null : base.toAbsolutePath().normalize();
        load();
    }

    /** The list of an opened folder, kept in {@code <folder>/.jeditor/recent-files.txt}. */
    static RecentFiles forFolder(Path folder) {
        return new RecentFiles(folder.resolve(SETTINGS_DIRECTORY).resolve("recent-files.txt"), folder);
    }

    /** The list used while no folder is open, kept in {@code ~/.jeditor/recent-files.txt}. */
    static RecentFiles forCurrentUser() {
        return new RecentFiles(Path.of(System.getProperty("user.home"), SETTINGS_DIRECTORY, "recent-files.txt"), null);
    }

    /** Marks a file as just used: it moves (or is added) to the front, and the oldest entry beyond the limit drops off. */
    void touch(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!files.isEmpty() && files.get(0).equals(normalized)) {
            return; // already the most recent: nothing changes, nothing to write
        }
        files.remove(normalized);
        files.add(0, normalized);
        while (files.size() > LIMIT) {
            files.remove(files.size() - 1);
        }
        save();
    }

    /** Newest first. Files that have since been deleted or moved are left out. */
    List<Path> list() {
        return files.stream().filter(Files::isRegularFile).toList();
    }

    /** How a file is written to the store: relative to the base with '/' separators when inside it, in full otherwise. */
    private String stored(Path file) {
        if (base != null && file.startsWith(base)) {
            return base.relativize(file).toString().replace('\\', '/');
        }
        return file.toString();
    }

    private void load() {
        if (!Files.isRegularFile(store)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(store, StandardCharsets.UTF_8)) {
                if (line.isBlank() || files.size() >= LIMIT) {
                    continue;
                }
                try {
                    Path stored = Path.of(line.strip());
                    Path path = (base != null && !stored.isAbsolute() ? base.resolve(stored) : stored.toAbsolutePath()).normalize();
                    if (!files.contains(path)) {
                        files.add(path);
                    }
                } catch (InvalidPathException ignored) {
                    // a mangled line loses one entry, not the whole list
                }
            }
        } catch (IOException ignored) {
            // an unreadable list is an empty list
        }
    }

    private void save() {
        try {
            if (store.getParent() != null) {
                Files.createDirectories(store.getParent());
            }
            Files.write(store, files.stream().map(this::stored).toList(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // losing the recent list is not worth interrupting the user for
        }
    }
}
