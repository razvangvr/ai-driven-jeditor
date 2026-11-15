package dev.jeditor.ui;

import dev.jeditor.compiler.JavaSourceAnalyzer;
import dev.jeditor.compiler.ProjectLayout;
import dev.jeditor.compiler.run.JavaLauncher;
import dev.jeditor.compiler.run.JavaRuntime;
import dev.jeditor.compiler.run.ProgramBuilder;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static dev.jeditor.ui.ConsolePanel.Style.ERROR;
import static dev.jeditor.ui.ConsolePanel.Style.INFO;
import static dev.jeditor.ui.ConsolePanel.Style.OUTPUT;

/**
 * Runs the main class of a Java file: compiles it into a scratch directory, starts it on the
 * chosen runtime as a separate process, and streams its output to the console. One program at a time.
 */
final class RunController {

    private final ConsolePanel console;
    private final JavaSourceAnalyzer analyzer;
    private final Runnable onStateChange;
    private final ProgramBuilder builder = new ProgramBuilder();

    private volatile boolean busy;
    private volatile Process process;
    private Path outputDir;

    /** @param onStateChange called on the event thread whenever {@link #isBusy()} changes */
    RunController(ConsolePanel console, JavaSourceAnalyzer analyzer, Runnable onStateChange) {
        this.console = console;
        this.analyzer = analyzer;
        this.onStateChange = onStateChange;
    }

    /** True from the moment a run is requested until the program has exited. */
    boolean isBusy() {
        return busy;
    }

    /** Builds and runs the (saved) file. Must be called on the event thread; returns immediately. */
    void run(Path file, Path workingDirectory, JavaRuntime runtime) {
        if (busy) {
            return;
        }
        busy = true;
        console.clear();
        onStateChange.run();
        Thread thread = new Thread(() -> {
            try {
                buildAndRun(file, workingDirectory, runtime);
            } catch (IOException | UncheckedIOException e) {
                console.append(ERROR, "Cannot run " + file.getFileName() + ": " + e.getMessage() + "\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                process = null;
                busy = false;
                console.setProgramInput(null);
                SwingUtilities.invokeLater(onStateChange);
            }
        }, "jeditor-run");
        thread.setDaemon(true);
        thread.start();
    }

    /** Kills the running program, if any, together with any processes it started. */
    void stop() {
        Process running = process;
        if (running != null) {
            running.descendants().forEach(ProcessHandle::destroyForcibly);
            running.destroyForcibly();
        }
    }

    private void buildAndRun(Path file, Path workingDirectory, JavaRuntime runtime) throws IOException, InterruptedException {
        String source = Files.readString(file);
        List<String> mainClasses = analyzer.analyze(file, source).mainClasses();
        if (mainClasses.isEmpty()) {
            console.append(ERROR, "Nothing to run: " + file.getFileName()
                    + " has no 'public static void main(String[] args)' method.\n");
            return;
        }
        String mainClass = mainClasses.get(0);
        ProjectLayout layout = ProjectLayout.detect(file, source);

        Path classes = freshOutputDir();
        ProgramBuilder.BuildResult build = builder.build(file, layout, classes, runtime.featureVersion());
        if (!build.success()) {
            build.messages().forEach(message -> console.append(ERROR, message + "\n"));
            console.append(INFO, "\nBuild failed; nothing was run.\n");
            return;
        }

        List<Path> classpath = new ArrayList<>();
        classpath.add(classes);
        classpath.addAll(layout.classpath());
        console.append(INFO, runtime.javaExecutable() + " " + mainClass + "\n");

        Process started = JavaLauncher.start(runtime, classpath, mainClass, List.of(), workingDirectory);
        process = started;
        console.setProgramInput(started.getOutputStream());
        try (Reader reader = new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                console.append(OUTPUT, new String(buffer, 0, read));
            }
        } catch (IOException e) {
            // stream closed because the process was killed; the exit code below tells the story
        }
        console.append(INFO, "\nProcess finished with exit code " + started.waitFor() + "\n");
    }

    /** An empty directory for class files, so classes of earlier runs can never leak into this one. */
    private synchronized Path freshOutputDir() throws IOException {
        if (outputDir == null) {
            outputDir = Files.createTempDirectory("jeditor-run");
            Path toDelete = outputDir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(toDelete, true)));
        }
        deleteRecursively(outputDir, false);
        return outputDir;
    }

    private static void deleteRecursively(Path dir, boolean includingRoot) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> includingRoot || !path.equals(dir))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                            // best effort: a leftover temp file is harmless
                        }
                    });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
