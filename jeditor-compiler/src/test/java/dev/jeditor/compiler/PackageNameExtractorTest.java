package dev.jeditor.compiler;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackageNameExtractorTest {

    @Test
    void simplePackage() {
        assertEquals(Optional.of("com.example.app"), PackageNameExtractor.extract("package com.example.app;\nclass A {}"));
    }

    @Test
    void unnamedPackage() {
        assertEquals(Optional.empty(), PackageNameExtractor.extract("import java.util.List;\nclass A {}"));
        assertEquals(Optional.empty(), PackageNameExtractor.extract(""));
    }

    @Test
    void skipsLeadingComments() {
        String source = """
                /*
                 * Licence header mentioning package fake.one;
                 */
                // package fake.two;
                package real.pkg;
                class A {}
                """;
        assertEquals(Optional.of("real.pkg"), PackageNameExtractor.extract(source));
    }

    @Test
    void skipsPackageAnnotations() {
        String source = """
                @Deprecated
                @SuppressWarnings("all")
                package annotated.pkg;
                """;
        assertEquals(Optional.of("annotated.pkg"), PackageNameExtractor.extract(source));
    }

    @Test
    void toleratesOddWhitespace() {
        assertEquals(Optional.of("a.b.c"), PackageNameExtractor.extract("  package  a . b\n.c ;"));
    }

    @Test
    void ignoresTheWordPackageLaterInTheFile() {
        String source = """
                class A {
                    String s = "package not.this;";
                }
                """;
        assertEquals(Optional.empty(), PackageNameExtractor.extract(source));
    }

    @Test
    void incompleteDeclarationIsNotAPackage() {
        assertEquals(Optional.empty(), PackageNameExtractor.extract("package com.example"));
    }
}
