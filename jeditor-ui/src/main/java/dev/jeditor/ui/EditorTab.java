package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CompilationDiagnostic;
import dev.jeditor.compiler.DeclarationTarget;
import dev.jeditor.compiler.JavaSourceAnalyzer;
import org.fife.ui.rsyntaxtextarea.ErrorStrip;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult;
import org.fife.ui.rtextarea.ExpandedFoldRenderStrategy;
import org.fife.ui.rtextarea.FoldIndicatorStyle;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * One open file: a syntax-highlighting text area with line numbers, code folding and, for Java
 * files, live compiler diagnostics.
 */
final class EditorTab extends JPanel {

    /** Pause after the last keystroke before the file is re-checked. */
    private static final int ANALYSIS_DELAY_MS = 400;

    private final Path file;
    private final JavaSourceAnalyzer analyzer;
    private final ExecutorService executor;
    private final Consumer<Optional<DeclarationTarget>> navigator;

    private final RSyntaxTextArea textArea = new RSyntaxTextArea();
    private final RTextScrollPane scrollPane = new RTextScrollPane(textArea);
    private final DiagnosticsParser parser = new DiagnosticsParser();
    private final Timer analysisTimer = new Timer(ANALYSIS_DELAY_MS, e -> runAnalysis());
    private final List<Runnable> listeners = new ArrayList<>();

    private Charset charset = StandardCharsets.UTF_8;
    private boolean dirty;
    private boolean analysisPending;
    private long generation;
    private AnalysisResult result = AnalysisResult.EMPTY;

