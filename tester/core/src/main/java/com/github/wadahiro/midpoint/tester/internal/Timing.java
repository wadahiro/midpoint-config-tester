package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in time report of the tester's own phases (system property {@code tester.timing=true}): the JVM before the
 * tester, startup, the deployment's load or restore, resets, snapshots, the waits for background work, the tests'
 * own time. Written after the last test class to {@code <workDir>/tester/timing.md} (printed) and
 * {@code timing.json}, each phase compared with the previous run's {@code timing.json} there (kept as
 * {@code timing-previous.json}), or with the report {@code tester.timing.baseline} names, to follow a change of the
 * tester or of the deployment over time. Off by default: {@link #start()} then returns 0 and {@link #end} does
 * nothing.
 */
public final class Timing {

    public static final boolean ON = Boolean.getBoolean("tester.timing");

    /** Per phase: count, total nanoseconds, maximum nanoseconds. */
    private static final Map<String, long[]> PHASES = new LinkedHashMap<>();

    private Timing() {
    }

    /** The start of a phase, for {@link #end}. */
    public static long start() {
        return ON ? System.nanoTime() : 0;
    }

    /** Adds the time since {@code start} to the phase. */
    public static void end(String phase, long start) {
        if (ON) {
            add(phase, System.nanoTime() - start);
        }
    }

    /** Adds {@code nanos} to the phase (also counts it once). */
    public static void add(String phase, long nanos) {
        if (!ON) {
            return;
        }
        synchronized (PHASES) {
            long[] p = PHASES.computeIfAbsent(phase, k -> new long[3]);
            p[0]++;
            p[1] += nanos;
            p[2] = Math.max(p[2], nanos);
        }
    }

    /** Counts an event of the phase without a time. */
    public static void count(String phase) {
        add(phase, 0);
    }

    private static volatile long testStart;

    /** A top-level test starts, after its reset to the deployment ({@code test.body} ends at {@link #testEnds()}). */
    public static void testBegins() {
        testStart = start();
    }

    /** The top-level test ended. */
    public static void testEnds() {
        if (ON && testStart != 0) {
            end("test.body", testStart);
            testStart = 0;
        }
    }

    /** The JVM's life before the tester started: its start, the test framework, the classes loaded until then. */
    public static void beforeTester() {
        if (ON) {
            add("startup.beforeTester", Math.max(0, System.currentTimeMillis()
                    - ManagementFactory.getRuntimeMXBean().getStartTime()) * 1_000_000L);
        }
    }

    /** Writes and prints the report; nothing when off. */
    public static void writeReport(Path dir, String line) {
        if (!ON) {
            return;
        }
        add("jvm.total", ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000L);
        List<Map.Entry<String, long[]>> entries;
        synchronized (PHASES) {
            entries = new ArrayList<>();
            PHASES.forEach((k, v) -> entries.add(Map.entry(k, v.clone())));
        }
        entries.sort(Map.Entry.comparingByKey());
        String baselineProperty = System.getProperty("tester.timing.baseline", "");
        Path json = dir.resolve("timing.json");
        Path previous = baselineProperty.isBlank() ? dir.resolve("timing-previous.json") : Path.of(baselineProperty);
        try {
            Files.createDirectories(dir);
            if (baselineProperty.isBlank() && Files.exists(json)) {
                Files.move(json, previous, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // diagnostics only
        }
        Map<String, double[]> before = read(previous);
        String java = System.getProperty("java.version");
        String fork = System.getProperty("tester.fork", "");
        String date = Instant.now().toString();
        StringBuilder md = new StringBuilder("# Tester timing, midPoint " + line + "\n\n" + "Java " + java
                + (fork.isEmpty() || fork.startsWith("$") ? "" : ", fork " + fork) + ", " + date
                + (before.isEmpty() ? "" : ", compared with " + previous) + "\n\n"
                + "| phase | count | total s | mean ms | max ms | total s before | mean ms before |\n"
                + "|---|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder js = new StringBuilder("{\n  \"line\": \"" + line + "\",\n  \"java\": \"" + java
                + "\",\n  \"fork\": \"" + (fork.startsWith("$") ? "" : fork) + "\",\n  \"date\": \"" + date
                + "\",\n  \"phases\": {");
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, long[]> e = entries.get(i);
            long[] p = e.getValue();
            double totalMs = p[1] / 1e6;
            double meanMs = p[0] == 0 ? 0 : p[1] / 1e6 / p[0];
            double maxMs = p[2] / 1e6;
            double[] b = before.get(e.getKey());
            md.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.1f | %s | %s |%n", e.getKey(), p[0],
                    totalMs / 1e3, meanMs, maxMs, b == null ? "" : String.format(Locale.ROOT, "%.2f", b[1] / 1e3),
                    b == null ? "" : String.format(Locale.ROOT, "%.2f", b[2])));
            js.append(String.format(Locale.ROOT,
                    "%n    \"%s\": {\"count\": %d, \"totalMs\": %.3f, \"meanMs\": %.3f, \"maxMs\": %.3f}%s",
                    e.getKey(), p[0], totalMs, meanMs, maxMs, i < entries.size() - 1 ? "," : ""));
        }
        js.append("\n  }\n}\n");
        System.out.println(md);
        try {
            Files.writeString(dir.resolve("timing.md"), md.toString(), StandardCharsets.UTF_8);
            Files.writeString(json, js.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // diagnostics only
        }
    }

    private static final Pattern PHASE = Pattern.compile(
            "\"([^\"]+)\": \\{\"count\": (\\d+), \"totalMs\": ([\\d.]+), \"meanMs\": ([\\d.]+), \"maxMs\": ([\\d.]+)\\}");

    /** A report's phases (count, total ms, mean ms, max ms), none when it is missing or unreadable. */
    static Map<String, double[]> read(Path json) {
        Map<String, double[]> phases = new HashMap<>();
        try {
            if (!Files.exists(json)) {
                return phases;
            }
            Matcher m = PHASE.matcher(Files.readString(json, StandardCharsets.UTF_8));
            while (m.find()) {
                phases.put(m.group(1), new double[] {Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
                        Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5))});
            }
        } catch (IOException | RuntimeException e) {
            // diagnostics only: no comparison
        }
        return phases;
    }
}
