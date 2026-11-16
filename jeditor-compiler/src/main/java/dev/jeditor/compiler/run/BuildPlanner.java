package dev.jeditor.compiler.run;

import dev.jeditor.compiler.ProjectLayout;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Decides what a build compiles and where the class files go.
 *
 * <ul>
 *   <li><b>Maven projects</b> (a {@code pom.xml} is present): the usual Maven places, so the result
 *       is where Maven and other tools expect it — {@code src/main/java} into {@code target/classes},
 *       and a single test source into {@code target/test-classes}.</li>
 *   <li><b>Plain folders</b>: every {@code .java} file in the folder into {@code <folder>/out}.</li>
 * </ul>
 */
public final class BuildPlanner {

    /** Directories never searched for sources in a plain folder: build outputs and tool metadata. */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("out", "target", "build", "bin", "node_modules");

    /**
     * One compiler invocation.
     *
     * @param name      what is being built, for messages: a module name, a file name
     * @param sources   the files to compile
     * @param layout    where the classes they depend on are found
     * @param outputDir where class files are written
     * @param wholeUnit true when {@code sources} is everything that belongs in {@code outputDir}, so
     *                  classes of other source trees must not be written there as a side effect
     */
    public record BuildTarget(String name, List<Path> sources, ProjectLayout layout, Path outputDir, boolean wholeUnit) {
        public BuildTarget {
            sources = List.copyOf(sources);
        }
    }

    private BuildPlanner() {
    }

    /**
     * Compiling one file. The classes it uses are compiled along with it when their source is found.
     *
     * @param projectFolder the opened folder; may be null
     */
    public static BuildTarget forFile(Path file, String source, Path projectFolder) {
        Path absolute = file.toAbsolutePath().normalize();
        ProjectLayout layout = ProjectLayout.detect(absolute, source, projectFolder);
        Path module = mavenModuleOf(absolute);
        Path output;
        if (module != null) {
            boolean test = absolute.startsWith(module.resolve("src/test"));
            output = module.resolve(test ? "target/test-classes" : "target/classes");
        } else {
            Path base = projectFolder != null && absolute.startsWith(projectFolder.toAbsolutePath().normalize())
                    ? projectFolder.toAbsolutePath().normalize()
                    : absolute.getParent();
            output = base.resolve("out");
        }
        return new BuildTarget(absolute.getFileName().toString(), List.of(absolute), layout, output, false);
    }

    /**
     * Compiling a whole folder: one target per Maven module that has main sources (the folder
     * itself and its direct child modules), or a single target for a plain folder. Empty when
     * there is nothing to compile.
     */
    public static List<BuildTarget> forProject(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        List<BuildTarget> targets = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            List<Path> modules = new ArrayList<>();
            modules.add(root);
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .filter(child -> Files.isRegularFile(child.resolve("pom.xml")))
                        .sorted()
                        .forEach(modules::add);
            }
            for (Path module : modules) {
                Path main = module.resolve("src/main/java");
                List<Path> sources = Files.isDirectory(main) ? javaFilesUnder(main, false) : List.of();
                if (!sources.isEmpty()) {
                    ProjectLayout layout = ProjectLayout.detect(sources.get(0), Files.readString(sources.get(0)), root);
                    targets.add(new BuildTarget(module.getFileName().toString(), sources, layout,
                            module.resolve("target/classes"), true));
                }
            }
            return targets;
        }
        List<Path> sources = javaFilesUnder(root, true);
        if (!sources.isEmpty()) {
            // Every source is passed explicitly, so no source path is needed; only libraries matter.
            ProjectLayout detected = ProjectLayout.detect(sources.get(0), Files.readString(sources.get(0)), root);
            ProjectLayout layout = new ProjectLayout(List.of(), detected.classpath());
            targets.add(new BuildTarget(root.getFileName().toString(), sources, layout, root.resolve("out"), true));
        }
        return targets;
    }

    private static List<Path> javaFilesUnder(Path directory, boolean skipOutputDirectories) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean skip = !dir.equals(directory)
                        && (name.startsWith(".") || (skipOutputDirectories && SKIPPED_DIRECTORIES.contains(name)));
                return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().endsWith(".java")) {
                    result.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(null);
        return result;
    }

    private static Path mavenModuleOf(Path file) {
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
        }
        return null;
    }
}
