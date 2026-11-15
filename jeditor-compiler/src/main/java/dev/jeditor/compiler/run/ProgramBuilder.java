package dev.jeditor.compiler.run;

import dev.jeditor.compiler.ProjectLayout;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compiles a source file, together with the project sources it depends on, into class files so
 * that it can be run. Uses the compiler of the JDK the editor itself runs on, and targets the
 * Java release of the runtime the program will be launched with, so a program can be run on an
 * older JRE that has no compiler of its own.
 */
public final class ProgramBuilder {

    /** Oldest release the JDK 21 compiler can still target. */
    static final int OLDEST_TARGET = 8;

    /**
     * @param success  true if class files were produced
     * @param messages compile errors as {@code file:line: error: message}; empty on success
     */
    public record BuildResult(boolean success, List<String> messages) {
        public BuildResult {
            messages = List.copyOf(messages);
        }
    }

    private final JavaCompiler compiler;

    public ProgramBuilder() {
        this(ToolProvider.getSystemJavaCompiler());
    }

    ProgramBuilder(JavaCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * Compiles {@code file} as saved on disk. Classes it refers to are compiled too when their
     * source is on the layout's source path.
     *
     * @param outputDir     where class files go; created if missing
     * @param targetRelease Java release the classes must run on, e.g. 17
     */
    public BuildResult build(Path file, ProjectLayout layout, Path outputDir, int targetRelease) {
        if (compiler == null) {
            return failure("No Java compiler available: start JEditor with a JDK, not a JRE.");
        }
        int compilerRelease = Runtime.version().feature();
        List<String> options = new ArrayList<>(List.of("-proc:none", "-nowarn", "-encoding", "UTF-8"));
        if (targetRelease < OLDEST_TARGET) {
            return failure("Cannot compile for Java " + targetRelease + ": the oldest supported runtime is Java "
                    + OLDEST_TARGET + ".");
        }
        if (targetRelease < compilerRelease) {
            // Restricts both language level and API to what the chosen runtime really has.
            options.addAll(List.of("--release", String.valueOf(targetRelease)));
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        boolean success;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(collector, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            Files.createDirectories(outputDir);
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, layout.sourceRoots());
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, layout.classpath());
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
            success = compiler.getTask(null, files, collector, options, null, files.getJavaFileObjects(file)).call();
        } catch (IOException | RuntimeException e) {
            return failure("Compilation failed: " + e);
        }

        List<String> messages = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                messages.add(format(diagnostic));
            }
        }
        if (!success && messages.isEmpty()) {
            messages.add("Compilation failed.");
        }
        return new BuildResult(success, success ? List.of() : messages);
    }

    private static String format(Diagnostic<? extends JavaFileObject> diagnostic) {
        String location = "";
        if (diagnostic.getSource() != null) {
            String path = diagnostic.getSource().toUri().getPath();
            location = (path == null ? diagnostic.getSource().getName() : Path.of(diagnostic.getSource().toUri()).toString())
                    + (diagnostic.getLineNumber() > 0 ? ":" + diagnostic.getLineNumber() : "") + ": ";
        }
        return location + "error: " + diagnostic.getMessage(Locale.ENGLISH);
    }

    private static BuildResult failure(String message) {
        return new BuildResult(false, List.of(message));
    }
}
