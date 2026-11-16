package dev.jeditor.compiler.run;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory facts about a program the editor has launched, and a plain-language account of them:
 * how much RAM the machine has, how much of it the program's JVM may use for its heap, how much
 * it has taken and how much it is actually using.
 *
 * @param mainClass the class that was run
 * @param runtime   the Java it runs on
 * @param pid       the program's process id
 * @param system    the machine it runs on
 * @param settings  the heap limits the JVM chose; null if they could not be determined
 * @param heap      the latest reading of the program's heap; null if none could be taken
 * @param peakUsed  the highest "used" value seen in any reading, in bytes; 0 if there was none
 * @param running   false once the program has ended, when {@code heap} is its last reading
 */
public record JvmInfo(String mainClass, JavaRuntime runtime, long pid, SystemInfo system, HeapSettings settings,
                      HeapReading heap, long peakUsed, boolean running) {

    private static final System.Logger LOG = System.getLogger(JvmInfo.class.getName());

    /**
     * The machine.
     *
     * @param totalRamBytes physical memory installed
     * @param freeRamBytes  physical memory not in use right now
     */
    public record SystemInfo(long totalRamBytes, long freeRamBytes, String osName, String osVersion,
                             String architecture, int processors) {

        /** Reads the machine the editor runs on, which is also the one launched programs run on. */
        public static SystemInfo read() {
            long total = 0;
            long free = 0;
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                total = os.getTotalMemorySize();
                free = os.getFreeMemorySize();
            }
            return new SystemInfo(total, free, System.getProperty("os.name", "?"), System.getProperty("os.version", "?"),
                    System.getProperty("os.arch", "?"), Runtime.getRuntime().availableProcessors());
        }
    }

    /**
     * The heap limits a JVM starts with when, as here, no {@code -Xms}/{@code -Xmx} is given: it
     * derives them from the machine's RAM.
     *
     * @param initialBytes what the heap starts at ({@code -Xms})
     * @param maxBytes     what the heap may grow to ({@code -Xmx}); the JVM never uses more heap than this
     * @param collector    the garbage collector in use, e.g. "G1"; null if not recognised
     */
    public record HeapSettings(long initialBytes, long maxBytes, String collector) {

        private static final Pattern FLAG = Pattern.compile("\\b(InitialHeapSize|MaxHeapSize)\\s*:?=\\s*(\\d+)");
        private static final Pattern COLLECTOR = Pattern.compile("\\bUse(\\w+?)GC\\s*:?=\\s*true");
        private static final Map<String, Optional<HeapSettings>> CACHE = new ConcurrentHashMap<>();

        /**
         * Asks the runtime what limits it picks on this machine by running
         * {@code java -XX:+PrintFlagsFinal -version}. The answer is remembered per runtime, since
         * it does not change while the machine stays the same.
         */
        public static Optional<HeapSettings> of(JavaRuntime runtime) {
            return CACHE.computeIfAbsent(runtime.home().toString(), home -> ask(runtime));
        }

        private static Optional<HeapSettings> ask(JavaRuntime runtime) {
            try {
                Process process = new ProcessBuilder(runtime.javaExecutable().toString(), "-XX:+PrintFlagsFinal", "-version")
                        .redirectErrorStream(true).start();
                process.getOutputStream().close();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("HeapSize") || line.contains("GC ")) { // the output is ~900 lines; keep what matters
                            output.append(line).append('\n');
                        }
                    }
                }
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return parse(output.toString());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot ask " + runtime.javaExecutable() + " for its heap settings", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

        /** Reads the limits out of {@code -XX:+PrintFlagsFinal} output; empty if either limit is missing. */
        static Optional<HeapSettings> parse(String flags) {
            long initial = -1;
            long max = -1;
            Matcher matcher = FLAG.matcher(flags);
            while (matcher.find()) {
                long value = Long.parseLong(matcher.group(2));
                if (matcher.group(1).equals("InitialHeapSize")) {
                    initial = value;
                } else {
                    max = value;
                }
            }
            if (initial < 0 || max < 0) {
                return Optional.empty();
            }
            Matcher collector = COLLECTOR.matcher(flags);
            return Optional.of(new HeapSettings(initial, max, collector.find() ? collector.group(1) : null));
        }
    }

    /**
     * One reading of a running program's heap.
     *
     * @param usedBytes      memory occupied by the program's objects right now
     * @param committedBytes memory the JVM has actually taken from the operating system for the heap
     * @param maxBytes       the limit the heap may grow to
     */
    public record HeapReading(long usedBytes, long committedBytes, long maxBytes) {
    }

    /**
     * A live connection to a running program's JVM, for reading its heap. The JVM is not
     * modified: the editor attaches to it the way monitoring tools such as JConsole do.
     */
    public static final class Monitor implements Closeable {
        private final JMXConnector connector;
        private final MemoryMXBean memory;

        private Monitor(JMXConnector connector, MemoryMXBean memory) {
            this.connector = connector;
            this.memory = memory;
        }

        /**
         * Connects to the JVM running as process {@code pid}.
         *
         * @throws IOException if that is not possible: the program has already ended, it is not a
         *                     JVM that accepts connections, or the editor does not run on a JDK
         */
        public static Monitor attach(long pid) throws IOException {
            VirtualMachine vm = null;
            try {
                vm = VirtualMachine.attach(String.valueOf(pid));
                String address = vm.startLocalManagementAgent();
                JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
                MBeanServerConnection connection = connector.getMBeanServerConnection();
                return new Monitor(connector, ManagementFactory.newPlatformMXBeanProxy(
                        connection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class));
            } catch (IOException e) {
                throw e;
            } catch (Exception | LinkageError e) { // AttachNotSupportedException, or no jdk.attach module (a JRE)
                throw new IOException("Cannot connect to the JVM of process " + pid + ": " + e, e);
            } finally {
                if (vm != null) {
                    try {
                        vm.detach();
                    } catch (IOException e) {
                        LOG.log(System.Logger.Level.WARNING, "Cannot detach from process " + pid, e);
                    }
                }
            }
        }

        /** @throws IOException if the program has ended or the connection broke */
        public HeapReading read() throws IOException {
            try {
                MemoryUsage usage = memory.getHeapMemoryUsage();
                return new HeapReading(usage.getUsed(), usage.getCommitted(), usage.getMax());
            } catch (RuntimeException e) { // the proxy wraps connection failures
                throw new IOException("The program's JVM no longer answers", e);
            }
        }

        @Override
        public void close() {
            try {
                connector.close();
            } catch (IOException | RuntimeException e) {
                // the program has usually exited already; nothing left to close
            }
        }
    }

    // ---- description --------------------------------------------------------------------------

    /** The same information with a newer heap reading; the peak is carried forward. */
    public JvmInfo with(HeapReading reading, boolean stillRunning) {
        HeapReading latest = reading != null ? reading : heap;
        return new JvmInfo(mainClass, runtime, pid, system, settings, latest,
                Math.max(peakUsed, reading == null ? 0 : reading.usedBytes()), stillRunning);
    }

    /** The account shown to the user. Plain text, meant for a fixed-width font. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        long ram = system.totalRamBytes();
        long max = heap != null ? heap.maxBytes() : settings != null ? settings.maxBytes() : -1;

        text.append("PROGRAM\n");
        text.append("  ").append(mainClass).append("   (process ").append(pid).append(running ? ", running" : ", ended").append(")\n");
        text.append("  on ").append(runtime.displayName());
        if (settings != null && settings.collector() != null) {
            text.append(", ").append(settings.collector()).append(" garbage collector");
        }
        text.append('\n');

        text.append("\nSYSTEM MEMORY (RAM)\n");
        text.append(row("Installed", gb(ram)));
        text.append(row("Free right now", gb(system.freeRamBytes())));

        text.append("\nMEMORY RESERVED FOR THIS PROGRAM  (its Java heap)\n");
        if (max > 0) {
            text.append(row("Maximum heap", size(max) + percentOf(max, ram, "of RAM")));
            text.append("      The most it may ever use for its objects. It was started without -Xmx,\n");
            text.append("      so Java chose this itself: by default a quarter of the machine's RAM.\n");
        }
        if (settings != null) {
            text.append(row("Initial heap", size(settings.initialBytes()) + percentOf(settings.initialBytes(), ram, "of RAM")));
            text.append("      What the heap starts at (no -Xms given: by default 1/64 of the RAM).\n");
        }
        if (max <= 0 && settings == null) {
            text.append("  The heap limits of this Java runtime could not be determined.\n");
        }

        text.append("\nHEAP ACTUALLY IN USE").append(running || heap == null ? "" : "  (last reading before the program ended)").append('\n');
        if (heap == null) {
            text.append(running
                    ? "  Waiting for the first reading...\n"
                    : "  No reading could be taken: the program ended before the editor could connect\n"
                    + "  to it (that takes a fraction of a second), or its JVM does not allow it.\n");
        } else {
            text.append(row("Used", size(heap.usedBytes()) + percentOf(heap.usedBytes(), max, "of the maximum")));
            text.append("      Occupied by the program's objects, including garbage not yet collected.\n");
            text.append(row("Taken from the OS", size(heap.committedBytes())));
            text.append("      What the JVM has actually claimed for the heap so far; grows towards the\n");
            text.append("      maximum only when the program needs it.\n");
            text.append(row("Peak used", size(peakUsed)));
            text.append(running ? "      Highest value seen so far. Readings are taken once a second.\n"
                    : "      Highest value seen in the once-a-second readings.\n");
        }

        text.append("\nOPERATING SYSTEM\n");
        text.append(row("Name", system.osName() + " " + system.osVersion()));
        text.append(row("Architecture", system.architecture()));
        text.append(row("Processors", String.valueOf(system.processors())));
        return text.toString();
    }

    private static String row(String label, String value) {
        return String.format(Locale.ROOT, "  %-18s %s%n", label, value);
    }

    /** RAM is quoted in GB, as it is sold: "15.6 GB". */
    static String gb(long bytes) {
        return bytes <= 0 ? "unknown" : String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Heap sizes in the unit that reads best: "224 MB", "3.3 GB". */
    static String size(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        double mb = bytes / (1024.0 * 1024);
        return mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024) : String.format(Locale.ROOT, "%.0f MB", mb);
    }

    private static String percentOf(long part, long whole, String ofWhat) {
        if (part < 0 || whole <= 0) {
            return "";
        }
        double percent = 100.0 * part / whole;
        return String.format(Locale.ROOT, "   (%s%% %s)", percent < 10 ? String.format(Locale.ROOT, "%.1f", percent)
                : String.format(Locale.ROOT, "%.0f", percent), ofWhat);
    }
}