    /**
     * @param navigator receives, on the event thread, the outcome of each "go to declaration"
     *                  request: the declaration found, or empty if the spot refers to nothing
     */
    EditorTab(Path file, JavaSourceAnalyzer analyzer, ExecutorService executor,
              Consumer<Optional<DeclarationTarget>> navigator) throws IOException {
        super(new BorderLayout());
        this.file = file;
        this.analyzer = analyzer;
        this.executor = executor;
        this.navigator = navigator;

        load();
        configureEditor();

        add(scrollPane, BorderLayout.CENTER);
        if (isJava()) {
            ErrorStrip errorStrip = new ErrorStrip(textArea);
            add(errorStrip, BorderLayout.LINE_END);
            textArea.addParser(parser);
            ToolTipManager.sharedInstance().registerComponent(textArea);
            installDeclarationLinks();
            analysisTimer.setRepeats(false);
            analysisPending = analyzer.isAvailable();
            runAnalysis();
        }

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // attribute changes only; the text is the same
            }
        });
        textArea.addCaretListener(e -> fireChanged());
    }

    // ---- state --------------------------------------------------------------------------------

    Path file() {
        return file;
    }

    boolean isJava() {
        return file.getFileName().toString().endsWith(".java");
    }

    boolean isDirty() {
        return dirty;
    }

    /** True while the shown diagnostics do not yet reflect the current text. */
    boolean isAnalysisPending() {
        return analysisPending;
    }

    AnalysisResult result() {
        return result;
    }

    int caretOffset() {
        return textArea.getCaretPosition();
    }

    int caretLine() {
        return textArea.getCaretLineNumber() + 1;
    }

    int caretColumn() {
        return textArea.getCaretOffsetFromLineStart() + 1;
    }

    /** Called on the event thread whenever dirtiness, the caret or the analysis result changes. */
    void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    // ---- actions ------------------------------------------------------------------------------

    void save() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, charset)) {
            textArea.write(writer); // restores the file's original line endings
        }
        dirty = false;
        fireChanged();
    }

    void goTo(int offset) {
        int target = Math.max(0, Math.min(offset, textArea.getDocument().getLength()));
        textArea.getFoldManager().ensureOffsetNotInClosedFold(target);
        textArea.setCaretPosition(target);
        textArea.requestFocusInWindow();
        SwingUtilities.invokeLater(() -> {
            try {
                var view = textArea.modelToView2D(target);
                if (view != null) {
                    var bounds = view.getBounds();
                    bounds.grow(0, textArea.getVisibleRect().height / 3);
                    textArea.scrollRectToVisible(bounds);
                }
            } catch (BadLocationException ignored) {
                // offset was clamped to the document, cannot happen
            }
        });
    }

    /**
     * Resolves the name at {@code offset} to its declaration, off the event thread, and reports
     * the outcome to the navigator. Uses the current editor text, saved or not.
     */
    void goToDeclarationAt(int offset) {
        if (!isJava() || !analyzer.isAvailable()) {
            return;
        }
        String text = textArea.getText();
        executor.submit(() -> {
            Optional<DeclarationTarget> target;
            try {
                target = analyzer.findDeclaration(file, text, offset);
            } catch (RuntimeException e) {
                target = Optional.empty();
            }
            Optional<DeclarationTarget> found = target;
            SwingUtilities.invokeLater(() -> navigator.accept(found));
        });
    }

    void goToDeclarationAtCaret() {
        goToDeclarationAt(textArea.getCaretPosition());
    }

    void focusEditor() {
        textArea.requestFocusInWindow();
    }

    void dispose() {
        analysisTimer.stop();
        generation++; // drop any result still in flight
    }

    // ---- internals ----------------------------------------------------------------------------

    private void load() throws IOException {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            charset = StandardCharsets.ISO_8859_1;
            content = Files.readString(file, charset);
        }
        // read() normalises line endings in the document and remembers the original for save().
        textArea.read(new BufferedReader(new StringReader(content)), null);
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
    }

    private void configureEditor() {
        textArea.setSyntaxEditingStyle(styleFor(file));
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        textArea.setTabSize(4);
        textArea.setTabsEmulated(true);
        textArea.setMarkOccurrences(true);
        textArea.setPaintMatchedBracketPair(true);
        textArea.setAutoIndentEnabled(true);
        textArea.setCloseCurlyBraces(true);
        try (InputStream in = RSyntaxTextArea.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/idea.xml")) {
            if (in != null) {
                Theme.load(in).apply(textArea);
            }
        } catch (IOException ignored) {
            // the default colours are perfectly usable
        }
        textArea.setFont(textArea.getFont().deriveFont(13f));

        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setFoldIndicatorEnabled(true);
        scrollPane.setIconRowHeaderEnabled(isJava());
        scrollPane.setBorder(null);
        Gutter gutter = scrollPane.getGutter();
        gutter.setBookmarkingEnabled(false);
        // Classic [-]/[+] markers, always visible, so it is obvious where each block starts and ends.
        gutter.setFoldIndicatorStyle(FoldIndicatorStyle.CLASSIC);
        gutter.setExpandedFoldRenderStrategy(ExpandedFoldRenderStrategy.ALWAYS);
    }

    /**
     * Ctrl+click (Cmd+click on macOS) on a name jumps to its declaration. While the modifier is
     * held, the text area asks whether the token under the mouse is a link; deciding that must be
     * instant, so every identifier-like token qualifies and the compiler is consulted only on click.
     */
    private void installDeclarationLinks() {
        textArea.setHyperlinksEnabled(true);
        textArea.setLinkGenerator((area, offset) -> {
            Token token = area.modelToToken(offset);
            if (token == null || !isNameToken(token.getType())) {
                return null;
            }
            return new LinkGeneratorResult() {
                @Override
                public HyperlinkEvent execute() {
                    goToDeclarationAt(offset);
                    return null; // handled here; no hyperlink event to dispatch
                }

                @Override
                public int getSourceOffset() {
                    return offset;
                }
            };
        });
    }

    private static boolean isNameToken(int type) {
        // The highlighter classifies well-known JDK class names (String, List, ...) as
        // "data type"/"function"/"reserved word 2" rather than plain identifiers.
        return type == TokenTypes.IDENTIFIER || type == TokenTypes.FUNCTION || type == TokenTypes.DATA_TYPE
                || type == TokenTypes.RESERVED_WORD_2 || type == TokenTypes.ANNOTATION;
    }

    private void edited() {
        boolean wasDirty = dirty;
        dirty = true;
        if (isJava() && analyzer.isAvailable()) {
            analysisPending = true;
            analysisTimer.restart();
        }
        if (!wasDirty) {
            fireChanged();
        }
    }

    private void runAnalysis() {
        if (!isJava() || !analyzer.isAvailable()) {
            return;
        }
        long requested = ++generation;
        String text = textArea.getText();
        executor.submit(() -> {
            AnalysisResult fresh;
            try {
                fresh = analyzer.analyze(file, text);
            } catch (RuntimeException e) {
                fresh = AnalysisResult.EMPTY;
            }
            AnalysisResult computed = fresh;
            SwingUtilities.invokeLater(() -> {
                if (requested == generation) { // otherwise the text changed again meanwhile
                    apply(computed);
                }
            });
        });
    }

    private void apply(AnalysisResult fresh) {
        result = fresh;
        analysisPending = false;
        parser.setDiagnostics(fresh.diagnostics());
        textArea.forceReparsing(parser);

        Gutter gutter = scrollPane.getGutter();
        gutter.removeAllTrackingIcons();
        Set<Integer> marked = new HashSet<>();
        // Diagnostics are sorted most-severe-first within a position; show one icon per line.
        fresh.diagnostics().stream()
                .sorted((a, b) -> a.severity().compareTo(b.severity()))
                .forEach(d -> addGutterIcon(gutter, marked, d));
        fireChanged();
    }

    private void addGutterIcon(Gutter gutter, Set<Integer> marked, CompilationDiagnostic diagnostic) {
        int line = diagnostic.line() - 1;
        if (line < 0 || line >= textArea.getLineCount() || !marked.add(line)) {
            return;
        }
        try {
            gutter.addLineTrackingIcon(line, Icons.of(diagnostic.severity()), DiagnosticsParser.toHtml(diagnostic.message()));
        } catch (BadLocationException ignored) {
            // line vanished between analysis and display; the next analysis will catch up
        }
    }

    private void fireChanged() {
        listeners.forEach(Runnable::run);
    }

    static String styleFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return switch (extension) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "xml", "xsd", "xsl", "fxml", "iml" -> SyntaxConstants.SYNTAX_STYLE_XML;
            case "html", "htm" -> SyntaxConstants.SYNTAX_STYLE_HTML;
            case "json" -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case "properties" -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
            case "yaml", "yml" -> SyntaxConstants.SYNTAX_STYLE_YAML;
            case "md" -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            case "kt", "kts" -> SyntaxConstants.SYNTAX_STYLE_KOTLIN;
            case "groovy", "gradle" -> SyntaxConstants.SYNTAX_STYLE_GROOVY;
            case "js" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "css" -> SyntaxConstants.SYNTAX_STYLE_CSS;
            case "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL;
            case "sh", "bash" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            case "bat", "cmd" -> SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH;
            case "py" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }
}
