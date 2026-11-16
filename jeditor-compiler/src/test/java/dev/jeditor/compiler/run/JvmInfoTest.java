package dev.jeditor.compiler.run;

import dev.jeditor.compiler.ProjectLayout;
import dev.jeditor.compiler.run.JvmInfo.HeapReading;
import dev.jeditor.compiler.run.JvmInfo.HeapSettings;
import dev.jeditor.compiler.run.JvmInfo.SystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmInfoTest {

    private static final long MB = 1024 * 1024;
    private static final long GB = 1024 * MB;

    private final JavaRuntime java21 = new JavaRuntime(Path.of("/opt/jdk-21"), "21.0.2", 21, true);
    private final SystemInfo machine = new SystemInfo(16 * GB, 9 * GB + 512 * MB, "Mac OS X", "14.5", "aarch64", 8);
    private final HeapSettings defaults = new HeapSettings(256 * MB, 4 * GB, "G1");

    @TempDir
    Path dir;

    // ---- the real thing -----------------------------------------------------------------------

    @Test
    void readsTheHeapOfARunningProgram() throws Exception {
        // A program that holds on to about 64 MB and then waits.
        Path source = Files.writeString(dir.resolve("Hog.java"), """
                public class Hog {
                    static byte[][] kept = new byte[64][];
                    public static void main(String[] args) throws Exception {
                        for (int i = 0; i < kept.length; i++) kept[i] = new byte[1024 * 1024];
                        System.out.println("ready");
                        Thread.sleep(60_000);
                    }
                }
                """);
        Path out = dir.resolve("out");
        assertTrue(new ProgramBuilder().build(source, ProjectLayout.NONE, out, Runtime.version().feature()).success());
        JavaRuntime runtime = JavaRuntime.current().orElseThrow();
        Process process = JavaLauncher.start(runtime, List.of(out), "Hog", List.of(), dir);
        try {
            new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream())).lines()
                    .filter("ready"::equals).findFirst().orElseThrow();

            try (JvmInfo.Monitor monitor = JvmInfo.Monitor.attach(process.pid())) {
                HeapReading reading = monitor.read();
                HeapSettings settings = HeapSettings.of(runtime).orElseThrow();

                assertTrue(reading.usedBytes() >= 64 * MB, "holds 64 MB, used = " + reading.usedBytes());
                assertTrue(reading.committedBytes() >= reading.usedBytes());
                assertEquals(settings.maxBytes(), reading.maxBytes(),
                        "the limit the runtime reports for this machine is the one the program got");
                assertTrue(settings.initialBytes() > 0 && settings.initialBytes() <= settings.maxBytes());
            }
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void connectingToAProgramThatHasEndedFailsCleanly() throws Exception {
        Process process = new ProcessBuilder(JavaRuntime.current().orElseThrow().javaExecutable().toString(), "-version")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
        assertThrows(IOException.class, () -> JvmInfo.Monitor.attach(process.pid()));
    }

    @Test
    void readsTheMachine() {
        SystemInfo system = SystemInfo.read();
        assertTrue(system.totalRamBytes() > 256 * MB, "RAM = " + system.totalRamBytes());
        assertTrue(system.freeRamBytes() > 0 && system.freeRamBytes() <= system.totalRamBytes());
        assertEquals(System.getProperty("os.name"), system.osName());
        assertEquals(System.getProperty("os.arch"), system.architecture());
        assertTrue(system.processors() >= 1);
    }

    // ---- parsing the runtime's flags ----------------------------------------------------------

    @Test
    void parsesModernFlagOutput() {
        String flags = """
                   size_t InitialHeapSize                          = 224395264                                 {product} {ergonomic}
                   size_t MaxHeapSize                              = 3586129920                                {product} {ergonomic}
                   size_t SoftMaxHeapSize                          = 3586129920                             {manageable} {ergonomic}
                     bool UseG1GC                                  = true                                      {product} {ergonomic}
                     bool UseParallelGC                            = false                                     {product} {default}
                     bool UseSerialGC                              = false                                     {product} {default}
                """;
        assertEquals(Optional.of(new HeapSettings(224395264L, 3586129920L, "G1")), HeapSettings.parse(flags));
    }

    @Test
    void parsesJava8FlagOutput() {
        String flags = """
                    uintx InitialHeapSize                          := 268435456                           {product}
                    uintx MaxHeapSize                              := 4294967296                          {product}
                     bool UseG1GC                                   = false                               {product}
                     bool UseParallelGC                            := true                                {product}
                """;
        assertEquals(Optional.of(new HeapSettings(268435456L, 4294967296L, "Parallel")), HeapSettings.parse(flags));
    }

    @Test
    void incompleteOrUnrelatedOutputGivesNothing() {
        assertEquals(Optional.empty(), HeapSettings.parse("openjdk version \"21\""));
        assertEquals(Optional.empty(), HeapSettings.parse("size_t MaxHeapSize = 123"));
        assertEquals(new HeapSettings(1, 2, null),
                HeapSettings.parse("InitialHeapSize = 1\nMaxHeapSize = 2\n").orElseThrow(), "collector unknown is fine");
    }

    // ---- the account --------------------------------------------------------------------------

    @Test
    void describesARunningProgram() {
        JvmInfo info = new JvmInfo("app.Main", java21, 4242, machine, defaults, null, 0, true)
                .with(new HeapReading(100 * MB, 256 * MB, 4 * GB), true)
                .with(new HeapReading(40 * MB, 256 * MB, 4 * GB), true);

        assertEquals("""
                PROGRAM
                  app.Main   (process 4242, running)
                  on Java 21.0.2 (JDK), G1 garbage collector

                SYSTEM MEMORY (RAM)
                  Installed          16.0 GB
                  Free right now     9.5 GB

                MEMORY RESERVED FOR THIS PROGRAM  (its Java heap)
                  Maximum heap       4.0 GB   (25% of RAM)
                      The most it may ever use for its objects. It was started without -Xmx,
                      so Java chose this itself: by default a quarter of the machine's RAM.
                  Initial heap       256 MB   (1.6% of RAM)
                      What the heap starts at (no -Xms given: by default 1/64 of the RAM).

                HEAP ACTUALLY IN USE
                  Used               40 MB   (1.0% of the maximum)
                      Occupied by the program's objects, including garbage not yet collected.
                  Taken from the OS  256 MB
                      What the JVM has actually claimed for the heap so far; grows towards the
                      maximum only when the program needs it.
                  Peak used          100 MB
                      Highest value seen so far. Readings are taken once a second.

                OPERATING SYSTEM
                  Name               Mac OS X 14.5
                  Architecture       aarch64
                  Processors         8
                """, info.describe());
    }

    @Test
    void afterTheProgramEndsTheLastReadingStays() {
        JvmInfo ended = new JvmInfo("app.Main", java21, 7, machine, defaults, null, 0, true)
                .with(new HeapReading(30 * MB, 256 * MB, 4 * GB), true)
                .with(null, false);

        String text = ended.describe();
        assertTrue(text.contains("(process 7, ended)"), text);
        assertTrue(text.contains("HEAP ACTUALLY IN USE  (last reading before the program ended)\n  Used               30 MB"), text);
        assertTrue(text.contains("Peak used          30 MB\n      Highest value seen in the once-a-second readings."), text);
    }

    @Test
    void aProgramTooShortToMeasureSaysSoButStillShowsTheLimits() {
        String text = new JvmInfo("app.Quick", java21, 9, machine, defaults, null, 0, false).describe();
        assertTrue(text.contains("Maximum heap       4.0 GB"), text);
        assertTrue(text.contains("HEAP ACTUALLY IN USE\n  No reading could be taken: the program ended before the editor could connect"), text);
        assertFalse(text.contains("Peak used"), text);

        String waiting = new JvmInfo("app.Quick", java21, 9, machine, defaults, null, 0, true).describe();
        assertTrue(waiting.contains("Waiting for the first reading..."), waiting);
    }

    @Test
    void worksWithoutKnownLimits() {
        String unknown = new JvmInfo("app.Main", java21, 1, machine, null, null, 0, false).describe();
        assertTrue(unknown.contains("The heap limits of this Java runtime could not be determined."), unknown);
        assertTrue(unknown.contains("on Java 21.0.2 (JDK)\n"), unknown);

        // A live reading carries the maximum even when the settings are missing.
        String live = new JvmInfo("app.Main", java21, 1, machine, null, new HeapReading(10 * MB, 20 * MB, 2 * GB), 10 * MB, true).describe();
        assertTrue(live.contains("Maximum heap       2.0 GB   (13% of RAM)"), live);
        assertFalse(live.contains("Initial heap"), live);
    }

    @Test
    void sizesAreShownInTheUnitThatReadsBest() {
        assertEquals("15.6 GB", JvmInfo.gb((long) (15.6 * GB)));
        assertEquals("unknown", JvmInfo.gb(0));
        assertEquals("0 MB", JvmInfo.size(0));
        assertEquals("224 MB", JvmInfo.size(224 * MB));
        assertEquals("1.0 GB", JvmInfo.size(GB));
        assertEquals("3.3 GB", JvmInfo.size(3586129920L));
        assertEquals("unknown", JvmInfo.size(-1));
    }
}
