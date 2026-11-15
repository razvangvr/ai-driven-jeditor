package dev.jeditor.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceAnalyzerTest {

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @TempDir
    Path dir;

    // ---- clean code ---------------------------------------------------------------------------

    @Test
    void validSourceHasNoDiagnostics() {
        AnalysisResult result = analyze("Hello.java", """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                """);
        assertEquals(List.of(), result.diagnostics());
        assertFalse(result.hasErrors());
    }

    @Test
    void java21LanguageFeaturesAreAccepted() {
        AnalysisResult result = analyze("Shapes.java", """
                import java.util.List;

                public class Shapes {
                    sealed interface Shape permits Circle, Square {}
                    record Circle(double radius) implements Shape {}
                    record Square(double side) implements Shape {}

                    static double area(Shape shape) {
                        return switch (shape) {
                            case Circle c -> Math.PI * c.radius() * c.radius();
                            case Square(double side) -> side * side;
                        };
                    }

                    static String describe(Object o) {
                        if (o instanceof String s && !s.isEmpty()) {
                            return s;
                        }
                        var text = \"""
                                multi
                                line\""";
                        return text + List.of(1, 2).size();
                    }
                }
                """);
        assertEquals(List.of(), result.diagnostics());
    }

    @Test
    void emptySourceIsFine() {
        AnalysisResult result = analyze("Empty.java", "");
        assertEquals(List.of(), result.diagnostics());
        assertEquals(List.of(), result.structure());
    }

    // ---- errors -------------------------------------------------------------------------------

    @Test
    void missingSemicolonIsReportedOnTheOffendingLine() {
        String source = """
                class A {
                    int x = 1
                    int y = 2;
                }
                """;
        AnalysisResult result = analyze("A.java", source);

        assertEquals(1, result.diagnostics().size());
        CompilationDiagnostic d = result.diagnostics().get(0);
        assertEquals(Severity.ERROR, d.severity());
        assertEquals("';' expected", d.message());
        assertEquals(2, d.line());
        assertEquals("1", text(source, d), "should underline the token after which the ';' is missing");
        assertEquals("compiler.err.expected", d.code());
    }

    @Test
    void unknownSymbolIsUnderlinedExactly() {
        String source = """
                class A {
                    void f() {
                        int x = undefinedThing + 1;
                    }
                }
                """;
        CompilationDiagnostic d = onlyDiagnostic(analyze("A.java", source));

        assertEquals(Severity.ERROR, d.severity());
        assertEquals("cannot find symbol", d.summary());
        assertTrue(d.message().contains("undefinedThing"), d.message());
        assertEquals("undefinedThing", text(source, d));
        assertEquals(3, d.line());
        assertEquals(17, d.column());
        assertEquals(source.indexOf("undefinedThing"), d.startOffset());
        assertEquals(14, d.length());
    }

    @Test
    void typeMismatchIsReported() {
        String source = """
                class A {
                    int x = "text";
                }
                """;
        CompilationDiagnostic d = onlyDiagnostic(analyze("A.java", source));
        assertTrue(d.message().startsWith("incompatible types"), d.message());
        assertEquals("\"text\"", text(source, d));
    }

    @Test
    void syntaxSemanticAndFlowErrorsAreAllReportedInOnePass() {
        String source = """
                class A {
                    int broken = ;
                    String s = 42;
                    int noReturn() {
                    }
                }
                """;
        AnalysisResult result = analyze("A.java", source);

        List<String> codes = result.diagnostics().stream().map(CompilationDiagnostic::code).toList();
        assertEquals(List.of(
                "compiler.err.illegal.start.of.expr",
                "compiler.err.prob.found.req",
                "compiler.err.missing.ret.stmt"), codes);
        assertEquals(List.of(2, 3, 5), result.diagnostics().stream().map(CompilationDiagnostic::line).toList());
        assertEquals(3, result.count(Severity.ERROR));
        CompilationDiagnostic missingReturn = result.diagnostics().get(2);
        assertEquals("}", text(source, missingReturn), "should mark the method's closing brace, not its whole body");
    }

    @Test
    void diagnosticsAreSortedByPosition() {
        AnalysisResult result = analyze("A.java", """
                class A {
                    void f() { b(); }
                    void g() { a(); }
                    int h = c;
                }
                """);
        List<Integer> offsets = result.diagnostics().stream().map(CompilationDiagnostic::startOffset).toList();
        assertEquals(3, offsets.size());
        assertEquals(offsets.stream().sorted().toList(), offsets);
    }

    @Test
    void publicClassMustMatchFileName() {
        CompilationDiagnostic d = onlyDiagnostic(analyze("Wrong.java", "public class Right {}"));
        assertEquals("compiler.err.class.public.should.be.in.file", d.code());
    }

    @Test
    void unterminatedCodeProducesInBoundsRange() {
        String source = "class A {\n    void f() {\n";
        AnalysisResult result = analyze("A.java", source);

        assertTrue(result.hasErrors());
        for (CompilationDiagnostic d : result.diagnostics()) {
            assertTrue(d.startOffset() >= 0 && d.endOffset() <= source.length(), d.toString());
            assertTrue(d.length() > 0, d.toString());
            assertFalse(text(source, d).isBlank(), "should point at a visible character: " + d);
        }
    }

    @Test
    void uncaughtCheckedExceptionIsReported() {
        CompilationDiagnostic d = onlyDiagnostic(analyze("A.java", """
                class A {
                    void f() {
                        throw new java.io.IOException("x");
                    }
                }
                """));
        assertEquals("compiler.err.unreported.exception.need.to.catch.or.throw", d.code());
        assertEquals(3, d.line());
    }

    @Test
    void garbageInputDoesNotThrow() {
        AnalysisResult result = analyze("A.java", "}}}} \u0000 @@ class ( { \"unterminated");
        assertTrue(result.hasErrors());
    }

    // ---- warnings -----------------------------------------------------------------------------

    @Test
    void lintProblemsAreWarningsNotErrors() {
        String source = """
                import java.util.ArrayList;
                import java.util.List;

                class A {
                    List raw = new ArrayList();
                }
                """;
        AnalysisResult result = analyze("A.java", source);

        assertFalse(result.hasErrors());
        assertTrue(result.count(Severity.WARNING) >= 1, result.toString());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.line() == 5), result.toString());
    }

    @Test
    void deprecatedApiUseIsAWarning() {
        AnalysisResult result = analyze("A.java", """
                class A {
                    @Deprecated void old() {}
                }
                class B {
                    void f(A a) { a.old(); }
                }
                """);
        CompilationDiagnostic d = onlyDiagnostic(result);
        assertEquals(Severity.WARNING, d.severity());
        assertEquals(5, d.line());
    }

    // ---- resolving other classes --------------------------------------------------------------

    @Test
    void siblingClassesInTheSameSourceTreeAreResolved() throws IOException {
        write("src/com/acme/util/Greeter.java", """
                package com.acme.util;
                public class Greeter {
                    public String greet(String name) { return "Hi " + name; }
                }
                """);
        String source = """
                package com.acme.app;
                import com.acme.util.Greeter;
                public class Main {
                    String s = new Greeter().greet("you");
                    int bad = new Greeter().greet("you");
                }
                """;
        Path main = write("src/com/acme/app/Main.java", source);

        CompilationDiagnostic d = onlyDiagnostic(analyzer.analyze(main, source));
        assertEquals(5, d.line());
        assertTrue(d.message().startsWith("incompatible types"), d.message());
    }

    @Test
    void editorContentWinsOverWhatIsOnDisk() throws IOException {
        Path file = write("A.java", "class A { int broken = ; }");
        assertEquals(List.of(), analyzer.analyze(file, "class A { int fixed = 1; }").diagnostics());
    }

    @Test
    void classReferencedBackFromASiblingIsNotCompiledTwice() throws IOException {
        // B (on disk) refers to A; the stale on-disk A must not clash with the in-memory A.
        write("p/B.java", "package p; public class B { A a; int n = A.NEW_CONSTANT; }");
        Path a = write("p/A.java", "package p; public class A { }");
        String edited = "package p; public class A { static final int NEW_CONSTANT = 1; B b; }";

        assertEquals(List.of(), analyzer.analyze(a, edited).diagnostics());
    }

    @Test
    void errorsInOtherFilesAreNotAttributedToThisOne() throws IOException {
        write("p/Broken.java", "package p; public class Broken { int x = \"nope\"; }");
        String source = "package p; public class Uses { Broken b; }";
        Path uses = write("p/Uses.java", source);

        assertEquals(List.of(), analyzer.analyze(uses, source).diagnostics());
    }

    @Test
    void compiledClassesOnTheClasspathAreResolved() throws IOException {
        Path libSource = write("lib-src/lib/Tool.java", "package lib; public class Tool { public static int id() { return 7; } }");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), libSource.toString());
        assertEquals(0, exit);

        String source = "class UsesLib { int id = lib.Tool.id(); }";
        Path file = dir.resolve("elsewhere/UsesLib.java");

        assertTrue(analyzer.analyze(file, source, ProjectLayout.NONE).hasErrors());
        assertEquals(List.of(),
                analyzer.analyze(file, source, new ProjectLayout(List.of(), List.of(classes))).diagnostics());
    }

    @Test
    void editorsOwnClasspathIsNotVisibleToAnalysedCode() {
        // JUnit is on the test classpath of this JVM, but must not leak into user code.
        AnalysisResult result = analyze("A.java", "class A { org.junit.jupiter.api.Test t; }");
        assertTrue(result.hasErrors());
    }

    @Test
    void nothingIsWrittenToDisk() throws IOException {
        String source = "package p; public class Clean {}";
        Path file = write("p/Clean.java", source);
        analyzer.analyze(file, source);
        try (var files = Files.walk(dir)) {
            assertEquals(List.of(), files.filter(f -> f.toString().endsWith(".class")).toList());
        }
    }

    // ---- availability -------------------------------------------------------------------------

    @Test
    void withoutACompilerAnalysisIsEmptyRatherThanFailing() {
        JavaSourceAnalyzer noCompiler = new JavaSourceAnalyzer(null);
        assertFalse(noCompiler.isAvailable());
        assertEquals(AnalysisResult.EMPTY, noCompiler.analyze(dir.resolve("A.java"), "class A { broken }"));
        assertTrue(analyzer.isAvailable());
    }

    // ---- range normalisation ------------------------------------------------------------------

    @Test
    void normalizeKeepsProperRanges() {
        assertArrayEquals(new int[] {4, 7}, JavaSourceAnalyzer.normalizeRange("int foo = 1;", 4, 7, 4));
    }

    @Test
    void normalizeExpandsEmptyRangeOverIdentifier() {
        assertArrayEquals(new int[] {4, 7}, JavaSourceAnalyzer.normalizeRange("int foo = 1;", 4, 4, 4));
        assertArrayEquals(new int[] {4, 7}, JavaSourceAnalyzer.normalizeRange("int foo = 1;", 4, -1, 4));
    }

    @Test
    void normalizeUsesSingleCharacterForPunctuation() {
        assertArrayEquals(new int[] {8, 9}, JavaSourceAnalyzer.normalizeRange("int foo = 1;", 8, 8, 8));
    }

    @Test
    void normalizeMovesEndOfFilePositionsOntoLastVisibleCharacter() {
        assertArrayEquals(new int[] {8, 9}, JavaSourceAnalyzer.normalizeRange("class A {\n  \n", 12, 12, 12));
        assertArrayEquals(new int[] {8, 9}, JavaSourceAnalyzer.normalizeRange("class A {\n  \n", 999, 999, 999));
    }

    @Test
    void normalizeCollapsesMultiLineRangeOntoPreferredPosition() {
        String source = "int f() {\n  g();\n}";
        assertArrayEquals(new int[] {17, 18}, JavaSourceAnalyzer.normalizeRange(source, 8, 18, 17));
        assertArrayEquals(new int[] {12, 13}, JavaSourceAnalyzer.normalizeRange(source, 8, 18, 12));
    }

    @Test
    void normalizeHandlesMissingPositionAndEmptySource() {
        assertArrayEquals(new int[] {0, 5}, JavaSourceAnalyzer.normalizeRange("class A {}", -1, -1, -1));
        assertArrayEquals(new int[] {0, 0}, JavaSourceAnalyzer.normalizeRange("", -1, -1, -1));
        assertArrayEquals(new int[] {0, 1}, JavaSourceAnalyzer.normalizeRange("   ", 2, 2, 2));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private AnalysisResult analyze(String fileName, String source) {
        return analyzer.analyze(dir.resolve(fileName), source, ProjectLayout.NONE);
    }

    private static CompilationDiagnostic onlyDiagnostic(AnalysisResult result) {
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        return result.diagnostics().get(0);
    }

    private static String text(String source, CompilationDiagnostic d) {
        return source.substring(d.startOffset(), d.endOffset());
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
