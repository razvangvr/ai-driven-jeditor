package dev.jeditor.compiler.run;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Starts a compiled main class on a chosen {@link JavaRuntime}, as a separate process. */
public final class JavaLauncher {

    private JavaLauncher() {
    }

    /** The command line that runs {@code mainClass}: {@code <home>/bin/java -cp <classpath> <mainClass> <args>}. */
    public static List<String> command(JavaRuntime runtime, List<Path> classpath, String mainClass, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(runtime.javaExecutable().toString());
        // Make the program's output UTF-8 whatever the platform default, so the console can decode it.
        // (file.encoding covers Java <= 17, stdout/stderr.encoding cover 19+; unknown ones are ignored.)
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-cp");
        command.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        command.add(mainClass);
        command.addAll(arguments);
        return command;
    }

    /**
     * Starts the program with standard error merged into standard output.
     *
     * @param workingDirectory the program's current directory
     */
    public static Process start(JavaRuntime runtime, List<Path> classpath, String mainClass, List<String> arguments,
                                Path workingDirectory) throws IOException {
        return new ProcessBuilder(command(runtime, classpath, mainClass, arguments))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
    }
}
