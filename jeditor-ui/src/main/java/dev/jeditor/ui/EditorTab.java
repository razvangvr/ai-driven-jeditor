package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CompilationDiagnostic;
import dev.jeditor.compiler.DeclarationTarget;
import dev.jeditor.compiler.JavaSourceAnalyzer;
import dev.jeditor.format.JavaFormatter;
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

    private static final System.Logger LOG = System.getLogger(EditorTab.class.getName());

    /** Pause after the last keystroke before the file is re-checked. */
    private static final int ANALYSIS_DELAY_MS = 400;

    private final Path file;
    private final JavaSourceAnalyzer analyzer;
    private final ExecutorService executor;
    private final Consumer<Optional<DeclarationTarget>> navigator;

    private final RSyntaxTextArea textArea = new RSyntaxTextArea();
    private final RTextScrollPane scrollPane = new RTextScrollPane(textArea);
    private final FindBar findBar = new FindBar(textArea);
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
        add(findBar, BorderLayout.SOUTH);
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
            } catch (BadLocationException e) {
                // offset was clamped to the document, so this should be impossible
                LOG.log(System.Logger.Level.WARNING, "Cannot scroll to offset " + target + " in " + file, e);
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
        executor.execute(() -> {
            Optional<DeclarationTarget> target;
            try {
                target = analyzer.findDeclaration(file, text, offset);
            } catch (RuntimeException | Error e) {
                LOG.log(System.Logger.Level.ERROR, "Go to declaration failed in " + file, e);
                target = Optional.empty();
            }
            Optional<DeclarationTarget> found = target;
            SwingUtilities.invokeLater(() -> navigator.accept(found));
        });
    }

    void goToDeclarationAtCaret() {
        goToDeclarationAt(textArea.getCaretPosition());
    }

    // ---- editing commands (Edit menu) ---------------------------------------------------------

    void undo() {
        textArea.undoLastAction();
    }

    void redo() {
        textArea.redoLastAction();
    }

    void cut() {
        textArea.cut();
    }

    void copy() {
        textArea.copy();
    }

    void paste() {
        textArea.paste();
    }

    void selectAll() {
        textArea.selectAll();
    }

    /** What a reformat request came to. */
    enum FormatOutcome {
        REFORMATTED, ALREADY_FORMATTED, CANNOT_FORMAT, NOT_JAVA
    }

    /**
     * Reformats the selection, or the whole file when nothing is selected, as one undoable edit.
     * The formatting runs off the event thread; {@code report} is then called on the event thread.
     * If the text is edited in the meantime the result is dropped, since it no longer applies.
     */
    void reformat(JavaFormatter formatter, Consumer<FormatOutcome> report) {
        if (!isJava()) {
            report.accept(FormatOutcome.NOT_JAVA);
            return;
        }
        String text = textArea.getText();
        int selectionStart = textArea.getSelectionStart();
        int selectionEnd = textArea.getSelectionEnd();
        executor.execute(() -> {
            Optional<String> result;
            try {
                result = selectionEnd > selectionStart
                        ? formatter.format(text, selectionStart, selectionEnd - selectionStart)
                        : formatter.format(text);
            } catch (RuntimeException | Error e) {
                LOG.log(System.Logger.Level.ERROR, "Reformatting failed in " + file, e);
                result = Optional.empty();
            }
            Optional<String> formatted = result;
            SwingUtilities.invokeLater(() -> {
                if (!textArea.getText().equals(text)) {
                    return;
                }
                if (formatted.isEmpty()) {
                    report.accept(FormatOutcome.CANNOT_FORMAT);
                } else if (formatted.get().equals(text)) {
                    report.accept(FormatOutcome.ALREADY_FORMATTED);
                } else {
                    replaceText(text, formatted.get());
                    report.accept(FormatOutcome.REFORMATTED);
                }
            });
        });
    }

    /** Swaps the document's text for {@code replacement}, touching only the part that differs, as one undo step. */
    private void replaceText(String current, String replacement) {
        int caret = caretAfterReformat(current, textArea.getCaretPosition(), replacement);
        int prefix = 0;
        int limit = Math.min(current.length(), replacement.length());
        while (prefix < limit && current.charAt(prefix) == replacement.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && current.charAt(current.length() - 1 - suffix) == replacement.charAt(replacement.length() - 1 - suffix)) {
            suffix++;
        }
        textArea.beginAtomicEdit();
        try {
            textArea.replaceRange(replacement.substring(prefix, replacement.length() - suffix), prefix, current.length() - suffix);
        } finally {
            textArea.endAtomicEdit();
        }
        textArea.setCaretPosition(Math.min(caret, textArea.getDocument().getLength()));
    }

    /**
     * Where the caret belongs after reformatting: in front of the same character as before.
     * Formatting only moves whitespace around, so "the same character" is found by counting the
     * non-whitespace characters that precede the caret.
     */
    static int caretAfterReformat(String before, int caret, String after) {
        int significant = 0;
        for (int i = 0; i < Math.min(caret, before.length()); i++) {
            if (!Character.isWhitespace(before.charAt(i))) {
                significant++;
            }
        }
        int position = 0;
        while (position < after.length() && significant > 0) {
            if (!Character.isWhitespace(after.charAt(position))) {
                significant--;
            }
            position++;
        }
        // Step over the whitespace that now precedes the character the caret was in front of,
        // unless the caret was itself sitting in whitespace at the end of a line.
        boolean caretBeforeText = caret < before.length() && !Character.isWhitespace(before.charAt(caret));
        while (caretBeforeText && position < after.length() && Character.isWhitespace(after.charAt(position))) {
            position++;
        }
        return position;
    }

    void showFind(boolean withReplace) {
        findBar.open(withReplace);
    }

    int lineCount() {
        return textArea.getLineCount();
    }

    /** Moves the caret to a 1-based line (and optional 1-based column), clamped to the document. */
    void goToLine(int line, int column) {
        try {
            int index = Math.max(0, Math.min(line, textArea.getLineCount()) - 1);
            int start = textArea.getLineStartOffset(index);
            int end = textArea.getLineEndOffset(index);
            int lastColumn = index == textArea.getLineCount() - 1 ? end : end - 1; // before the line break
            goTo(Math.min(start + Math.max(0, column - 1), Math.max(start, lastColumn)));
        } catch (BadLocationException e) {
            // index was clamped to an existing line, so this should be impossible
            LOG.log(System.Logger.Level.WARNING, "Cannot go to line " + line + " of " + file, e);
        }
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
            // Expected for legacy files, so one line without a stack trace is enough.
            LOG.log(System.Logger.Level.WARNING, file + " is not valid UTF-8 (" + e.getMessage() + "); opening it as ISO-8859-1");
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
        } catch (IOException e) {
            // the default colours are perfectly usable
            LOG.log(System.Logger.Level.WARNING, "Cannot load the editor colour theme; using the default colours", e);
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
        // execute(), not submit(): submit() parks anything thrown in a Future nobody reads.
        executor.execute(() -> {
            AnalysisResult fresh;
            try {
                fresh = analyzer.analyze(file, text);
            } catch (RuntimeException | Error e) {
                // Also Errors (a compiler stack overflow on deeply nested code, a linkage problem):
                // otherwise the Problems view would say "compiling..." for ever.
                LOG.log(System.Logger.Level.ERROR, "Checking " + file + " for errors failed", e);
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
        } catch (BadLocationException e) {
            // line vanished between analysis and display; the next analysis will catch up
            LOG.log(System.Logger.Level.WARNING, "Cannot mark line " + (line + 1) + " of " + file + " in the gutter", e);
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
