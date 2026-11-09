package com.github.wadahiro.midpoint.configtester;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * How often midPoint evaluated each mapping in the test so far, and how long it took ({@link Scenario#mappingStats()}):
 * where an expensive script runs, and how often (a reconciliation of many users evaluates a mapping that often for
 * each). The first evaluation of a mapping is far slower (its script's compilation, the first search of a target
 * system), so it is shown apart, and the mappings are sorted by the total of the others ({@link Entry#rest()}), the
 * longest first. The times are of this JVM, its in-memory database and the tester's stand-in systems, and include the
 * tester's recording of the expressions inside a mapping: compare the mappings with each other, not with production.
 *
 * <pre>
 * Mapping evaluations in this test, by the total after the first, the longest first:
 *   'user-department-role' of User template: 12 evaluations, median 1.0 ms, after the first 10.3 ms; first 11.7 ms
 * </pre>
 */
public final class MappingStats {

    /** The evaluations of one mapping (by its name and owner). */
    public static final class Entry {
        private final String mapping;
        private final String owner;
        private final List<Duration> durations = new ArrayList<>();

        Entry(String mapping, String owner) {
            this.mapping = mapping;
            this.owner = owner;
        }

        /** The mapping's name; null when it has none. */
        public String mapping() {
            return mapping;
        }

        /** The object the mapping is in (a template, a role, a resource), by name. */
        public String owner() {
            return owner;
        }

        public int count() {
            return durations.size();
        }

        public Duration total() {
            return durations.stream().reduce(Duration.ZERO, Duration::plus);
        }

        public Duration median() {
            List<Duration> sorted = new ArrayList<>(durations);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        public Duration max() {
            return Collections.max(durations);
        }

        /** The first evaluation (with a script, its compilation). */
        public Duration first() {
            return durations.get(0);
        }

        /** The total of the evaluations after the first: what the mapping costs once warm. */
        public Duration rest() {
            return total().minus(first());
        }

        @Override
        public String toString() {
            return (mapping != null ? "'" + mapping + "'" : "(unnamed mapping)") + (owner != null ? " of " + owner : "")
                    + ": " + count() + (count() == 1 ? " evaluation" : " evaluations")
                    + (count() > 1 ? ", median " + millis(median()) + ", after the first " + millis(rest()) : "")
                    + "; first " + millis(first());
        }
    }

    private final List<Entry> entries;

    private MappingStats(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    static MappingStats of(Scenario scenario) {
        Map<String, Entry> byMapping = new LinkedHashMap<>();
        for (Run run : scenario.runs()) {
            for (Evaluation e : run.evaluations()) {
                if (!e.isMapping() || e.duration() == null) {
                    continue;
                }
                String owner = e.owner() == null ? null : e.owner().name() != null ? e.owner().name() : e.owner().oid();
                byMapping.computeIfAbsent(e.mappingName() + "\u0000" + owner, k -> new Entry(e.mappingName(), owner))
                        .durations.add(e.duration());
            }
        }
        List<Entry> entries = new ArrayList<>(byMapping.values());
        entries.sort(Comparator.comparing(Entry::rest).reversed());
        return new MappingStats(entries);
    }

    /** The mappings, by the total after the first evaluation, the longest first. */
    public List<Entry> entries() {
        return entries;
    }

    /** The mapping of this name (the first, when several owners have one); null when it was not evaluated. */
    public Entry of(String mapping) {
        return entries.stream().filter(e -> mapping.equals(e.mapping())).findFirst().orElse(null);
    }

    /** Prints {@link #toString()} to standard output. */
    public MappingStats print() {
        System.out.println(this);
        return this;
    }

    @Override
    public String toString() {
        if (entries.isEmpty()) {
            return "No mapping was evaluated in this test";
        }
        StringBuilder sb = new StringBuilder(
                "Mapping evaluations in this test, by the total after the first, the longest first:");
        entries.forEach(entry -> sb.append("\n  ").append(entry));
        return sb.toString();
    }

    private static String millis(Duration duration) {
        return String.format(java.util.Locale.ROOT, "%.1f ms", duration.toNanos() / 1_000_000.0);
    }
}
