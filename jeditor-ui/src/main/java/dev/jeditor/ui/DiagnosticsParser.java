package dev.jeditor.ui;

import dev.jeditor.compiler.CompilationDiagnostic;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;

import javax.swing.text.Element;
import java.util.List;

/**
 * Feeds compiler diagnostics to the text area, which renders them as squiggly underlines with
 * tooltips and as marks in the error strip.
 *
 * <p>Compilation itself happens off the event thread (see {@link EditorTab}); this parser only
 * replays the most recent result, so it is cheap enough for the text area to call at will.
 */
final class DiagnosticsParser extends AbstractParser {

    private List<CompilationDiagnostic> diagnostics = List.of();

    void setDiagnostics(List<CompilationDiagnostic> diagnostics) {
        this.diagnostics = List.copyOf(diagnostics);
    }

    @Override
    public ParseResult parse(RSyntaxDocument doc, String style) {
        DefaultParseResult result = new DefaultParseResult(this);
        Element root = doc.getDefaultRootElement();
        result.setParsedLines(0, root.getElementCount() - 1);

        int docLength = doc.getLength();
        for (CompilationDiagnostic diagnostic : diagnostics) {
            // The document may have been edited since the diagnostics were computed: clamp.
            int start = Math.min(diagnostic.startOffset(), Math.max(0, docLength - 1));
            int length = Math.max(1, Math.min(diagnostic.length(), docLength - start));
            if (docLength == 0) {
                start = 0;
                length = 0;
            }
            DefaultParserNotice notice = new DefaultParserNotice(
                    this, diagnostic.summary(), root.getElementIndex(start), start, length);
            notice.setLevel(switch (diagnostic.severity()) {
                case ERROR -> ParserNotice.Level.ERROR;
                case WARNING -> ParserNotice.Level.WARNING;
                case NOTE -> ParserNotice.Level.INFO;
            });
            notice.setToolTipText(toHtml(diagnostic.message()));
            result.addNotice(notice);
        }
        return result;
    }

    static String toHtml(String message) {
        String escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html>" + escaped.replace("\n", "<br>").replace("  ", "&nbsp;&nbsp;") + "</html>";
    }
}
