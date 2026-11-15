package dev.jeditor.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Writes the editor's warnings and errors to a file per day, {@code errors-2026-11-15.log}, in the
 * editor's private folder, so that a problem that was handled quietly, or an exception nobody
 * expected, can be looked up afterwards.
 *
 * <p>Code throughout the project reports problems through the JDK's own logging facade
 * ({@link System.Logger}); this class is the one place that decides where they end up. It also
 * catches exceptions that no code handled, on any thread, which would otherwise only reach a
 * console nobody is looking at.
 *
 * <p>Writing the log can never fail the operation being logged: if the file cannot be written,
 * the entry is dropped.
 */
final class ErrorLog extends Handler {

    /** Every class of the project logs under this name prefix (its package). */
    static final String ROOT_LOGGER = "dev.jeditor";

    /** Log files older than this are deleted when the editor starts. */
    static final int DAYS_KEPT = 14;

    /** The same problem recurring within this time is written once, not once per occurrence. */
    static final Duration REPEAT_WINDOW = Duration.ofMinutes(1);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final System.Logger UNCAUGHT = System.getLogger(ROOT_LOGGER + ".uncaught");

    private final Path directory;
    private final Clock clock;
    private final Map<String, Instant> lastWritten = new HashMap<>();
    private Consumer<String> onUnexpectedError = message -> { };

    /**
     * @param directory where the log files go; created when the first entry is written
     * @param clock     source of the date (file name) and time (entries)
     */
    ErrorLog(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
        setLevel(Level.WARNING);
    }

    /**
     * Starts logging for the whole application into {@code ~/.jeditor}: attaches to the project's
     * loggers, catches unhandled exceptions on every thread, and removes old log files.
     */
    static ErrorLog install() {
        ErrorLog log = new ErrorLog(Path.of(System.getProperty("user.home"), RecentFiles.SETTINGS_DIRECTORY), Clock.systemDefaultZone());
        log.attach();
        log.deleteOldFiles();
        return log;
    }

    /** Routes the project's log records, and exceptions no code caught, to this log. */
    void attach() {
        Logger root = Logger.getLogger(ROOT_LOGGER);
        root.addHandler(this);
        ATTACHED_ROOT = root; // java.util.logging holds loggers weakly; keep ours alive
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            UNCAUGHT.log(System.Logger.Level.ERROR, "Unexpected error on thread '" + thread.getName() + "'", error);
            onUnexpectedError.accept(String.valueOf(error));
        });
    }

    private static Logger ATTACHED_ROOT;

    /** Called (on whatever thread failed) with a one-line description each time an unhandled exception is logged. */
    void onUnexpectedError(Consumer<String> listener) {
        this.onUnexpectedError = listener;
    }

    /** Today's log file; it exists only once something has been logged today. */
    Path todaysFile() {
        return fileFor(LocalDate.now(clock));
    }

    Path directory() {
        return directory;
    }

    private Path fileFor(LocalDate date) {
        return directory.resolve("errors-" + date + ".log");
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        try {
            Instant now = clock.instant();
            String key = record.getLoggerName() + '|' + record.getMessage() + '|'
                    + (record.getThrown() == null ? "" : record.getThrown().toString());
            Instant previous = lastWritten.get(key);
            if (previous != null && Duration.between(previous, now).compareTo(REPEAT_WINDOW) < 0) {
                return; // e.g. the same unreadable folder met on every keystroke's re-check
            }
            lastWritten.put(key, now);
            lastWritten.values().removeIf(time -> Duration.between(time, now).compareTo(REPEAT_WINDOW) >= 0 && !time.equals(now));

            Files.createDirectories(directory);
            Files.writeString(todaysFile(), format(record), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // Logging must never break what is being logged; with no log to write to, the entry is lost.
        }
    }

    private String format(LogRecord record) {
        StringWriter text = new StringWriter();
        text.append(LocalDateTime.now(clock).format(TIMESTAMP))
                .append(' ').append(record.getLevel().intValue() >= Level.SEVERE.intValue() ? "ERROR  " : "WARNING")
                .append(" [").append(Thread.currentThread().getName()).append("] ")
                .append(shortName(record.getLoggerName())).append('\n')
                .append("    ").append(String.valueOf(record.getMessage())).append('\n');
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace, true));
            trace.toString().lines().forEach(line -> text.append("    ").append(line).append('\n'));
        }
        return text.append('\n').toString();
    }

    private static String shortName(String loggerName) {
        return loggerName == null ? "?" : loggerName.substring(loggerName.lastIndexOf('.') + 1);
    }

    /** Deletes log files older than {@link #DAYS_KEPT} days; other files in the folder are never touched. */
    void deleteOldFiles() {
        LocalDate oldestKept = LocalDate.now(clock).minusDays(DAYS_KEPT);
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.matches("errors-\\d{4}-\\d{2}-\\d{2}\\.log")) {
                    try {
                        if (LocalDate.parse(name.substring("errors-".length(), name.length() - ".log".length())).isBefore(oldestKept)) {
                            Files.deleteIfExists(file);
                        }
                    } catch (RuntimeException | IOException e) {
                        // a file that looks like a log but is not, or cannot be deleted: leave it
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // no folder yet, or unreadable: nothing to clean up
        }
    }

    @Override
    public void flush() {
        // every entry is written through immediately
    }

    @Override
    public void close() {
        // no resources held between entries
    }
}
