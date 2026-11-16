package dev.jeditor.compiler.run;

import dev.jeditor.compiler.ProjectLayout;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything about one launch of a program, and a plain-language account of it: which class was
 * started, on which Java, with which classpath and why each entry is on it, the exact command,
 * and how to do the same by hand outside the editor.
 *
 * @param sourceFile       the file whose main class was run
 * @param mainClass        the class handed to {@code java}
 * @param runtime          the Java installation used
 * @param workingDirectory the program's current directory
 * @param projectFolder    the folder open in the editor; may be null
 * @param compiledClasses  the temporary folder the sources were compiled into for this run
 * @param classpath        the classpath in search order; its first entry is {@code compiledClasses}
 * @param command          the exact command line, one element per argument
 * @param buildOutput      where Build would put the classes ({@code out} or {@code target/classes}),
 *                         used to show how to run the program by hand
 * @param exitCode         the program's exit code; null while it is still running
 * @param duration         how long it ran; null while it is still running
 */
public record LaunchRecord(Path sourceFile, String mainClass, JavaRuntime runtime, Path workingDirectory,
                           Path projectFolder, Path compiledClasses, List<Path> classpath, List<String> command,
                           Path buildOutput, Integer exitCode, Duration duration) {

    public LaunchRecord {
        classpath = List.copyOf(classpath);
        command = List.copyOf(command);
    }

    /** The same launch, now finished. */
    public LaunchRecord finished(int code, Duration ran) {
        return new LaunchRecord(sourceFile, mainClass, runtime, workingDirectory, projectFolder, compiledClasses,
                classpath, command, buildOutput, code, ran);
    }

    /** The command as one line that can be pasted into a terminal. */
    public String commandLine() {
        return command.stream().map(LaunchRecord::quote).collect(Collectors.joining(" "));
    }

    /**
     * The command laid out one argument per line, so that a long classpath stays readable. On
     * Unix-like systems the lines end in a backslash, so the block can still be pasted into a
     * terminal as it is; elsewhere it is a single line.
     */
    String commandForDisplay(String indent) {
        if (File.separatorChar == '\\') {
            return indent + commandLine();
        }
        StringBuilder text = new StringBuilder(indent).append(quote(command.get(0)));
        for (int i = 1; i < command.size(); i++) {
            String argument = command.get(i);
            text.append(" \\\n").append(indent).append("    ").append(quote(argument));
            if (argument.equals("-cp") && i + 1 < command.size()) {
                text.append(' ').append(quote(command.get(++i))); // keep the option and its value together
            }
        }
        return text.toString();
    }

    /** The value given to {@code -cp}: the entries joined with the platform's path separator. */
    public String classpathString() {
        return classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    /**
     * The command that runs the same program from a terminal, without the editor: from the working
     * directory, against the build output instead of this run's temporary folder, and with
     * {@code folder/*} standing for all JARs of a library folder.
     */
    public String commandToRunByHand() {
        Set<String> entries = new LinkedHashSet<>();
        entries.add(relative(buildOutput));
        for (Path entry : classpath) {
            if (entry.equals(compiledClasses)) {
                continue;
            }
            if (isLibraryJar(entry)) {
                entries.add(relative(entry.getParent()) + File.separator + "*"); // standard java wildcard: every JAR in the folder
            } else {
                entries.add(relative(entry));
            }
        }
        return "java -cp " + quote(String.join(File.pathSeparator, entries)) + " " + mainClass;
    }

    /** The account shown to the user. Plain text, meant for a fixed-width font. */
    public String describe() {
        StringBuilder text = new StringBuilder();

        section(text, "MAIN CLASS");
        text.append("  ").append(mainClass).append('\n');
        text.append("  declared in ").append(relative(sourceFile)).append('\n');

        section(text, "JAVA RUNTIME");
        text.append("  ").append(runtime.displayName()).append('\n');
        text.append("  ").append(runtime.javaExecutable()).append('\n');

        section(text, "WORKING DIRECTORY");
        text.append("  ").append(workingDirectory).append('\n');

        section(text, "CLASSPATH  (" + classpath.size() + (classpath.size() == 1 ? " entry" : " entries")
                + ", searched in this order; the first one that has a class wins)");
        int position = 1;
        for (Path entry : classpath) {
            text.append("  ").append(position++).append(". ").append(entry).append('\n');
            text.append("       ").append(reason(entry)).append('\n');
        }

        section(text, "HOW THIS CLASSPATH WAS PUT TOGETHER");
        text.append("  Java does not look for libraries by itself, and has no rule about a \"lib\"\n");
        text.append("  folder. It loads classes only from what is listed after -cp. The editor wrote\n");
        text.append("  that list for this run:\n");
        text.append("    - it compiled your sources into a temporary folder and put it first;\n");
        List<Path> libraryFolders = libraryFoldersUsed();
        if (libraryFolders.isEmpty()) {
            text.append("    - it found no library JARs to add.");
            if (projectFolder != null) {
                text.append(" Put JARs in ").append(projectFolder.resolve("lib")).append("\n      and they will be added.");
            }
            text.append('\n');
        } else {
            for (Path folder : libraryFolders) {
                long jars = classpath.stream().filter(entry -> folder.equals(entry.getParent())).count();
                text.append("    - it added ").append(jars == 1 ? "the JAR" : "each of the " + jars + " JARs")
                        .append(" it found in ").append(folder).append(";\n");
            }
        }
        long others = classpath.stream().filter(entry -> !entry.equals(compiledClasses) && !isLibraryJar(entry)).count();
        if (others > 0) {
            text.append("    - it added ").append(others).append(others == 1 ? " other entry" : " other entries")
                    .append(" that belong").append(others == 1 ? "s" : "").append(" to the project (explained above).\n");
        }

        section(text, "EXACT COMMAND THE EDITOR RAN");
        text.append(commandForDisplay("  ")).append('\n');

        section(text, "TO RUN IT YOURSELF, WITHOUT THE EDITOR");
        text.append("  1. Build > Build Project   (compiles into ").append(relative(buildOutput)).append(")\n");
        text.append("  2. In a terminal:\n");
        text.append("       cd ").append(quote(workingDirectory.toString())).append('\n');
        text.append("       ").append(commandToRunByHand()).append('\n');
        if (!libraryFolders.isEmpty()) {
            text.append("     (\"").append(File.separator).append("*\" after a folder is standard Java for \"every JAR in it\".)\n");
        }

        section(text, "RESULT");
        if (exitCode == null) {
            text.append("  still running\n");
        } else {
            text.append("  exit code ").append(exitCode).append(exitCode == 0 ? " (success)" : " (failure)")
                    .append(String.format(Locale.ROOT, " after %.1f s%n", duration.toMillis() / 1000.0));
        }
        return text.toString();
    }

    /** Why an entry is on the classpath, in one sentence. */
    String reason(Path entry) {
        if (entry.equals(compiledClasses)) {
            return "your project's own classes, compiled just now from the sources (a temporary folder)";
        }
        if (isLibraryJar(entry)) {
            return "a library: every .jar in the \"" + entry.getParent().getFileName() + "\" folder is added automatically";
        }
        return ClasspathInspector.originOf(entry);
    }

    private boolean isLibraryJar(Path entry) {
        Path parent = entry.getParent();
        return parent != null && parent.getFileName() != null && !Files.isDirectory(entry)
                && ProjectLayout.LIBRARY_FOLDER_NAMES.contains(parent.getFileName().toString());
    }

    private List<Path> libraryFoldersUsed() {
        Set<Path> folders = new LinkedHashSet<>();
        classpath.stream().filter(this::isLibraryJar).forEach(entry -> folders.add(entry.getParent()));
        return new ArrayList<>(folders);
    }

    /** Relative to the working directory when inside it, which is how one would type it there. */
    private String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path base = workingDirectory.toAbsolutePath().normalize();
        return absolute.startsWith(base) && !absolute.equals(base) ? base.relativize(absolute).toString() : absolute.toString();
    }

    private static void section(StringBuilder text, String title) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(title).append('\n');
    }

    /** Quotes an argument for a shell if it contains anything a shell would act on. */
    static String quote(String argument) {
        if (!argument.isEmpty() && argument.chars().allMatch(c -> Character.isLetterOrDigit(c) || "-_./:=+,@%".indexOf(c) >= 0)) {
            return argument;
        }
        boolean windows = File.separatorChar == '\\';
        return windows ? "\"" + argument.replace("\"", "\\\"") + "\"" : "'" + argument.replace("'", "'\\''") + "'";
    }
}
