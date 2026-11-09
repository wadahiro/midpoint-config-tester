package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * Why a value is what it is: the mapping evaluations of this test whose target is the item, oldest first, as the
 * tester recorded them ({@link User#why}, {@link Account#why}). Printed, one line per evaluation, the same evaluation
 * repeated in a row (midPoint's projector runs several times in one operation) once with its count:
 *
 * <pre>
 * Mappings that set fullName of e1001 in this test:
 *   - 'user-fullName' of User template for e1001 (task Reconcile HR, reconciliation): givenName [] -&gt; [John],
 *     familyName [] -&gt; [Doe]; output [John Doe]; 3 evaluations
 * </pre>
 *
 * <p>The count is how often midPoint evaluated the mapping for the focus in the operation; with as many focuses, a
 * reconciliation evaluates it that often for each (a hint where an expensive script runs repeatedly).</p>
 *
 * <p>The failure messages of the assertions on users and accounts end with the same text.</p>
 */
public final class Why {

    /** One mapping evaluation. */
    public static final class Step {
        private final String mapping;
        private final String owner;
        private final String focus;
        private final Map<String, String> sources;
        private final Boolean condition;
        private final List<String> output;
        private final String task;
        private final String channel;
        private final String operation;

        Step(Run run, Evaluation e) {
            this.mapping = e.mappingName();
            this.owner = e.owner() == null ? null : e.owner().name() != null ? e.owner().name() : e.owner().oid();
            this.focus = e.focus() != null ? e.focus().name() : null;
            Map<String, String> sources = new LinkedHashMap<>();
            for (Map.Entry<String, Evaluation.SourceValues> source : e.sources().entrySet()) {
                sources.put(source.getKey(), Values.texts(source.getValue().oldValues()) + " -> "
                        + Values.texts(source.getValue().newValues()));
            }
            this.sources = Collections.unmodifiableMap(sources);
            this.condition = e.conditionNew();
            this.output = e.hasOutput() ? Values.texts(e.newValues()) : null;
            this.task = e.task();
            this.channel = e.channel();
            this.operation = run.operation();
        }

        /** The mapping's name; null when it has none. */
        public String mapping() {
            return mapping;
        }

        /** The object the mapping is in (a template, a role, a resource), by name. */
        public String owner() {
            return owner;
        }

        /** The user it was evaluated for. */
        public String focus() {
            return focus;
        }

        /** Each source: {@code [old values] -> [new values]}. */
        public Map<String, String> sources() {
            return sources;
        }

        /** The condition for the new state; null when the mapping has none. */
        public Boolean condition() {
            return condition;
        }

        /** The values the mapping gave for the new state; null when it gave none (e.g. its condition was false). */
        public List<String> output() {
            return output;
        }

        /** The background task it ran in (null: in the call of the test itself, see {@link #operation()}). */
        public String task() {
            return task;
        }

        /** The channel of the operation, short ({@code reconciliation}, {@code import}...); null when it has none. */
        public String channel() {
            return channel;
        }

        /** The tester's call it happened in ({@code run task Reconcile HR}, {@code request ...}). */
        public String operation() {
            return operation;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mapping != null ? "'" + mapping + "'" : "(unnamed mapping)");
            if (owner != null) {
                sb.append(" of ").append(owner);
            }
            if (focus != null) {
                sb.append(" for ").append(focus);
            }
            sb.append(" (").append(task != null ? "task " + task : operation)
                    .append(channel != null ? ", " + channel : "").append(")");
            if (!sources.isEmpty()) {
                List<String> parts = new ArrayList<>();
                sources.forEach((name, values) -> parts.add(name + " " + values));
                sb.append(": ").append(String.join(", ", parts));
            }
            if (condition != null) {
                sb.append("; condition ").append(condition);
            }
            sb.append(output != null ? "; output " + output : "; no output");
            return sb.toString();
        }
    }

    private static final int MAX_IN_MESSAGES = 8;

    private final String what;
    private final List<Step> steps;

    private Why(String what, List<Step> steps) {
        this.what = what;
        this.steps = Collections.unmodifiableList(steps);
    }

    /** The evaluations, oldest first. */
    public List<Step> steps() {
        return steps;
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** The last evaluation (the one that set the current value, unless a later change did not run a mapping). */
    public Step last() {
        if (steps.isEmpty()) {
            throw new AssertionError("No mapping set " + what + " in this test");
        }
        return steps.get(steps.size() - 1);
    }

    /** Prints {@link #toString()} to standard output. */
    public Why print() {
        System.out.println(this);
        return this;
    }

    @Override
    public String toString() {
        if (steps.isEmpty()) {
            return "No mapping set " + what + " in this test";
        }
        StringBuilder sb = new StringBuilder("Mappings that set ").append(what).append(" in this test:");
        lines(steps).forEach(line -> sb.append("\n  - ").append(line));
        return sb.toString();
    }

    /** The steps as text, the same step repeated in a row once with its count. */
    static List<String> lines(List<Step> steps) {
        List<String> out = new ArrayList<>();
        String previous = null;
        int count = 0;
        for (Step step : steps) {
            String text = step.toString();
            if (text.equals(previous)) {
                count++;
                continue;
            }
            if (previous != null) {
                out.add(previous + (count > 1 ? "; " + count + " evaluations" : ""));
            }
            previous = text;
            count = 1;
        }
        if (previous != null) {
            out.add(previous + (count > 1 ? "; " + count + " evaluations" : ""));
        }
        return out;
    }

    // ---- package ----------------------------------------------------------------------------------------------

    /**
     * The mapping evaluations of the test's runs whose target is the item; {@code item} is a path ({@code fullName},
     * {@code activation/administrativeStatus}) or an attribute ({@code cn}), {@code focus} a user name or null (any).
     */
    static Why of(Scenario scenario, String item, String focus, String what) {
        List<Step> steps = new ArrayList<>();
        for (Run run : scenario.runs()) {
            for (Evaluation e : run.evaluations()) {
                if (!e.isMapping() || e.target() == null || !matches(e.target(), item)) {
                    continue;
                }
                if (focus != null && (e.focus() == null || !e.focus().is(focus))) {
                    continue;
                }
                steps.add(new Step(run, e));
            }
        }
        return new Why(what, steps);
    }

    /** The items (as the mappings name their targets, e.g. {@code fullName}) mappings of this test set for the user. */
    static List<String> targets(Scenario scenario, String focus) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Run run : scenario.runs()) {
            for (Evaluation e : run.evaluations()) {
                if (e.isMapping() && e.target() != null && e.focus() != null && e.focus().is(focus)
                        && !normalize(e.target()).startsWith("attributes/")) {
                    out.add(normalize(e.target()));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** For a failure message: a new line and the last evaluations, or an empty string when none was recorded. */
    static String mappings(Scenario scenario, String item, String focus, String what) {
        Why why = of(scenario, item, focus, what);
        if (why.isEmpty()) {
            return "";
        }
        List<String> lines = lines(why.steps);
        List<String> shown = lines.size() > MAX_IN_MESSAGES
                ? lines.subList(lines.size() - MAX_IN_MESSAGES, lines.size()) : lines;
        StringBuilder sb = new StringBuilder("\nMappings that set ").append(what).append(" in this test")
                .append(lines.size() > MAX_IN_MESSAGES
                        ? " (the last " + MAX_IN_MESSAGES + " of " + lines.size() + ")" : "")
                .append(':');
        shown.forEach(line -> sb.append("\n  - ").append(line));
        return sb.toString();
    }

    /** Target {@code attributes/ri:cn} or {@code $focus/fullName} matches {@code cn} or {@code fullName}. */
    static boolean matches(String target, String item) {
        return normalize(target).equals(normalize(item)) || normalize(target).endsWith("/" + normalize(item))
                && normalize(target).startsWith("attributes/");
    }

    private static String normalize(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.startsWith("$")) {
                continue;
            }
            int colon = segment.indexOf(':');
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(colon >= 0 ? segment.substring(colon + 1) : segment);
        }
        return sb.toString();
    }
}
