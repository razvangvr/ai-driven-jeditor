package dev.jeditor.compiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks Java source for compile errors and extracts its structure, using the JDK's own compiler
 * ({@code javac}, through {@code javax.tools} and the Compiler Tree API).
 *
 * <p>The source is analysed from memory, so unsaved editor content can be checked. Nothing is
 * written to disk: the compiler stops after attribution and flow analysis and never generates
 * class files.
 *
 * <p>Instances are thread-safe; each call runs an independent compiler task.
 */
public final class JavaSourceAnalyzer {

    private static final System.Logger LOG = System.getLogger(JavaSourceAnalyzer.class.getName());

    private static final List<String> OPTIONS = List.of(
            "-proc:none",
            // Lint like an IDE would, minus the checks that are mostly noise while editing.
            "-Xlint:all,-serial,-processing,-this-escape,-options,-path",
            // Report syntax, type and flow errors together instead of stopping at the first phase that fails.
            "-XDshould-stop.ifError=FLOW",
            "-Xmaxerrs", "500",
            "-Xmaxwarns", "500");

    private final JavaCompiler compiler;

    /** Uses the compiler of the running JDK. */
    public JavaSourceAnalyzer() {
        this(ToolProvider.getSystemJavaCompiler());
    }

    JavaSourceAnalyzer(JavaCompiler compiler) {
        this.compiler = compiler;
    }

    /** False when running on a JRE without the {@code jdk.compiler} module; analysis then finds nothing. */
    public boolean isAvailable() {
        return compiler != null;
    }

    /** Analyses {@code source} as the content of {@code file}, detecting the project layout from disk. */
    public AnalysisResult analyze(Path file, String source) {
        return analyze(file, source, ProjectLayout.detect(file, source));
    }

