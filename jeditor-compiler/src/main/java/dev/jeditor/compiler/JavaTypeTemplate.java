package dev.jeditor.compiler;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Plans a new Java source file: where it goes, which package it declares, and its starting content.
 * Nothing is written to disk here.
 */
public final class JavaTypeTemplate {

    public enum Kind {
        CLASS("class"), INTERFACE("interface"), ENUM("enum"), RECORD("record"), ANNOTATION("@interface");

        private final String keyword;

        Kind(String keyword) {
            this.keyword = keyword;
        }

        /** E.g. "Class", "Interface". */
        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    /**
     * @param file   the {@code .java} file to create
     * @param source its content
     */
    public record NewType(Path file, String source) {
    }

    private JavaTypeTemplate() {
    }

    /**
     * Plans a new type named {@code name} in {@code directory}.
     *
     * @param directory     the folder the user chose
     * @param projectFolder the opened folder, used as a last resort to work out the package; may be null
     * @param name          a simple name ({@code Invoice}) or a qualified one ({@code billing.model.Invoice}),
     *                      whose leading parts become sub-packages below {@code directory}; a trailing
     *                      {@code .java} is tolerated
     * @throws IllegalArgumentException with a message fit for the user, if the name is not usable
     */
    public static NewType plan(Path directory, Path projectFolder, String name, Kind kind) {
        String trimmed = name.strip();
        if (trimmed.endsWith(".java")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".java".length());
        }
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Enter a name.");
        }
        String[] parts = trimmed.split("\\.", -1);
        for (String part : parts) {
            if (!SourceVersion.isIdentifier(part) || SourceVersion.isKeyword(part)) {
                throw new IllegalArgumentException("'" + part + "' is not a valid Java name.");
            }
        }
        String typeName = parts[parts.length - 1];
        Path target = directory;
        List<String> packageParts = new ArrayList<>();
        String basePackage = packageOf(directory, projectFolder);
        if (!basePackage.isEmpty()) {
            packageParts.add(basePackage);
        }
        for (int i = 0; i < parts.length - 1; i++) {
            target = target.resolve(parts[i]);
            packageParts.add(parts[i]);
        }
        return new NewType(target.resolve(typeName + ".java"), render(kind, String.join(".", packageParts), typeName));
    }

    /** The starting content of a type; {@code packageName} may be empty for the unnamed package. */
    public static String render(Kind kind, String packageName, String typeName) {
        String header = packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
        String declaration = "public " + kind.keyword + " " + typeName + (kind == Kind.RECORD ? "()" : "");
        return header + declaration + " {\n}\n";
    }

    /**
     * The package that sources in {@code directory} should declare. Tried in order:
     * <ol>
     *   <li>what existing sources say: a {@code .java} file in the directory or an ancestor whose
     *       {@code package} declaration matches its location;</li>
     *   <li>Maven layout: the path below {@code src/main/java} or {@code src/test/java};</li>
     *   <li>the path below a directory named {@code src};</li>
     *   <li>the path below the project folder.</li>
     * </ol>
     * Returns the empty string (unnamed package) when none applies or the path is not a legal package name.
     */
    public static String packageOf(Path directory, Path projectFolder) {
        Path dir = directory.toAbsolutePath().normalize();
        Path project = projectFolder == null ? null : projectFolder.toAbsolutePath().normalize();

        // 1. Existing sources are the best evidence of where the source root is.
        for (Path ancestor = dir; ancestor != null; ancestor = ancestor.getParent()) {
            Optional<Path> root = sourceRootFromSourcesIn(ancestor);
            if (root.isPresent()) {
                return toPackage(root.get().relativize(dir));
            }
            if (ancestor.equals(project)) {
                break;
            }
        }
        // 2. and 3. Conventional source roots.
        for (String[] marker : new String[][] {{"src", "main", "java"}, {"src", "test", "java"}, {"src"}}) {
            for (int i = dir.getNameCount() - marker.length; i >= 0; i--) {
                if (matchesAt(dir, i, marker)) {
                    int after = i + marker.length;
                    return after == dir.getNameCount() ? "" : toPackage(dir.subpath(after, dir.getNameCount()));
                }
            }
        }
        // 4. Treat the opened folder as the source root.
        if (project != null && dir.startsWith(project)) {
            return toPackage(project.relativize(dir));
        }
        return "";
    }

    private static Optional<Path> sourceRootFromSourcesIn(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".java")).sorted().toList()) {
                Optional<String> declared = PackageNameExtractor.extract(Files.readString(file));
                Optional<Path> root = ProjectLayout.sourceRootOf(file, declared.orElse(""));
                if (root.isPresent()) {
                    return root;
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable directory or file: no evidence from here
        }
        return Optional.empty();
    }

    private static boolean matchesAt(Path path, int index, String[] names) {
        for (int i = 0; i < names.length; i++) {
            if (!path.getName(index + i).toString().equals(names[i])) {
                return false;
            }
        }
        return true;
    }

    private static String toPackage(Path relative) {
        if (relative.toString().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Path part : relative) {
            String name = part.toString();
            if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                return ""; // e.g. "my-app": cannot be part of a package name
            }
            parts.add(name);
        }
        return String.join(".", parts);
    }
}
