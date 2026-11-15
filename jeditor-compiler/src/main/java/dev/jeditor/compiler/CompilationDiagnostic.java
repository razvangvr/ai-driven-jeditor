package dev.jeditor.compiler;

import java.util.Objects;

/**
 * A single problem reported by the compiler for the analysed source.
 *
 * @param severity    how serious the problem is
 * @param message     human-readable description (may span several lines)
 * @param line        1-based line of the problem
 * @param column      1-based column (in characters) of the problem
 * @param startOffset 0-based character offset where the problem starts (inclusive)
 * @param endOffset   0-based character offset where the problem ends (exclusive); always
 *                    {@code > startOffset} unless the source is empty
 * @param code        the compiler's diagnostic key, e.g. {@code compiler.err.cant.resolve.location}
 */
public record CompilationDiagnostic(
        Severity severity,
        String message,
        int line,
        int column,
        int startOffset,
        int endOffset,
        String code) {

    public CompilationDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public int length() {
        return endOffset - startOffset;
    }

    /** First line of the message, convenient for tables and status bars. */
    public String summary() {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline).stripTrailing();
    }
}