    /**
     * Analyses {@code source} as the content of {@code file}.
     *
     * @param file   where the source lives (or will live); used for the file-name/class-name check
     *               and never read
     * @param source the text to analyse
     * @param layout where to find other classes the source refers to
     */
    public AnalysisResult analyze(Path file, String source, ProjectLayout layout) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(layout, "layout");
        if (compiler == null) {
            return AnalysisResult.EMPTY;
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        InMemorySource unit = new InMemorySource(file, source);
        List<CodeBlock> structure = List.of();
        List<String> mainClasses = List.of();

        try (StandardJavaFileManager files = compiler.getStandardFileManager(collector, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            // Set both explicitly: by default javac would use the editor's own classpath.
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, layout.sourceRoots());
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, layout.classpath());

            JavacTask task = (JavacTask) compiler.getTask(null, files, collector, OPTIONS, null, List.of(unit));
            task.setLocale(Locale.ENGLISH);
            try {
                Iterable<? extends CompilationUnitTree> parsed = task.parse();
                // Structure must be read before analyze(), which adds synthetic members to the trees.
                for (CompilationUnitTree tree : parsed) {
                    CodeStructureScanner scanner = new CodeStructureScanner(tree, Trees.instance(task).getSourcePositions());
                    structure = scanner.scan();
                    mainClasses = scanner.mainClasses();
                }
                task.analyze();
            } catch (RuntimeException | IOException e) {
                // A compiler crash on pathological input must not take the editor down;
                // whatever was collected so far is still worth showing.
                LOG.log(System.Logger.Level.WARNING, "The compiler failed while analysing " + file
                        + "; the problems shown for it may be incomplete", e);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not close the compiler's file manager after analysing " + file, e);
        }

        List<CompilationDiagnostic> diagnostics = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
            if (diagnostic.getSource() == unit) { // problems in other files belong to those files
                diagnostics.add(convert(diagnostic, source));
            }
        }
        diagnostics.sort(Comparator.comparingInt(CompilationDiagnostic::startOffset)
                .thenComparing(CompilationDiagnostic::severity));
        return new AnalysisResult(diagnostics, structure, mainClasses);
    }

    // ---- go to declaration -------------------------------------------------------------------

    /** Like {@link #findDeclaration(Path, String, int, ProjectLayout)}, detecting the project layout from disk. */
    public Optional<DeclarationTarget> findDeclaration(Path file, String source, int offset) {
        return findDeclaration(file, source, offset, ProjectLayout.detect(file, source));
    }

    /**
     * Resolves the name at {@code offset} (a type, method, field or variable reference) to the place
     * it is declared, the way an IDE's "go to declaration" does. The compiler does the resolving, so
     * imports, inheritance, overloads and shadowing are all honoured.
     *
     * @return empty if there is no resolvable name at the offset; otherwise the declaration, whose
     *         {@link DeclarationTarget#file() file} is empty when it lives in compiled code only
     */
    public Optional<DeclarationTarget> findDeclaration(Path file, String source, int offset, ProjectLayout layout) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(layout, "layout");
        if (compiler == null || offset < 0 || offset > source.length()) {
            return Optional.empty();
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        InMemorySource unit = new InMemorySource(file, source);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(collector, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, layout.sourceRoots());
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, layout.classpath());
            JavacTask task = (JavacTask) compiler.getTask(null, files, collector, OPTIONS, null, List.of(unit));
            Iterator<? extends CompilationUnitTree> parsed = task.parse().iterator();
            if (!parsed.hasNext()) {
                return Optional.empty();
            }
            CompilationUnitTree tree = parsed.next();
            task.analyze(); // attribution is what links each name to the symbol it refers to
            return new DeclarationFinder(Trees.instance(task)).find(tree, offset);
        } catch (RuntimeException | IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not resolve the declaration at offset " + offset + " of " + file, e);
            return Optional.empty();
        }
    }

    private static CompilationDiagnostic convert(Diagnostic<? extends JavaFileObject> diagnostic, String source) {
        int[] range = normalizeRange(source,
                diagnostic.getStartPosition(), diagnostic.getEndPosition(), diagnostic.getPosition());
        int lineStart = range[0] == 0 ? 0 : source.lastIndexOf('\n', range[0] - 1) + 1;
        int line = 1 + (int) source.chars().limit(range[0]).filter(c -> c == '\n').count();
        return new CompilationDiagnostic(
                severityOf(diagnostic.getKind()),
                diagnostic.getMessage(Locale.ENGLISH),
                line,
                range[0] - lineStart + 1,
                range[0],
                range[1],
                diagnostic.getCode());
    }

    private static Severity severityOf(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> Severity.WARNING;
            case NOTE, OTHER -> Severity.NOTE;
        };
    }

    /**
     * Turns the compiler's raw positions into a visible, in-bounds range {@code [start, end)}.
     * javac may report no position, an empty range, a multi-line range, or a position at
     * end-of-file; an editor needs a short span of real characters to underline.
     */
    static int[] normalizeRange(String source, long rawStart, long rawEnd, long preferred) {
        int length = source.length();
        if (length == 0) {
            return new int[] {0, 0};
        }
        if (rawStart == Diagnostic.NOPOS) {
            rawStart = preferred;
        }
        // A range spanning lines (e.g. a whole method body for "missing return statement") is too
        // coarse to underline; javac's preferred position pinpoints the actual spot.
        if (preferred >= rawStart && preferred < rawEnd && rawEnd <= length
                && source.substring((int) rawStart, (int) rawEnd).indexOf('\n') >= 0) {
            rawStart = preferred;
            rawEnd = preferred;
        }
        int start = (int) Math.max(0, Math.min(rawStart, length));
        int end = (int) Math.max(0, Math.min(rawEnd, length));

        // Errors like "reached end of file" or "';' expected" point at whitespace or past the end:
        // anchor them on the last real character before that point instead.
        if (end <= start) {
            while (start > 0 && (start == length || Character.isWhitespace(source.charAt(start)))) {
                start--;
            }
            end = start;
            // Cover the whole identifier/number the position falls on, or else a single character.
            while (end < length && Character.isJavaIdentifierPart(source.charAt(end))) {
                end++;
            }
            if (end == start) {
                end = start + 1;
            }
        }
        return new int[] {start, end};
    }

    /** A compilation unit whose content comes from memory but which is named after a real path. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String content;

        InMemorySource(Path file, String content) {
            super(file.toAbsolutePath().normalize().toUri(), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
