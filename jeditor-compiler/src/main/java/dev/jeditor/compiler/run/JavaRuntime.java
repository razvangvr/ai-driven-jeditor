package dev.jeditor.compiler.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An installed Java runtime (JRE or JDK) that programs can be run on.
 *
 * @param home           the installation directory (the one containing {@code bin/java})
 * @param version        full version string, e.g. {@code 21.0.2} or {@code 1.8.0_392}
 * @param featureVersion the Java release number, e.g. 21 or 8
 * @param jdk            true if the installation also contains a compiler ({@code bin/javac})
 */
public record JavaRuntime(Path home, String version, int featureVersion, boolean jdk) {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final Pattern VERSION_OUTPUT = Pattern.compile("version \"([^\"]+)\"");

    /**
     * Inspects a directory and describes the Java installation in it, if there is one. Forgiving
     * about what is picked: the home itself, its {@code bin} directory, the {@code java} executable,
     * or a macOS bundle (whose home is under {@code Contents/Home}).
     */
    public static Optional<JavaRuntime> detect(Path location) {
        Path path = location.toAbsolutePath().normalize();
        if (Files.isRegularFile(path) && path.getParent() != null) {
            path = path.getParent(); // .../bin/java -> .../bin
        }
        if (path.getFileName() != null && path.getFileName().toString().equals("bin") && path.getParent() != null) {
            path = path.getParent();
        }
        if (!Files.isRegularFile(executable(path, "java")) && Files.isRegularFile(executable(path.resolve("Contents/Home"), "java"))) {
            path = path.resolve("Contents/Home");
        }
        if (!Files.isRegularFile(executable(path, "java"))) {
            return Optional.empty();
        }
        Path home = path;
        return readVersion(home).map(version -> new JavaRuntime(
                home, version, featureVersionOf(version), Files.isRegularFile(executable(home, "javac"))));
    }

    /** The runtime this application itself is running on. */
    public static Optional<JavaRuntime> current() {
        return detect(Path.of(System.getProperty("java.home")));
    }

    public Path javaExecutable() {
        return executable(home, "java");
    }

    /** E.g. {@code Java 21.0.2 (JDK)}. */
    public String displayName() {
        return "Java " + version + (jdk ? " (JDK)" : " (JRE)");
    }

    /** Maps a version string to its release number: {@code 1.8.0_392 -> 8}, {@code 21.0.2 -> 21}, {@code 17-ea -> 17}. */
    public static int featureVersionOf(String version) {
        String[] parts = version.split("[^0-9]+");
        try {
            if (parts.length == 0 || parts[0].isEmpty()) {
                return 0;
            }
            int first = Integer.parseInt(parts[0]);
            return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Path executable(Path home, String name) {
        return home.resolve("bin").resolve(WINDOWS ? name + ".exe" : name);
    }

    private static Optional<String> readVersion(Path home) {
        // Every modern JRE/JDK ships a 'release' file; reading it avoids starting a process.
        Path release = home.resolve("release");
        if (Files.isRegularFile(release)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(release)) {
                properties.load(in);
                String value = properties.getProperty("JAVA_VERSION");
                if (value != null) {
                    String version = value.replace("\"", "").trim();
                    if (featureVersionOf(version) > 0) {
                        return Optional.of(version);
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // fall through to asking the executable
            }
        }
        return askExecutable(home);
    }

    private static Optional<String> askExecutable(Path home) {
        try {
            Process process = new ProcessBuilder(executable(home, "java").toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 10_000) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            Matcher matcher = VERSION_OUTPUT.matcher(output);
            if (matcher.find() && featureVersionOf(matcher.group(1)) > 0) {
                return Optional.of(matcher.group(1));
            }
        } catch (IOException e) {
            // not runnable: not a usable runtime
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
