package dev.jeditor.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * A structural element of a Java source file (a type, method, field, ...), with the exact region
 * of the source it covers.
 *
 * @param kind        what kind of element this is
 * @param name        display name, e.g. {@code Foo}, {@code bar(int, String) : void}, {@code count : int}
 * @param startOffset 0-based offset of the first character of the element (inclusive)
 * @param endOffset   0-based offset just past the last character of the element (exclusive)
 * @param startLine   1-based line where the element starts
 * @param endLine     1-based line where the element ends
 * @param children    nested elements, in source order
 */
public record CodeBlock(
        Kind kind,
        String name,
        int startOffset,
        int endOffset,
        int startLine,
        int endLine,
        List<CodeBlock> children) {

    public enum Kind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION,
        METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT, INITIALIZER;

        public boolean isType() {
            return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD || this == ANNOTATION;
        }
    }

    public CodeBlock {
        children = List.copyOf(children);
    }

    public boolean contains(int offset) {
        return offset >= startOffset && offset < endOffset;
    }

    /**
     * The chain of nested blocks enclosing {@code offset}, outermost first; empty when the offset is
     * outside every block. Useful for "where am I" breadcrumbs.
     */
    public static List<CodeBlock> pathTo(List<CodeBlock> blocks, int offset) {
        List<CodeBlock> path = new ArrayList<>();
        List<CodeBlock> level = blocks;
        while (true) {
            CodeBlock hit = level.stream().filter(b -> b.contains(offset)).findFirst().orElse(null);
            if (hit == null) {
                return path;
            }
            path.add(hit);
            level = hit.children();
        }
    }
}
