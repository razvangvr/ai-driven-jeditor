package dev.jeditor.compiler;

import dev.jeditor.compiler.JavaTypeTemplate.Kind;
import dev.jeditor.compiler.JavaTypeTemplate.NewType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTypeTemplateTest {

    @TempDir
    Path project;

    // ---- content ------------------------------------------------------------------------------

    @Test
    void classSkeleton() {
        assertEquals("""
                package com.acme;

                public class Invoice {
                }
                """, JavaTypeTemplate.render(Kind.CLASS, "com.acme", "Invoice"));
    }

    @Test
    void unnamedPackageHasNoPackageLine() {
        assertEquals("public interface Shape {\n}\n", JavaTypeTemplate.render(Kind.INTERFACE, "", "Shape"));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void everyKindCompilesCleanly(Kind kind) {
        NewType type = JavaTypeTemplate.plan(project.resolve("src/com/acme"), project, "Thing", kind);
        AnalysisResult result = new JavaSourceAnalyzer().analyze(type.file(), type.source(), ProjectLayout.NONE);
        assertEquals(List.of(), result.diagnostics(), type.source());
        assertEquals(1, result.structure().size());
    }

    @Test
    void kindLabels() {
        assertEquals("Class", Kind.CLASS.label());
        assertEquals("Annotation", Kind.ANNOTATION.label());
    }

    // ---- where the file goes ------------------------------------------------------------------

    @Test
    void simpleNameGoesIntoTheChosenDirectory() {
        Path dir = project.resolve("src/main/java/com/acme");
        NewType type = JavaTypeTemplate.plan(dir, project, "Invoice", Kind.CLASS);
        assertEquals(dir.resolve("Invoice.java"), type.file());
        assertTrue(type.source().startsWith("package com.acme;\n"), type.source());
    }

    @Test
    void qualifiedNameCreatesSubPackages() {
        Path dir = project.resolve("src/main/java/com/acme");
        NewType type = JavaTypeTemplate.plan(dir, project, "billing.model.Invoice", Kind.RECORD);
        assertEquals(dir.resolve("billing/model/Invoice.java"), type.file());
        assertTrue(type.source().startsWith("package com.acme.billing.model;\n\npublic record Invoice() {"), type.source());
    }

    @Test
    void surroundingSpacesAndJavaSuffixAreTolerated() {
        NewType type = JavaTypeTemplate.plan(project, project, "  Main.java ", Kind.CLASS);
        assertEquals(project.resolve("Main.java"), type.file());
        assertEquals("public class Main {\n}\n", type.source());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "1Abc", "my-class", "class", "a..B", ".B", "B.", "has space", "pkg.int.Foo", ".java"})
    void unusableNamesAreRejectedWithAMessage(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JavaTypeTemplate.plan(project, project, name, Kind.CLASS));
        assertTrue(e.getMessage().length() > 5, e.getMessage());
    }

    // ---- package detection --------------------------------------------------------------------

    @Test
    void mavenLayout() {
        assertEquals("com.acme.app", JavaTypeTemplate.packageOf(project.resolve("src/main/java/com/acme/app"), project));
        assertEquals("com.acme", JavaTypeTemplate.packageOf(project.resolve("module/src/test/java/com/acme"), project));
        assertEquals("", JavaTypeTemplate.packageOf(project.resolve("src/main/java"), project));
    }

    @Test
    void plainSrcDirectory() {
        assertEquals("com.acme", JavaTypeTemplate.packageOf(project.resolve("src/com/acme"), project));
        assertEquals("", JavaTypeTemplate.packageOf(project.resolve("src"), project));
    }

    @Test
    void noConventionFallsBackToThePathBelowTheProjectFolder() {
        assertEquals("", JavaTypeTemplate.packageOf(project, project));
        assertEquals("util.text", JavaTypeTemplate.packageOf(project.resolve("util/text"), project));
    }

    @Test
    void existingSourcesOverrideConventions() throws IOException {
        // Sources live under "code/", an unconventional root, and say so in their package lines.
        write("code/org/demo/App.java", "package org.demo;\nclass App {}\n");

        assertEquals("org.demo", JavaTypeTemplate.packageOf(project.resolve("code/org/demo"), project));
        assertEquals("org.demo.sub.deep", JavaTypeTemplate.packageOf(project.resolve("code/org/demo/sub/deep"), project),
                "a new sub-package continues from the nearest ancestor that has sources");
    }

    @Test
    void sourcesWhosePackageDoesNotMatchTheirFolderAreIgnored() throws IOException {
        write("src/com/acme/Misplaced.java", "package somewhere.else;\nclass Misplaced {}\n");
        assertEquals("com.acme", JavaTypeTemplate.packageOf(project.resolve("src/com/acme"), project));
    }

    @Test
    void foldersThatCannotBePackageNamesGiveTheUnnamedPackage() {
        assertEquals("", JavaTypeTemplate.packageOf(project.resolve("my-notes/v1.2"), project));
        assertEquals("", JavaTypeTemplate.packageOf(project.resolve("src/main/java/new/stuff"), project), "'new' is a keyword");
    }

    @Test
    void directoryOutsideTheProjectAndNoProjectAtAll() {
        Path outside = project.resolveSibling("elsewhere-" + System.nanoTime());
        assertEquals("", JavaTypeTemplate.packageOf(outside, project));
        assertEquals("", JavaTypeTemplate.packageOf(outside, null));
        assertEquals("a.b", JavaTypeTemplate.packageOf(outside.resolve("src/a/b"), null));
    }

    private void write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
