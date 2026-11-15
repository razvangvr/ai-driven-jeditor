package dev.jeditor.compiler;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;

import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns a parsed compilation unit into a tree of {@link CodeBlock}s (types and their members).
 * Must run on a freshly <em>parsed</em> tree: later compiler phases add synthetic members
 * (e.g. default constructors) that have no place in an outline.
 */
final class CodeStructureScanner {

    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final LineMap lineMap;

    CodeStructureScanner(CompilationUnitTree unit, SourcePositions positions) {
        this.unit = unit;
        this.positions = positions;
        this.lineMap = unit.getLineMap();
    }

    List<CodeBlock> scan() {
        List<CodeBlock> result = new ArrayList<>();
        for (Tree declaration : unit.getTypeDecls()) {
            if (declaration instanceof ClassTree type) {
                addIfPositioned(result, type(type));
            }
        }
        return result;
    }

    private CodeBlock type(ClassTree type) {
        CodeBlock.Kind kind = switch (type.getKind()) {
            case INTERFACE -> CodeBlock.Kind.INTERFACE;
            case ENUM -> CodeBlock.Kind.ENUM;
            case RECORD -> CodeBlock.Kind.RECORD;
            case ANNOTATION_TYPE -> CodeBlock.Kind.ANNOTATION;
            default -> CodeBlock.Kind.CLASS;
        };
        String typeName = type.getSimpleName().toString();
        List<CodeBlock> children = new ArrayList<>();
        for (Tree member : type.getMembers()) {
            CodeBlock child = switch (member) {
                case ClassTree nested -> type(nested);
                case MethodTree method -> method(method, typeName);
                case VariableTree variable -> variable(variable, kind, typeName);
                case BlockTree block -> block(block, block.isStatic() ? "static {...}" : "{...}");
                default -> null;
            };
            addIfPositioned(children, child);
        }
        return block(type, kind, typeName, children);
    }

    private CodeBlock method(MethodTree method, String enclosingTypeName) {
        String parameters = method.getParameters().stream()
                .map(p -> String.valueOf(p.getType()))
                .collect(Collectors.joining(", ", "(", ")"));
        boolean constructor = method.getReturnType() == null;
        if (constructor) {
            // A compact record constructor has no parameter list of its own in the source.
            return block(method, CodeBlock.Kind.CONSTRUCTOR, enclosingTypeName + parameters, List.of());
        }
        return block(method, CodeBlock.Kind.METHOD,
                method.getName() + parameters + " : " + method.getReturnType(), List.of());
    }

    private CodeBlock variable(VariableTree variable, CodeBlock.Kind enclosingKind, String enclosingTypeName) {
        String type = String.valueOf(variable.getType());
        boolean enumConstant = enclosingKind == CodeBlock.Kind.ENUM
                && variable.getInitializer() instanceof NewClassTree
                && type.equals(enclosingTypeName);
        if (enumConstant) {
            return block(variable, CodeBlock.Kind.ENUM_CONSTANT, variable.getName().toString(), List.of());
        }
        return block(variable, CodeBlock.Kind.FIELD, variable.getName() + " : " + type, List.of());
    }

    private CodeBlock block(BlockTree block, String name) {
        return block(block, CodeBlock.Kind.INITIALIZER, name, List.of());
    }

    private CodeBlock block(Tree tree, CodeBlock.Kind kind, String name, List<CodeBlock> children) {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start == Diagnostic.NOPOS || end == Diagnostic.NOPOS || end < start) {
            return null; // too broken to locate; the diagnostics will explain why
        }
        // 'end' is exclusive, so the element's last character is at end - 1.
        int startLine = (int) lineMap.getLineNumber(start);
        int endLine = (int) lineMap.getLineNumber(Math.max(start, end - 1));
        return new CodeBlock(kind, name, (int) start, (int) end, startLine, endLine, children);
    }

    private static void addIfPositioned(List<CodeBlock> target, CodeBlock block) {
        if (block != null) {
            target.add(block);
        }
    }
}
