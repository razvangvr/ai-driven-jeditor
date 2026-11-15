package dev.jeditor.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The most recently used files, newest first, capped at {@link #LIMIT} and remembered between
 * editor restarts in a plain text file (one path per line).
 *
 * <p>There are two such lists. Each opened folder keeps its own inside the folder itself, in
 * {@code .jeditor/recent-files.txt}, with paths relative to the folder so the list stays valid
 * when the folder is moved. A second, per-user list spans all folders and remembers which folder
 * each file was used in; it is what lets a recent file bring its folder back with it.
 */
final class RecentFiles {

    static final int LIMIT = 10;

    /** Folder, inside an opened folder, where the editor keeps what it remembers about that folder. */
    static final String SETTINGS_DIRECTORY = ".jeditor";

    private final Path store;
    private final Path base;
    private final List<Path> files = new ArrayList<>();
    /** For each file, the folder (project) that was open when it was last used; absent if none was. */
    private final Map<Path, Path> folders = new HashMap<>();

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

    /**
     * The list across all folders, kept in {@code ~/.jeditor/recent-files.txt}. It also remembers
     * which folder each file was used in, so that the folder can be reopened along with the file.
     */
    static RecentFiles forCurrentUser() {
        return new RecentFiles(Path.of(System.getProperty("user.home"), SETTINGS_DIRECTORY, "recent-files.txt"), null);
    }

    /** Marks a file as just used: it moves (or is added) to the front, and the oldest entry beyond the limit drops off. */
    void touch(Path file) {
        touch(file, null);
    }

    /**
     * Like {@link #touch(Path)}, also recording the folder (project) the file is being used in.
     *
     * @param folder the folder open in the editor, or null if none
     */
    void touch(Path file, Path folder) {
        Path normalized = file.toAbsolutePath().normalize();
        Path normalizedFolder = folder == null ? null : folder.toAbsolutePath().normalize();
        if (!files.isEmpty() && files.get(0).equals(normalized) && Objects.equals(folders.get(normalized), normalizedFolder)) {
            return; // already the most recent: nothing changes, nothing to write
        }
        files.remove(normalized);
        files.add(0, normalized);
        if (normalizedFolder == null) {
            folders.remove(normalized);
        } else {
            folders.put(normalized, normalizedFolder);
        }
        while (files.size() > LIMIT) {
            folders.remove(files.remove(files.size() - 1));
        }
        save();
    }

    /**
     * The folder to open together with {@code file}: the one it was last used in if that is still
     * there, otherwise a guess from what is on disk (see {@link #guessProjectFolder}).
     */
    Path folderOf(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path recorded = folders.get(normalized);
        if (recorded != null && Files.isDirectory(recorded) && normalized.startsWith(recorded)) {
            return recorded;
        }
        return guessProjectFolder(normalized);
    }

    /**
     * The folder a file most plausibly belongs to: the nearest folder above it that the editor has
     * been used in ({@code .jeditor}), else the nearest version-control root ({@code .git}), else
     * the top of the Maven build it is part of ({@code pom.xml}), else simply the file's own folder.
     */
    static Path guessProjectFolder(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path maven = null;
        for (String marker : new String[] {SETTINGS_DIRECTORY, ".git"}) {
            for (Path dir = normalized.getParent(); dir != null; dir = dir.getParent()) {
                // The home directory has a .jeditor of its own (per-user settings); it is not a project.
                if (!dir.equals(home) && Files.exists(dir.resolve(marker))) {
                    return dir;
                }
            }
        }
        for (Path dir = normalized.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                maven = dir; // keep climbing: the outermost pom is the project, inner ones are its modules
            } else if (maven != null) {
                break;
            }
        }
        return maven != null ? maven : normalized.getParent();
    }

    /** {@code first} followed by the entries of {@code second} not already in it, cut to {@code limit}. */
    static List<Path> merged(List<Path> first, List<Path> second, int limit) {
        List<Path> result = new ArrayList<>(first);
        for (Path path : second) {
            if (!result.contains(path)) {
                result.add(path);
            }
        }
        return List.copyOf(result.subList(0, Math.min(limit, result.size())));
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
        Path folder = folders.get(file);
        return folder == null ? file.toString() : file + "\t" + folder; // "file<TAB>folder"
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
                    String[] columns = line.split("\t", 2);
                    Path stored = Path.of(columns[0].strip());
                    Path path = (base != null && !stored.isAbsolute() ? base.resolve(stored) : stored.toAbsolutePath()).normalize();
                    if (!files.contains(path)) {
                        files.add(path);
                        if (columns.length == 2 && !columns[1].isBlank()) {
                            folders.put(path, Path.of(columns[1].strip()).toAbsolutePath().normalize());
                        }
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
