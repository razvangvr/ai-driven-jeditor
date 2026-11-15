package dev.jeditor.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorLogTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-15T10:30:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final System.Logger LOG = System.getLogger("dev.jeditor.ui.ErrorLogTest");

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private ErrorLog log;
    private Thread.UncaughtExceptionHandler previousHandler;

    @BeforeEach
    void attach() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        log = new ErrorLog(dir.resolve(".jeditor"), clock);
        log.attach();
    }

    @AfterEach
    void detach() {
        Logger.getLogger(ErrorLog.ROOT_LOGGER).removeHandler(log);
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    @Test
    void nothingIsWrittenUntilSomethingGoesWrong() {
        LOG.log(System.Logger.Level.INFO, "just information");
        LOG.log(System.Logger.Level.DEBUG, "details");
        assertFalse(Files.exists(dir.resolve(".jeditor")));
    }

    @Test
    void warningsGoToAFileNamedAfterTheDayWithTheFullStackTrace() throws IOException {
        LOG.log(System.Logger.Level.WARNING, "Could not move pkg to the trash",
                new IllegalStateException("outer", new IOException("disk says no")));

        Path file = dir.resolve(".jeditor/errors-2026-11-15.log");
        assertEquals(file, log.todaysFile());
        String text = Files.readString(file);
        assertTrue(text.startsWith("2026-11-15 10:30:00.000 WARNING ["), text);
        assertTrue(text.contains("] ErrorLogTest\n"), text);
        assertTrue(text.contains("    Could not move pkg to the trash\n"), text);
        assertTrue(text.contains("java.lang.IllegalStateException: outer"), text);
        assertTrue(text.contains("Caused by: java.io.IOException: disk says no"), text);
        assertTrue(text.contains("at dev.jeditor.ui.ErrorLogTest."), "stack frames included:\n" + text);
    }

    @Test
    void errorsAreMarkedAsSuchAndAMessageAloneIsEnough() throws IOException {
        LOG.log(System.Logger.Level.ERROR, "something broke");
        String text = Files.readString(log.todaysFile());
        assertTrue(text.contains(" ERROR   ["), text);
        assertTrue(text.contains("    something broke\n"), text);
    }

    @Test
    void entriesAccumulateAndANewDayStartsANewFile() throws IOException {
        LOG.log(System.Logger.Level.WARNING, "first");
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        LOG.log(System.Logger.Level.WARNING, "second");
        clock.now = clock.now.plus(Duration.ofDays(1));
        LOG.log(System.Logger.Level.WARNING, "next day");

        String day1 = Files.readString(dir.resolve(".jeditor/errors-2026-11-15.log"));
        assertTrue(day1.indexOf("first") < day1.indexOf("second"), day1);
        assertFalse(day1.contains("next day"));
        assertTrue(Files.readString(dir.resolve(".jeditor/errors-2026-11-16.log")).contains("next day"));
    }

    @Test
    void theSameProblemRepeatingQuicklyIsWrittenOnce() throws IOException {
        for (int i = 0; i < 50; i++) {
            LOG.log(System.Logger.Level.WARNING, "Cannot list folder", new IOException("denied"));
            clock.now = clock.now.plusSeconds(1);
        }
        LOG.log(System.Logger.Level.WARNING, "A different problem", new IOException("denied"));

        String text = Files.readString(log.todaysFile());
        assertEquals(1, count(text, "Cannot list folder"), text);
        assertEquals(1, count(text, "A different problem"));

        clock.now = clock.now.plus(ErrorLog.REPEAT_WINDOW);
        LOG.log(System.Logger.Level.WARNING, "Cannot list folder", new IOException("denied"));
        assertEquals(2, count(Files.readString(log.todaysFile()), "Cannot list folder"), "reported again once the window has passed");
    }

    @Test
    void exceptionsNobodyCaughtAreLoggedAndAnnounced() throws Exception {
        List<String> announced = new ArrayList<>();
        log.onUnexpectedError(announced::add);

        Thread thread = new Thread(() -> {
            throw new IllegalStateException("nobody expected this");
        }, "worker-7");
        thread.start();
        thread.join();

        String text = Files.readString(log.todaysFile());
        assertTrue(text.contains(" ERROR   [worker-7] uncaught"), text);
        assertTrue(text.contains("Unexpected error on thread 'worker-7'"), text);
        assertTrue(text.contains("java.lang.IllegalStateException: nobody expected this"), text);
        assertEquals(List.of("java.lang.IllegalStateException: nobody expected this"), announced);
    }

    @Test
    void anUnwritableLogNeverBreaksTheCaller() throws IOException {
        Files.writeString(dir.resolve(".jeditor"), "a file where the folder should be");
        LOG.log(System.Logger.Level.ERROR, "cannot be written anywhere", new RuntimeException("x"));
        // reaching this line is the assertion
        assertTrue(Files.isRegularFile(dir.resolve(".jeditor")));
    }

    @Test
    void oldLogsAreDeletedButNothingElseIs() throws IOException {
        Path folder = Files.createDirectories(dir.resolve(".jeditor"));
        Path old = Files.writeString(folder.resolve("errors-2026-10-31.log"), "old");
        Path boundary = Files.writeString(folder.resolve("errors-2026-11-01.log"), "exactly 14 days");
        Path recent = Files.writeString(folder.resolve("errors-2026-11-14.log"), "recent");
        Path settings = Files.writeString(folder.resolve("recent-files.txt"), "keep");
        Path lookalike = Files.writeString(folder.resolve("errors-2020-99-99.log"), "not a date");
        Path other = Files.writeString(folder.resolve("errors-notes.log"), "keep");

        log.deleteOldFiles();

        assertFalse(Files.exists(old));
        for (Path kept : List.of(boundary, recent, settings, lookalike, other)) {
            assertTrue(Files.exists(kept), kept.toString());
        }
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
