package dev.jeditor.ui;

import dev.jeditor.compiler.AnalysisResult;
import dev.jeditor.compiler.CodeBlock;
import dev.jeditor.compiler.CompilationDiagnostic;
import dev.jeditor.compiler.Severity;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void goToLineInputParsing() {
        assertArrayEquals(new int[] {12, 1}, MainFrame.parseLineColumn(" 12 "));
        assertArrayEquals(new int[] {12, 5}, MainFrame.parseLineColumn("12:5"));
        assertArrayEquals(new int[] {12, 5}, MainFrame.parseLineColumn("12 , 5"));
        assertArrayEquals(new int[] {1, 1}, MainFrame.parseLineColumn("0:-3"));
        assertNull(MainFrame.parseLineColumn("abc"));
        assertNull(MainFrame.parseLineColumn("1:2:3"));
        assertNull(MainFrame.parseLineColumn(""));
    }

    @Test
    void caretStaysInFrontOfTheSameCharacterAfterReformat() {
        String before = "class A{int f(){return 1;}}";
        String after = "class A {\n    int f() {\n        return 1;\n    }\n}\n";

        // in front of 'r' of return
        assertEquals(after.indexOf("return"), EditorTab.caretAfterReformat(before, before.indexOf("return"), after));
        // inside a word
        assertEquals(after.indexOf("turn"), EditorTab.caretAfterReformat(before, before.indexOf("turn"), after));
        // start and end of the file
        assertEquals(0, EditorTab.caretAfterReformat(before, 0, after));
        assertEquals(after.lastIndexOf('}') + 1, EditorTab.caretAfterReformat(before, before.length(), after));
        // caret in whitespace that gets removed
        assertEquals("class A {\n    int".length(), EditorTab.caretAfterReformat("class A{int     x;}", 13, "class A {\n    int x;\n}\n"));
    }

    @Test
    void deleteConfirmationSaysHowMuchAFolderContains() {
        assertEquals("The folder is empty.", MainFrame.describeContents(0, 0));
        assertEquals("Contains 1 file.", MainFrame.describeContents(1, 0));
        assertEquals("Contains 3 subfolders.", MainFrame.describeContents(0, 3));
        assertEquals("Contains 12 files in 4 subfolders.", MainFrame.describeContents(12, 4));
        assertEquals("Contains 2 files in 1 subfolder.", MainFrame.describeContents(2, 1));
    }

    @Test
    void syntaxStyleFollowsFileExtension() {
        assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA, EditorTab.styleFor(Path.of("src/Main.java")));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_XML, EditorTab.styleFor(Path.of("POM.XML")));
        assertEquals(SyntaxConstants.SYNTAX_STYLE_NONE, EditorTab.styleFor(Path.of("LICENSE")));
    }
}
