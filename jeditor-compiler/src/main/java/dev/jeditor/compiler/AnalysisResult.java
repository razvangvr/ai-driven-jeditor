package dev.jeditor.compiler;

import java.util.List;

/**
 * Outcome of analysing one source file.
 *
 * @param diagnostics problems found in the analysed file, sorted by position
 * @param structure   top-level types of the file with their members
 * @param mainClasses fully qualified (binary) names of the classes in the file that declare a
 *                    {@code public static void main(String[])} method, in source order
 */
public record AnalysisResult(List<CompilationDiagnostic> diagnostics, List<CodeBlock> structure, List<String> mainClasses) {

    public static final AnalysisResult EMPTY = new AnalysisResult(List.of(), List.of(), List.of());

    public AnalysisResult {
        diagnostics = List.copyOf(diagnostics);
        structure = List.copyOf(structure);
        mainClasses = List.copyOf(mainClasses);
    }

    public boolean hasErrors() {
        return count(Severity.ERROR) > 0;
    }

    public long count(Severity severity) {
        return diagnostics.stream().filter(d -> d.severity() == severity).count();
    }
}
