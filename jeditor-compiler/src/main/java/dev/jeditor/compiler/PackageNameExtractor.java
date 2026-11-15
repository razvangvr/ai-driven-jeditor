package dev.jeditor.compiler;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheaply reads the {@code package} declaration of a Java source without running the compiler.
 * Needed up-front, because the source root (and therefore the compiler's source path) is derived
 * from it.
 */
public final class PackageNameExtractor {

    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
    private static final Pattern PACKAGE = Pattern.compile(
            "\\A\\s*(?:@[\\w.]+(?:\\s*\\([^)]*\\))?\\s*)*package\\s+([\\p{L}\\p{N}_$]+(?:\\s*\\.\\s*[\\p{L}\\p{N}_$]+)*)\\s*;");

    private PackageNameExtractor() {
    }

    /** Returns the declared package, or empty for the unnamed package. */
    public static Optional<String> extract(String source) {
        // The package declaration can only be preceded by comments, whitespace and annotations,
        // so stripping comments with a regex is safe: no string literal can appear before it.
        String withoutComments = COMMENTS.matcher(source).replaceAll(" ");
        Matcher matcher = PACKAGE.matcher(withoutComments);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1).replaceAll("\\s+", ""));
    }
}
