package dev.jeditor.ui;

import dev.jeditor.compiler.JavaSourceAnalyzer;
import dev.jeditor.compiler.ProjectLayout;
import dev.jeditor.compiler.run.BuildPlanner;
import dev.jeditor.compiler.run.JavaLauncher;
import dev.jeditor.compiler.run.JavaRuntime;
import dev.jeditor.compiler.run.LaunchRecord;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

import static dev.jeditor.ui.ConsolePanel.Style.ERROR;
import static dev.jeditor.ui.ConsolePanel.Style.INFO;
import static dev.jeditor.ui.ConsolePanel.Style.OUTPUT;

/**
 * Background jobs that report to the console, one at a time:
 * <ul>
 *   <li><b>Run</b>: compiles a file into a scratch directory, starts its main class on the chosen
 *       runtime as a separate process, and streams its output;</li>
 *   <li><b>Build</b>: compiles a file or the whole project into the project's output directory.</li>
 * </ul>
 */
final class RunController {

    private static final System.Logger LOG = System.getLogger(RunController.class.getName());

    private final ConsolePanel console;
    private final JavaSourceAnalyzer analyzer;
    private final Runnable onStateChange;
    private final ProgramBuilder builder = new ProgramBuilder();

    private volatile boolean busy;
    private volatile Process process;
    private Path outputDir;

    private final Consumer<LaunchRecord> onLaunch;

    /**
     * @param onStateChange called on the event thread whenever {@link #isBusy()} changes
     * @param onLaunch      called on the event thread when a program is started, and again with the
     *                      completed record when it ends
     */
    RunController(ConsolePanel console, JavaSourceAnalyzer analyzer, Runnable onStateChange, Consumer<LaunchRecord> onLaunch) {
        this.console = console;
        this.analyzer = analyzer;
        this.onStateChange = onStateChange;
        this.onLaunch = onLaunch;
    }

    /** True from the moment a run or build is requested until it has finished. */
    boolean isBusy() {
        return busy;
    }

    private interface Job {
        void run() throws IOException, InterruptedException;
    }

    /** Builds and runs the (saved) file. Must be called on the event thread; returns immediately. */
    void run(Path file, Path workingDirectory, Path projectFolder, JavaRuntime runtime) {
        start("run " + file.getFileName(), () -> buildAndRun(file, workingDirectory, projectFolder, runtime));
    }

    /**
     * Compiles the (saved) file, and what it uses, into the project's output directory.
     *
     * @param projectFolder the opened folder, or null
     * @param release       the Java release to compile for
     */
    void compileFile(Path file, Path projectFolder, int release) {
        start("compile " + file.getFileName(),
                () -> build(List.of(BuildPlanner.forFile(file, Files.readString(file), projectFolder)), release));
    }

    /** Compiles every source of the opened folder into its output directory (or directories, for Maven modules). */
    void buildProject(Path folder, int release) {
        start("build " + folder.getFileName(), () -> {
            List<BuildPlanner.BuildTarget> targets = BuildPlanner.forProject(folder);
            if (targets.isEmpty()) {
                console.append(ERROR, "Nothing to build: no Java sources found in " + folder + "\n");
                return;
            }
            build(targets, release);
        });
    }

    private void build(List<BuildPlanner.BuildTarget> targets, int release) {
        long started = System.nanoTime();
        for (BuildPlanner.BuildTarget target : targets) {
            int count = target.sources().size();
            console.append(INFO, "Compiling " + target.name() + " (" + count + (count == 1 ? " file" : " files")
                    + ", Java " + release + ") \u2192 " + target.outputDir() + "\n");
            ProgramBuilder.BuildResult result = builder.build(target, release);
            if (!result.success()) {
                result.messages().forEach(message -> console.append(ERROR, message + "\n"));
                console.append(ERROR, "\nBuild failed: " + result.messages().size()
                        + (result.messages().size() == 1 ? " error" : " errors") + " in " + target.name() + ".\n");
                return;
            }
        }
        console.append(INFO, String.format("%nBuild successful in %.1f s.%n", (System.nanoTime() - started) / 1e9));
    }

    private void start(String what, Job job) {
        if (busy) {
            return;
        }
        busy = true;
        console.clear();
        onStateChange.run();
        Thread thread = new Thread(() -> {
            try {
                job.run();
            } catch (IOException | UncheckedIOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot " + what, e);
                console.append(ERROR, "Cannot " + what + ": " + e.getMessage() + "\n");
            } catch (RuntimeException | Error e) {
                // Without this the job would end silently and the Console would just stop.
                LOG.log(System.Logger.Level.ERROR, "Unexpected failure: " + what, e);
                console.append(ERROR, "Unexpected error (" + e + "); details are in the error log.\n");
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

    private void buildAndRun(Path file, Path workingDirectory, Path projectFolder, JavaRuntime runtime)
            throws IOException, InterruptedException {
        String source = Files.readString(file);
        List<String> mainClasses = analyzer.analyze(file, source).mainClasses();
        if (mainClasses.isEmpty()) {
            console.append(ERROR, "Nothing to run: " + file.getFileName()
                    + " has no 'public static void main(String[] args)' method.\n");
            return;
        }
        String mainClass = mainClasses.get(0);
        ProjectLayout layout = ProjectLayout.detect(file, source, projectFolder);

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
        LaunchRecord launch = new LaunchRecord(file, mainClass, runtime, workingDirectory, projectFolder, classes, classpath,
                JavaLauncher.command(runtime, classpath, mainClass, List.of()),
                BuildPlanner.forFile(file, source, projectFolder).outputDir(), null, null);
        // The whole command, not a summary: what is on -cp is exactly what the program can see.
        console.append(INFO, launch.commandLine() + "\n");
        SwingUtilities.invokeLater(() -> onLaunch.accept(launch));

        long startedAt = System.nanoTime();
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
        int exitCode = started.waitFor();
        LaunchRecord finished = launch.finished(exitCode, java.time.Duration.ofNanos(System.nanoTime() - startedAt));
        SwingUtilities.invokeLater(() -> onLaunch.accept(finished));
        console.append(INFO, "\nProcess finished with exit code " + exitCode + "\n");
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
                        } catch (IOException e) {
                            // best effort: a leftover temp file is harmless
                            LOG.log(System.Logger.Level.WARNING, "Cannot delete temporary file " + path, e);
                        }
                    });
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot clean up temporary directory " + dir, e);
        }
    }
}
