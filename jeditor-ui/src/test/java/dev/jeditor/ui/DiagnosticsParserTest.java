package dev.jeditor.ui;

import dev.jeditor.compiler.CompilationDiagnostic;
import dev.jeditor.compiler.JavaSourceAnalyzer;
import dev.jeditor.compiler.ProjectLayout;
import dev.jeditor.compiler.Severity;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.junit.jupiter.api.Test;

import javax.swing.text.BadLocationException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsParserTest {

    private final DiagnosticsParser parser = new DiagnosticsParser();

    @Test
    void compilerDiagnosticsBecomeNoticesAtTheSamePlace() throws BadLocationException {
        String source = "class A {\n    int x = missing;\n    java.util.List raw;\n}\n";
        parser.setDiagnostics(new JavaSourceAnalyzer().analyze(Path.of("A.java"), source, ProjectLayout.NONE).diagnostics());

        List<ParserNotice> notices = parse(source);

        assertEquals(2, notices.size());
        ParserNotice error = notices.get(0);
        assertEquals(ParserNotice.Level.ERROR, error.getLevel());
        assertEquals(1, error.getLine(), "notice lines are 0-based");
        assertEquals(source.indexOf("missing"), error.getOffset());
        assertEquals("missing".length(), error.getLength());
        assertEquals("cannot find symbol", error.getMessage());
        assertTrue(error.getToolTipText().contains("<br>"), error.getToolTipText());

        ParserNotice warning = notices.get(1);
        assertEquals(ParserNotice.Level.WARNING, warning.getLevel());
        assertEquals(2, warning.getLine());
    }

    @Test
    void staleDiagnosticsAreClampedToTheDocument() throws BadLocationException {
        parser.setDiagnostics(List.of(
                new CompilationDiagnostic(Severity.ERROR, "gone", 9, 1, 500, 510, "x"),
                new CompilationDiagnostic(Severity.NOTE, "overlong", 1, 1, 2, 400, "y")));

        List<ParserNotice> notices = parse("short");

        assertEquals(4, notices.get(0).getOffset());
        assertEquals(1, notices.get(0).getLength());
        assertEquals(2, notices.get(1).getOffset());
        assertEquals(3, notices.get(1).getLength());
        assertEquals(ParserNotice.Level.INFO, notices.get(1).getLevel());
    }

    @Test
    void emptyDocumentIsHandled() throws BadLocationException {
        parser.setDiagnostics(List.of(new CompilationDiagnostic(Severity.ERROR, "m", 1, 1, 3, 4, "x")));
        ParserNotice notice = parse("").get(0);
        assertEquals(0, notice.getOffset());
        assertEquals(0, notice.getLength());
    }

    @Test
    void noDiagnosticsNoNotices() throws BadLocationException {
        assertTrue(parse("class A {}").isEmpty());
    }

    @Test
    void tooltipHtmlIsEscaped() {
        assertEquals("<html>List&lt;T&gt; &amp; more<br>&nbsp;&nbsp;detail</html>",
                DiagnosticsParser.toHtml("List<T> & more\n  detail"));
    }

    private List<ParserNotice> parse(String text) throws BadLocationException {
        RSyntaxDocument document = new RSyntaxDocument(SyntaxConstants.SYNTAX_STYLE_JAVA);
        document.insertString(0, text, null);
        return parser.parse(document, SyntaxConstants.SYNTAX_STYLE_JAVA).getNotices();
    }
}
