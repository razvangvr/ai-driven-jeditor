package dev.jeditor.format;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reformats Java source according to the standard Java code conventions (the Sun/Oracle "Code
 * Conventions for the Java Programming Language"): 4-space indentation, opening brace at the end
 * of the line, one statement per line, lines wrapped at 80 columns.
 *
 * <p>The work is done by the Eclipse JDT code formatter with its built-in "Java Conventions"
 * profile; this class only configures and calls it. The one deviation from that profile is that
 * indentation is written with spaces only, never tabs, which the conventions leave open.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class JavaFormatter {

    private static final System.Logger LOG = System.getLogger(JavaFormatter.class.getName());

    /** Width of one indentation level, in spaces. */
    public static final int INDENT = 4;
    /** Lines longer than this are wrapped. */
    public static final int LINE_LENGTH = 80;

    private static final String JAVA_VERSION = "21";

    private final Map<String, String> options;

    public JavaFormatter() {
        @SuppressWarnings("unchecked")
        Map<String, String> conventions = DefaultCodeFormatterConstants.getJavaConventionsSettings();
        // The language level the formatter's parser accepts (records, pattern switch, text blocks...).
        conventions.put(JavaCore.COMPILER_SOURCE, JAVA_VERSION);
        conventions.put(JavaCore.COMPILER_COMPLIANCE, JAVA_VERSION);
        conventions.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JAVA_VERSION);
        conventions.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
        conventions.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, String.valueOf(INDENT));
        conventions.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, String.valueOf(INDENT));
        conventions.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, String.valueOf(LINE_LENGTH));
        this.options = Map.copyOf(conventions);
    }

    private static final Pattern TRAILING_SPACE_IN_COMMENT = Pattern.compile("(?m)^([ \\t]*\\*)[ \\t]+$");

    /**
     * Formats a whole source file.
     *
     * <p>Formatting is purely syntactic, so code that does not compile because of type errors
     * formats normally. With syntax errors the formatter does what it can: declarations it can
     * still recognise are formatted and the broken part is left as written.
     *
     * @return empty if the source is too broken to format at all
     */
    public Optional<String> format(String source) {
        // The formatter leaves a trailing space on the empty " * " lines it adds to Javadoc; drop it.
        return format(source, 0, source.length())
                .map(formatted -> TRAILING_SPACE_IN_COMMENT.matcher(formatted).replaceAll("$1"));
    }

    /**
     * Formats the part of {@code source} between {@code offset} and {@code offset + length} and
     * returns the complete new source; text outside that region is left exactly as it is.
     *
     * @return empty if the source is too broken to format at all; the unchanged source if the
     *         region already follows the conventions
     * @throws IllegalArgumentException if the region is outside the source
     */
    public Optional<String> format(String source, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > source.length()) {
            throw new IllegalArgumentException("Region " + offset + "+" + length + " is outside the source (" + source.length() + ")");
        }
        CodeFormatter formatter = ToolFactory.createCodeFormatter(options);
        TextEdit edit;
        try {
            edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                    source, offset, length, 0, "\n");
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "The formatter failed on the source it was given", e);
            return Optional.empty();
        }
        if (edit == null) {
            return Optional.empty();
        }
        IDocument document = new Document(source);
        try {
            edit.apply(document);
        } catch (BadLocationException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "The formatter's edits could not be applied to the source", e);
            return Optional.empty();
        }
        return Optional.of(document.get());
    }
}
