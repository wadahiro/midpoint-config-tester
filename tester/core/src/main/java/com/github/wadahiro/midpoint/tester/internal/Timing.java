package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in time report of the tester's own phases (system property {@code tester.timing=true}): startup, resets,
 * snapshots, the waits for background work, the class set-up and golden results. Written after the last test
 * class to {@code <workDir>/tester/timing.md} and printed. Off by default: {@link #start()} then returns 0 and
 * {@link #end} does nothing.
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

    /** Writes and prints the report; nothing when off. */
    public static void writeReport(Path dir, String line) {
        if (!ON) {
            return;
        }
        List<Map.Entry<String, long[]>> entries;
        synchronized (PHASES) {
            entries = new ArrayList<>();
            PHASES.forEach((k, v) -> entries.add(Map.entry(k, v.clone())));
        }
        entries.sort(Map.Entry.comparingByKey());
        StringBuilder sb = new StringBuilder("# Tester timing, midPoint " + line + "\n\n"
                + "| phase | count | total s | mean ms | max ms |\n|---|---:|---:|---:|---:|\n");
        for (Map.Entry<String, long[]> e : entries) {
            long[] p = e.getValue();
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.1f |%n", e.getKey(), p[0],
                    p[1] / 1e9, p[0] == 0 ? 0 : p[1] / 1e6 / p[0], p[2] / 1e6));
        }
        System.out.println(sb);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("timing.md"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // diagnostics only
        }
    }
}
