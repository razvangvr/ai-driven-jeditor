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
 * sessions in a plain text file (one path per line).
 */
final class RecentFiles {

    static final int LIMIT = 10;

    private final Path store;
    private final List<Path> files = new ArrayList<>();

    /** Opens the list kept in {@code store}; a missing or unreadable file means an empty list. */
    RecentFiles(Path store) {
        this.store = store;
        load();
    }

    /** The per-user list in {@code ~/.jeditor/recent-files.txt}. */
    static RecentFiles forCurrentUser() {
        return new RecentFiles(Path.of(System.getProperty("user.home"), ".jeditor", "recent-files.txt"));
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
                    Path path = Path.of(line.strip()).toAbsolutePath().normalize();
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
            Files.write(store, files.stream().map(Path::toString).toList(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // losing the recent list is not worth interrupting the user for
        }
    }
}
