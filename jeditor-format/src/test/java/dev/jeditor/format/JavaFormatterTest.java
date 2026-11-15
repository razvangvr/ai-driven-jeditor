package dev.jeditor.format;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFormatterTest {

    private final JavaFormatter formatter = new JavaFormatter();

    // ---- the conventions ----------------------------------------------------------------------

    @Test
    void appliesTheStandardJavaConventions() {
        String messy = """
                package a.b;import java.util.List;
                public class Foo{private int x=1;



                public Foo(int x){this.x=x;}
                int max(int a,int b){if(a>b){return a;}else{return b;}}
                \tvoid loop(List<String> items)
                \t{
                \t\tfor(String item:items){System.out.println(item);}
                \t\ttry{max(1,2);}catch(Exception e){throw new IllegalStateException(e);}finally{x=0;}
                \t}
                }
                """;
        String expected = """
                package a.b;

                import java.util.List;

                public class Foo {
                    private int x = 1;

                    public Foo(int x) {
                        this.x = x;
                    }

                    int max(int a, int b) {
                        if (a > b) {
                            return a;
                        } else {
                            return b;
                        }
                    }

                    void loop(List<String> items) {
                        for (String item : items) {
                            System.out.println(item);
                        }
                        try {
                            max(1, 2);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        } finally {
                            x = 0;
                        }
                    }
                }
                """;
        assertEquals(expected, formatter.format(messy).orElseThrow());
    }

    @Test
    void switchLabelsAlignWithSwitchAsInTheConventions() {
        String formatted = format("class A{void f(int x){switch(x){case 1:g();break;default:break;}}void g(){}}");
        assertTrue(formatted.contains("""
                        switch (x) {
                        case 1:
                            g();
                            break;
                        default:
                            break;
                        }
                """), formatted);
    }

    @Test
    void longLinesAreWrappedAt80ColumnsWithAnEightSpaceContinuation() {
        String formatted = format("class A{String s=String.format(\"%s %s %s %s\",\"aaaaaaaaaaaaaaaa\","
                + "\"bbbbbbbbbbbbbbbbbbb\",\"cccccccccccccccccc\",\"dddddddddddd\");}");
        assertTrue(formatted.lines().allMatch(line -> line.length() <= JavaFormatter.LINE_LENGTH), formatted);
        assertTrue(formatted.contains("\n            \"bbbbbbbbbbbbbbbbbbb\""), "continuation = 4 (member) + 8:\n" + formatted);
    }

    @Test
    void indentsWithSpacesNeverTabs() {
        String formatted = format("class A {\n\tvoid f() {\n\t\tint x = 1;\n\t}\n}\n");
        assertFalse(formatted.contains("\t"), formatted);
        assertTrue(formatted.contains("\n        int x = 1;\n"), formatted);
    }

    @Test
    void commentsAreKeptAndJavadocIsTidied() {
        String formatted = format("""
                class A {
                // line comment
                /** Does   things.
                * @param x the input */
                void f(int x){ /* inline */ }
                }
                """);
        assertTrue(formatted.contains("    // line comment\n"), formatted);
        assertTrue(formatted.contains("""
                    /**
                     * Does things.
                     *
                     * @param x the input
                     */
                """), formatted);
        assertTrue(formatted.lines().noneMatch(line -> line.endsWith(" ")), "no trailing spaces:\n" + formatted);
        assertTrue(formatted.contains("/* inline */"), formatted);
    }

    // ---- language level -----------------------------------------------------------------------

    @Test
    void understandsJava21Syntax() {
        String formatted = format("""
                sealed interface Shape permits Circle,Square{}
                record Circle(double r)implements Shape{}
                record Square(double side)implements Shape{}
                class Area{static double of(Shape s){return switch(s){case Circle c->Math.PI*c.r()*c.r();case Square(double side)->side*side;};}
                String text(Object o){if(o instanceof String str&&!str.isEmpty()){return str;}var block=\"""
                    keep
                      this\""";return block;}}
                """);
        assertTrue(formatted.contains("record Circle(double r) implements Shape {\n}"), formatted);
        assertTrue(formatted.contains("case Square(double side) -> side * side;"), formatted);
        assertTrue(formatted.contains("if (o instanceof String str && !str.isEmpty()) {"), formatted);
        assertTrue(formatted.contains("        keep\n                  this\"\"\""),
                "a text block moves as a whole, keeping the lines' indentation relative to each other:\n" + formatted);
    }

    // ---- stability ----------------------------------------------------------------------------

    @Test
    void formattingIsIdempotent() {
        String once = format("class A{int f(int a){if(a>0){return a;}return -a;}}");
        assertEquals(once, format(once));
    }

    @Test
    void alreadyFormattedSourceComesBackUnchanged() {
        String clean = """
                package p;

                public class Clean {
                    private final int value;

                    public Clean(int value) {
                        this.value = value;
                    }
                }
                """;
        assertEquals(clean, format(clean));
    }

    @Test
    void codeWithTypeErrorsStillFormatsBecauseFormattingIsSyntactic() {
        assertEquals("class A {\n    int x = \"not an int\" + undefined;\n}\n",
                format("class A{int x=\"not an int\"+undefined;}\n"));
    }

    // ---- what cannot be formatted -------------------------------------------------------------

    @Test
    void withSyntaxErrorsWhatParsesIsFormattedAndTheBrokenPartIsLeftAsWritten() {
        assertEquals("class A {\n    int x = ;\n}", format("class A { int x = ; }"));
        assertEquals("class A {\n    void f() { int x = 1 }\n}", format("class A { void f() { int x = 1 } }"));
    }

    @Test
    void textThatIsNotJavaIsReturnedUnchanged() {
        assertEquals("this is not java", format("this is not java"));
        assertEquals("}}}} class", format("}}}} class"));
    }

    @Test
    void hopelesslyIncompleteCodeGivesEmpty() {
        assertEquals(Optional.empty(), formatter.format("class A {\n void f(){\n  if(true){\n"));
    }

    @Test
    void formattingNeverChangesAnythingButWhitespace() {
        String messy = "class A{int f(int a,int b){String s=\"a  b\";char c=' ';return a+b;}// c  c\n}";
        assertEquals(messy.replaceAll("\\s", ""), format(messy).replaceAll("\\s", ""));
        assertTrue(format(messy).contains("\"a  b\""), "whitespace inside string literals is content");
    }

    @Test
    void emptyAndBlankSourcesAreHandled() {
        assertEquals("", format(""));
        assertTrue(format("\n\n").isBlank());
    }

    // ---- formatting a region ------------------------------------------------------------------

    @Test
    void regionFormattingLeavesTheRestUntouched() {
        String source = """
                class A {
                    void   untouchedBefore( ){ }
                    void target(int a){if(a>0){a++;}}
                    void   untouchedAfter( ){ }
                }
                """;
        int start = source.indexOf("void target");
        int end = source.indexOf('\n', start);

        String formatted = formatter.format(source, start, end - start).orElseThrow();

        assertTrue(formatted.contains("void   untouchedBefore( ){ }"), formatted);
        assertTrue(formatted.contains("void   untouchedAfter( ){ }"), formatted);
        assertTrue(formatted.contains("""
                    void target(int a) {
                        if (a > 0) {
                            a++;
                        }
                    }
                """), formatted);
    }

    @Test
    void regionOutsideTheSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> formatter.format("class A {}", 5, 50));
        assertThrows(IllegalArgumentException.class, () -> formatter.format("class A {}", -1, 2));
    }

    private String format(String source) {
        return formatter.format(source).orElseThrow(() -> new AssertionError("could not format:\n" + source));
    }
}
