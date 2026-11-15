package dev.jeditor.compiler;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the declaration of the name at a source offset, on an already attributed compilation unit.
 * Two steps: locate the syntax-tree node of the name under the offset, then ask the compiler which
 * symbol it resolved that node to and where that symbol's own tree is.
 */
final class DeclarationFinder {

    private final Trees trees;
    private final SourcePositions positions;

    DeclarationFinder(Trees trees) {
        this.trees = trees;
        this.positions = trees.getSourcePositions();
    }

    Optional<DeclarationTarget> find(CompilationUnitTree unit, int offset) {
        TreePath reference = referenceAt(unit, offset);
        if (reference == null && offset > 0) {
            reference = referenceAt(unit, offset - 1); // caret sitting just after the name
        }
        if (reference == null) {
            return Optional.empty();
        }
        Element element = trees.getElement(reference);
        if (element == null
                || element.getKind() == ElementKind.PACKAGE
                || element.getKind() == ElementKind.MODULE
                || element.asType().getKind() == TypeKind.ERROR      // unresolved: the compiler does not know either
                || element.getSimpleName().contentEquals("this")
                || element.getSimpleName().contentEquals("super")) {
            return Optional.empty();
        }
        return Optional.of(locate(element));
    }

    /** The innermost name-like node (identifier, {@code a.b} member name, {@code A::b} member name) under the offset. */
    private TreePath referenceAt(CompilationUnitTree unit, int offset) {
        TreePath[] found = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                if (covers(node, 0)) {
                    found[0] = getCurrentPath();
                }
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                // In "a.b.C" only the trailing name belongs to this node; "a.b" is its child.
                if (covers(node, node.getIdentifier().length())) {
                    found[0] = getCurrentPath();
                }
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                if (covers(node, node.getName().length())) {
                    found[0] = getCurrentPath();
                }
                return super.visitMemberReference(node, unused);
            }

            /** @param trailing if positive, only the last {@code trailing} characters of the node count */
            private boolean covers(Tree node, int trailing) {
                long start = positions.getStartPosition(unit, node);
                long end = positions.getEndPosition(unit, node);
                if (start == Diagnostic.NOPOS || end == Diagnostic.NOPOS) {
                    return false; // compiler-generated node, not something the user can click
                }
                if (trailing > 0) {
                    start = Math.max(start, end - trailing);
                }
                return offset >= start && offset < end;
            }
        }.scan(unit, null);
        return found[0];
    }

    private DeclarationTarget locate(Element element) {
        Element target = element;
        TreePath declaration = trees.getPath(target);
        if (declaration == null && target.getKind() == ElementKind.CONSTRUCTOR) {
            // "new Foo()" with an implicit default constructor: the class is the best destination.
            target = target.getEnclosingElement();
            declaration = trees.getPath(target);
        }
        String displayName = target instanceof TypeElement type
                ? type.getQualifiedName().toString()
                : simpleNameOf(target);
        if (declaration == null) {
            return new DeclarationTarget(displayName, Optional.empty(), -1); // only a class file exists
        }
        CompilationUnitTree unit = declaration.getCompilationUnit();
        URI uri = unit.getSourceFile().toUri();
        if (!"file".equals(uri.getScheme())) {
            return new DeclarationTarget(displayName, Optional.empty(), -1);
        }
        int start = (int) Math.max(0, positions.getStartPosition(unit, declaration.getLeaf()));
        int end = (int) positions.getEndPosition(unit, declaration.getLeaf());
        return new DeclarationTarget(displayName, Optional.of(Path.of(uri)), nameOffset(unit, start, end, simpleNameOf(target)));
    }

    private static String simpleNameOf(Element element) {
        return element.getKind() == ElementKind.CONSTRUCTOR
                ? element.getEnclosingElement().getSimpleName().toString()
                : element.getSimpleName().toString();
    }

    /**
     * A declaration's tree starts at its modifiers/annotations; land on the declared name instead,
     * which is the first whole-word occurrence of it at or after the keyword/type that precedes it.
     */
    private static int nameOffset(CompilationUnitTree unit, int start, int end, String name) {
        try {
            CharSequence content = unit.getSourceFile().getCharContent(true);
            int limit = end > start ? Math.min(end, content.length()) : content.length();
            Matcher matcher = Pattern.compile("(?<![\\p{L}\\p{N}_$@.])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_$])")
                    .matcher(content).region(Math.min(start, limit), limit);
            if (matcher.find()) {
                return matcher.start();
            }
        } catch (IOException e) {
            // fall back to the start of the declaration
        }
        return start;
    }
}
