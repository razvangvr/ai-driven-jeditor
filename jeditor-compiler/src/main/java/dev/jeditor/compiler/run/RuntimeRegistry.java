package dev.jeditor.compiler.run;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * The Java runtimes the user has added, and which one is selected for running programs.
 * Persisted as a small properties file so the list survives restarts.
 */
public final class RuntimeRegistry {

    private static final System.Logger LOG = System.getLogger(RuntimeRegistry.class.getName());

    private final Path file;
    private final List<JavaRuntime> runtimes = new ArrayList<>();
    private JavaRuntime selected;

    /** Opens the registry stored in {@code file}; a missing file means an empty registry. */
    public RuntimeRegistry(Path file) {
        this.file = file;
        load();
    }

    /** The per-user registry in {@code ~/.jeditor/runtimes.properties}. */
    public static RuntimeRegistry forCurrentUser() {
        return new RuntimeRegistry(Path.of(System.getProperty("user.home"), ".jeditor", "runtimes.properties"));
    }

    public List<JavaRuntime> runtimes() {
        return List.copyOf(runtimes);
    }

    /** The runtime programs are run on; empty only when no runtime has been added. */
    public Optional<JavaRuntime> selected() {
        return Optional.ofNullable(selected);
    }

    /**
     * Adds the Java installation at {@code location} and returns it. Adding an installation that
     * is already registered just returns the existing entry. The first runtime added becomes the
     * selected one.
     *
     * @throws IllegalArgumentException if {@code location} is not a Java installation
     */
    public JavaRuntime add(Path location) {
        JavaRuntime runtime = JavaRuntime.detect(location).orElseThrow(() -> new IllegalArgumentException(
                "Not a Java installation (no bin/java found): " + location));
        for (JavaRuntime existing : runtimes) {
            if (existing.home().equals(runtime.home())) {
                return existing;
            }
        }
        runtimes.add(runtime);
        if (selected == null) {
            selected = runtime;
        }
        save();
        return runtime;
    }

    /** Removes a runtime; if it was the selected one, the first remaining runtime gets selected. */
    public void remove(JavaRuntime runtime) {
        if (runtimes.remove(runtime)) {
            if (runtime.equals(selected)) {
                selected = runtimes.isEmpty() ? null : runtimes.get(0);
            }
            save();
        }
    }

    /** @throws IllegalArgumentException if the runtime has not been added */
    public void select(JavaRuntime runtime) {
        if (!runtimes.contains(runtime)) {
            throw new IllegalArgumentException("Unknown runtime: " + runtime.home());
        }
        if (!runtime.equals(selected)) {
            selected = runtime;
            save();
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot read " + file + "; starting with no Java runtimes", e);
            return;
        }
        String selectedHome = properties.getProperty("selected", "");
        for (int i = 0; properties.containsKey("runtime." + i); i++) {
            // Re-detect on every start: installations get upgraded or uninstalled behind our back.
            Optional<JavaRuntime> runtime = JavaRuntime.detect(Path.of(properties.getProperty("runtime." + i)));
            if (runtime.isPresent() && runtimes.stream().noneMatch(r -> r.home().equals(runtime.get().home()))) {
                runtimes.add(runtime.get());
                if (runtime.get().home().toString().equals(selectedHome)) {
                    selected = runtime.get();
                }
            }
        }
        if (selected == null && !runtimes.isEmpty()) {
            selected = runtimes.get(0);
        }
    }

    private void save() {
        Properties properties = new Properties();
        for (int i = 0; i < runtimes.size(); i++) {
            properties.setProperty("runtime." + i, runtimes.get(i).home().toString());
        }
        if (selected != null) {
            properties.setProperty("selected", selected.home().toString());
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "JEditor Java runtimes");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save Java runtimes to " + file, e);
        }
    }
}
