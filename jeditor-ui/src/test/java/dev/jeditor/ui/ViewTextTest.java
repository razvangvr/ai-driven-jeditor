package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CodeBlock;
import dev.jeditor.compiler.CompilationDiagnostic;
import dev.jeditor.compiler.Severity;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Text shown in the problems header, status bar breadcrumb, and the file-type mapping. */
class ViewTextTest {

    @Test
    void problemsHeaderCountsErrorsAndWarnings() {
        AnalysisResult result = new AnalysisResult(List.of(
                new CompilationDiagnostic(Severity.ERROR, "a", 1, 1, 0, 1, "x"),
                new CompilationDiagnostic(Severity.WARNING, "b", 1, 1, 0, 1, "x"),
                new CompilationDiagnostic(Severity.WARNING, "c", 1, 1, 0, 1, "x")), List.of(), List.of());
        assertEquals("Problems — A.java: 1 error, 2 warnings", ProblemsPanel.headerText("A.java", result, false));
    }

    @Test
    void problemsHeaderForCleanPendingAndNonJavaFiles() {
        assertEquals("Problems — A.java: no problems", ProblemsPanel.headerText("A.java", AnalysisResult.EMPTY, false));
        assertEquals("Problems — A.java: compiling…", ProblemsPanel.headerText("A.java", AnalysisResult.EMPTY, true));
        assertEquals("Problems", ProblemsPanel.headerText(null, AnalysisResult.EMPTY, false));
    }

    @Test
    void breadcrumbShowsPathAndInnermostLineRange() {
        CodeBlock method = new CodeBlock(CodeBlock.Kind.METHOD, "run() : void", 20, 40, 3, 7, List.of());
        CodeBlock type = new CodeBlock(CodeBlock.Kind.CLASS, "Job", 0, 50, 1, 9, List.of(method));
        assertEquals("Job  ›  run() : void   (lines 3–7)", MainFrame.breadcrumb(List.of(type, method)));
        assertEquals(" ", MainFrame.breadcrumb(List.of()));
    }

    @Test
    void syntaxStyleFollowsFileExtension() {
        assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA, EditorTab.styleFor(Path.of("src/Main.java")));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_XML, EditorTab.styleFor(Path.of("POM.XML")));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_NONE, EditorTab.styleFor(Path.of("LICENSE")));
    }
}
