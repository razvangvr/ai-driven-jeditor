package dev.jeditor.compiler;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeStructureTest {

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @Test
    void classWithMembersInSourceOrder() {
        String source = """
                package demo;

                public class Account {
                    private static final int LIMIT = 10;
                    private final String owner;

                    static {
                        System.out.println("loaded");
                    }

                    public Account(String owner) {
                        this.owner = owner;
                    }

                    public String owner() {
                        return owner;
                    }

                    void deposit(int amount, java.util.List<String> notes) {
                    }
                }
                """;
        CodeBlock type = onlyType(source);

        assertEquals(CodeBlock.Kind.CLASS, type.kind());
        assertEquals("Account", type.name());
        assertEquals(3, type.startLine());
        assertEquals(21, type.endLine());
        assertEquals(List.of(
                "FIELD LIMIT : int",
                "FIELD owner : String",
                "INITIALIZER static {...}",
                "CONSTRUCTOR Account(String)",
                "METHOD owner() : String",
                "METHOD deposit(int, java.util.List<String>) : void"), describe(type.children()));
    }

    @Test
    void methodBlockSpansFromSignatureToClosingBrace() {
        String source = """
                class A {
                    @Override
                    public String toString() {
                        return "A";
                    }
                }
                """;
        CodeBlock method = onlyType(source).children().get(0);

        assertEquals(2, method.startLine());
        assertEquals(5, method.endLine());
        String text = source.substring(method.startOffset(), method.endOffset());
        assertTrue(text.startsWith("@Override"), text);
        assertTrue(text.endsWith("}"), text);
        assertTrue(method.contains(source.indexOf("return")));
        assertFalse(method.contains(method.endOffset()));
    }

    @Test
    void noSyntheticDefaultConstructor() {
        assertEquals(List.of(), onlyType("class A {}").children());
    }

    @Test
    void nestedAndMultipleTopLevelTypes() {
        List<CodeBlock> types = structure("""
                interface Shape { double area(); }
                class Outer {
                    class Inner { void m() {} }
                    @interface Marker {}
                }
                """);

        assertEquals(List.of("INTERFACE Shape", "CLASS Outer"), describe(types));
        assertEquals(List.of("METHOD area() : double"), describe(types.get(0).children()));
        CodeBlock outer = types.get(1);
        assertEquals(List.of("CLASS Inner", "ANNOTATION Marker"), describe(outer.children()));
        assertEquals(List.of("METHOD m() : void"), describe(outer.children().get(0).children()));
        assertTrue(outer.children().get(0).kind().isType());
    }

    @Test
    void enumConstantsAndMembers() {
        CodeBlock type = onlyType("""
                enum Color {
                    RED, GREEN("g");
                    private String code;
                    Color() {}
                    Color(String code) { this.code = code; }
                }
                """);

        assertEquals(CodeBlock.Kind.ENUM, type.kind());
        assertEquals(List.of(
                "ENUM_CONSTANT RED",
                "ENUM_CONSTANT GREEN",
                "FIELD code : String",
                "CONSTRUCTOR Color()",
                "CONSTRUCTOR Color(String)"), describe(type.children()));
    }

    @Test
    void recordWithMethod() {
        CodeBlock type = onlyType("""
                record Point(int x, int y) {
                    double norm() { return Math.sqrt(x * x + y * y); }
                }
                """);

        assertEquals(CodeBlock.Kind.RECORD, type.kind());
        assertTrue(describe(type.children()).contains("METHOD norm() : double"), type.children().toString());
    }

    @Test
    void localAndAnonymousClassesAreNotPartOfTheOutline() {
        CodeBlock type = onlyType("""
                class A {
                    Runnable r = new Runnable() { public void run() {} };
                    void f() { class Local {} }
                }
                """);
        assertEquals(List.of("FIELD r : Runnable", "METHOD f() : void"), describe(type.children()));
    }

    @Test
    void structureSurvivesCompileErrors() {
        List<CodeBlock> types = structure("""
                class A {
                    void ok() {}
                    void broken() { int x = ; undefined(); }
                    void alsoOk() {}
                }
                """);
        assertEquals(List.of("METHOD ok() : void", "METHOD broken() : void", "METHOD alsoOk() : void"),
                describe(types.get(0).children()));
    }

    @Test
    void childrenAreContainedInParent() {
        CodeBlock type = onlyType("""
                class A {
                    int a;
                    void m() {}
                    class B { int c; }
                }
                """);
        assertContained(type);
    }

    @Test
    void pathToFindsEnclosingBlocksOutermostFirst() {
        String source = """
                class A {
                    int a;
                    class B {
                        void m() { int here; }
                    }
                }
                class Z {}
                """;
        List<CodeBlock> types = structure(source);

        assertEquals(List.of("CLASS A", "CLASS B", "METHOD m() : void"),
                describe(CodeBlock.pathTo(types, source.indexOf("here"))));
        assertEquals(List.of("CLASS A"), describe(CodeBlock.pathTo(types, source.indexOf("class B") - 2)));
        assertEquals(List.of("CLASS Z"), describe(CodeBlock.pathTo(types, source.indexOf("Z {}"))));
        assertEquals(List.of(), describe(CodeBlock.pathTo(types, source.length())));
    }

    private static void assertContained(CodeBlock parent) {
        for (CodeBlock child : parent.children()) {
            assertTrue(child.startOffset() >= parent.startOffset() && child.endOffset() <= parent.endOffset(),
                    child + " not inside " + parent);
            assertTrue(child.startLine() >= parent.startLine() && child.endLine() <= parent.endLine());
            assertContained(child);
        }
    }

    private List<CodeBlock> structure(String source) {
        return analyzer.analyze(Path.of("Test.java"), source, ProjectLayout.NONE).structure();
    }

    private CodeBlock onlyType(String source) {
        List<CodeBlock> types = structure(source);
        assertEquals(1, types.size(), types.toString());
        return types.get(0);
    }

    private static List<String> describe(List<CodeBlock> blocks) {
        return blocks.stream().map(b -> b.kind() + " " + b.name()).toList();
    }
}
