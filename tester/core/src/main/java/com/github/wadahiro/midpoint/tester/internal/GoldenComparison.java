package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.github.wadahiro.midpoint.tester.internal.GoldenResults.Place;
import com.github.wadahiro.midpoint.tester.internal.GoldenResults.RunResult;
import com.github.wadahiro.midpoint.tester.internal.GoldenResults.TestResult;

/**
 * Compares the results of a test with its golden results, run by run (matched by key: step name or driver label,
 * else by number) and place by place ({@code Identity}), and renders the differences as Markdown. No system
 * properties are read here (see {@link Options}).
 */
public final class GoldenComparison {

    /** What differs; {@link #DEFAULT_FAILING} fail verification, the others are notes. */
    public enum Kind {
        /** The outcomes of a place changed (new or removed values, condition, error). */
        CHANGED,
        /** A place of the golden results was not evaluated. */
        MISSING,
        /** A place was evaluated that the golden results do not have. */
        NEW,
        /** Only the old values or the old condition differ: the place computes the same result. */
        OLD,
        /**
         * With the option {@code new-state}: only what depends on the old state differs (removed values, old values,
         * the old condition, the output under a false new condition), see {@link GoldenResults#newState}.
         */
        OLD_STATE,
        /** Only line-dependent text differs: an error's message (same class) or the JSON form of a container. */
        TEXT,
        /** The number of evaluations of a place changed. */
        COUNT,
        /** The variables or sources a place was given changed. */
        INPUTS,
        /** A run was matched by its number because its key (step name or driver label) changed. */
        RUN,
        /** The golden results have nothing for this test. */
        NOT_RECORDED;

        String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    public static final Set<Kind> DEFAULT_FAILING = EnumSet.of(Kind.CHANGED, Kind.MISSING, Kind.NEW);
    private static final int MAX_LINES = 10;

    /** How to compare (the defaults suit a cross-line check). */
    public static final class Options {
        /** Compare every outcome instead of the final ones ({@code golden.outcomes=all}). */
        public boolean allOutcomes;
        /** Let differences at places of midPoint's baseline objects fail ({@code golden.baseline=compare}). */
        public boolean compareBaseline;
        /**
         * Compare what the final outcomes leave in the new state ({@code option new-state} in the accepted file): a
         * difference only in what depends on the old state is {@link Kind#OLD_STATE}.
         */
        public boolean newState;
        /**
         * Places whose identity changed between the lines' configurations ({@code option moved <golden> -> <now>} in
         * the accepted file): the golden results of the first are compared with the results of the second.
         */
        public final Map<String, String> moved = new LinkedHashMap<>();

        public static Options defaults() {
            return new Options();
        }
    }

    /** One difference at one place of one run. */
    public static final class Difference {
        public final Kind kind;
        public final int run;
        public final String runKey;
        public final String operation;
        public final String identity;
        public final Set<String> flags;
        /** What only the golden results have (outcomes, inputs, the count). */
        public final List<String> golden;
        /** What only the results of this run have. */
        public final List<String> actual;
        /**
         * Why the difference is a note whatever its kind (a baseline place, an accepted difference, a test recorded
         * while failing), or null.
         */
        public String note;

        Difference(Kind kind, int run, String runKey, String operation, String identity, Set<String> flags,
                Collection<String> golden, Collection<String> actual) {
            this.kind = kind;
            this.run = run;
            this.runKey = runKey;
            this.operation = operation;
            this.identity = identity;
            this.flags = flags;
            this.golden = List.copyOf(golden);
            this.actual = List.copyOf(actual);
        }

        boolean fails(Set<Kind> failing) {
            return note == null && failing.contains(kind);
        }

        @Override
        public String toString() {
            return kind.label() + " run " + run + " " + identity + ": " + golden + " -> " + actual
                    + (note != null ? " (" + note + ")" : "");
        }
    }

    /**
     * A difference the user acknowledged, one line of an accepted-differences file:
     * {@code <kind> | <class>.<test> | <run key> | <identity>  # reason}; {@code *} matches any text in a field.
     */
    public static final class Acceptance {
        final Kind kind;
        /** The {@code <class>.<test>} field as written. */
        final String testField;
        final Pattern test;
        final Pattern run;
        final Pattern identity;
        final String reason;

        Acceptance(Kind kind, String test, String run, String identity, String reason) {
            this.kind = kind;
            this.testField = test;
            this.test = glob(test);
            this.run = glob(run);
            this.identity = glob(identity);
            this.reason = reason;
        }

        /**
         * Whether it matches a difference of the test {@code <class>.<method>}, or of one invocation of a repeated or
         * parameterized test ({@code <class>.<method> repetition 1 of 4}, {@code <class>.<method> [1] ...}).
         */
        boolean matches(String testName, Difference d) {
            int invocation = testName.indexOf(' ');
            return (kind == null || kind == d.kind)
                    && (test.matcher(testName).matches()
                            || invocation > 0 && test.matcher(testName.substring(0, invocation)).matches())
                    && run.matcher(d.runKey).matches() && identity.matcher(d.identity).matches();
        }

        private static Pattern glob(String text) {
            List<String> parts = new ArrayList<>();
            for (String part : text.split("\\*", -1)) {
                parts.add(part.isEmpty() ? "" : Pattern.quote(part));
            }
            return Pattern.compile(String.join(".*", parts));
        }
    }

    private static final Pattern COMMENT = Pattern.compile("(?:^|\\s)(#)(?:\\s|$)");

    private GoldenComparison() {
    }

    /**
     * An option of an accepted-differences file, {@code option <name> [<value>]  # reason}: how the pair is compared.
     * {@code new-state} ({@link Options#newState}) for the whole pair; {@code moved <golden identity> -> <identity>}
     * ({@link Options#moved}) for one place.
     */
    public static final class Option {
        public final String name;
        /** For {@code moved}: the golden identity and the identity now; else empty. */
        public final List<String> values;
        public final String reason;

        Option(String name, List<String> values, String reason) {
            this.name = name;
            this.values = values;
            this.reason = reason;
        }
    }

    public static final String NEW_STATE = "new-state";
    public static final String MOVED = "moved";
    private static final String OPTION = "option ";
    private static final String ARROW = " -> ";

    /** The options of an accepted-differences file's text. */
    public static List<Option> parseOptions(String text) {
        List<Option> out = new ArrayList<>();
        int number = 0;
        for (String raw : text.split("\r?\n")) {
            number++;
            String[] line = stripComment(raw);
            if (line[0].strip().startsWith(OPTION)) {
                String option = line[0].strip().substring(OPTION.length()).strip();
                String name = option.split("\\s+", 2)[0];
                String value = option.substring(name.length()).strip();
                String where = "Accepted differences, line " + number + ": ";
                if (NEW_STATE.equals(name) && value.isEmpty()) {
                    out.add(new Option(name, List.of(), line[1]));
                } else if (MOVED.equals(name) && value.contains(ARROW)) {
                    int arrow = value.indexOf(ARROW);
                    out.add(new Option(name, List.of(value.substring(0, arrow).strip(),
                            value.substring(arrow + ARROW.length()).strip()), line[1]));
                } else if (MOVED.equals(name)) {
                    throw new IllegalArgumentException(where + "expected option moved <golden identity> -> <identity>");
                } else {
                    throw new IllegalArgumentException(where + "unknown option " + option + " (known: " + NEW_STATE
                            + ", " + MOVED + ")");
                }
            }
        }
        return out;
    }

    /** Sets the options of an accepted-differences file. */
    public static void apply(List<Option> fileOptions, Options options) {
        for (Option option : fileOptions) {
            if (NEW_STATE.equals(option.name)) {
                options.newState = true;
            } else if (MOVED.equals(option.name)) {
                options.moved.put(option.values.get(0), option.values.get(1));
            }
        }
    }

    /** {@code [line without comment, reason]}. */
    private static String[] stripComment(String raw) {
        // a comment is a '#' at the line start or after a blank, and before a blank or the line end: identities have
        // '#' in ordinals ([#3]), run keys in repetitions (#2)
        Matcher comment = COMMENT.matcher(raw);
        int hash = comment.find() ? comment.start(1) : -1;
        return hash >= 0 ? new String[] {raw.substring(0, hash), raw.substring(hash + 1).strip()}
                : new String[] {raw, ""};
    }

    /**
     * The accepted differences of a file's text; blank lines, {@code #} comments and options
     * ({@link #parseOptions}) are skipped.
     */
    public static List<Acceptance> parseAccepted(String text) {
        List<Acceptance> out = new ArrayList<>();
        int number = 0;
        for (String raw : text.split("\r?\n")) {
            number++;
            String[] stripped = stripComment(raw);
            String line = stripped[0];
            String reason = stripped[1];
            if (line.isBlank() || line.strip().startsWith(OPTION)) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4) {
                throw new IllegalArgumentException("Accepted differences, line " + number
                        + ": expected <kind> | <class>.<test> | <run key> | <identity>, got: " + raw);
            }
            String kind = fields[0].strip();
            out.add(new Acceptance("*".equals(kind) ? null : Kind.valueOf(kind.toUpperCase(Locale.ROOT)),
                    fields[1].strip(), fields[2].strip(), fields[3].strip(), reason));
        }
        return out;
    }

    /** Marks the differences of the test that an acceptance matches as notes. */
    public static void accept(String testName, List<Difference> differences, List<Acceptance> accepted) {
        for (Difference d : differences) {
            if (d.note != null) {
                continue;
            }
            for (Acceptance a : accepted) {
                if (a.matches(testName, d)) {
                    d.note = "accepted" + (a.reason.isEmpty() ? "" : ": " + a.reason);
                    break;
                }
            }
        }
    }

    public static List<Difference> compare(TestResult golden, TestResult actual) {
        return compare(golden, actual, Options.defaults());
    }

    /** The differences, in the order of runs and places; {@code golden} null when nothing was recorded. */
    public static List<Difference> compare(TestResult golden, TestResult actual, Options options) {
        List<Difference> out = new ArrayList<>();
        if (golden == null) {
            out.add(new Difference(Kind.NOT_RECORDED, 0, "", "", "", Set.of(), List.of(), List.of()));
            return out;
        }
        Map<String, RunResult> before = byKey(golden);
        Map<String, RunResult> now = byKey(actual);
        // runs ordered by the actual number (else the golden one); matched by key, then left-overs by number
        TreeMap<Integer, RunResult[]> pairs = new TreeMap<>();
        Map<Integer, RunResult> unmatchedBefore = new TreeMap<>();
        for (RunResult b : before.values()) {
            RunResult a = now.get(b.key);
            if (a == null) {
                unmatchedBefore.put(b.index, b);
            }
        }
        for (RunResult a : now.values()) {
            RunResult b = before.get(a.key);
            if (b == null) {
                b = unmatchedBefore.remove(a.index);
                if (b != null) {
                    out.add(new Difference(Kind.RUN, a.index, a.key, a.operation, "", Set.of(), List.of(b.key),
                            List.of(a.key)));
                }
            }
            pairs.put(a.index * 2 + 1, new RunResult[] {b, a});
        }
        unmatchedBefore.values().forEach(b -> pairs.put(b.index * 2, new RunResult[] {b, null}));
        for (RunResult[] pair : pairs.values()) {
            comparePlaces(pair[0], pair[1], options, out);
        }
        for (Difference d : out) {
            if (!options.compareBaseline && d.flags.contains("baseline")) {
                d.note = "in midPoint's own objects";
            }
        }
        return out;
    }

    private static Map<String, RunResult> byKey(TestResult test) {
        Map<String, RunResult> out = new LinkedHashMap<>();
        test.runs.values().forEach(r -> out.put(r.key, r));
        return out;
    }

    private static void comparePlaces(RunResult before, RunResult now, Options options, List<Difference> out) {
        RunResult shown = now != null ? now : before;
        Map<String, Place> golden = before != null ? moved(before.places, options.moved) : Map.of();
        SortedSet<String> places = new TreeSet<>(golden.keySet());
        if (now != null) {
            places.addAll(now.places.keySet());
        }
        for (String identity : places) {
            Place a = golden.get(identity);
            Place b = now != null ? now.places.get(identity) : null;
            boolean moved = a != null && !a.identity.equals(identity);
            if (b == null) {
                out.add(difference(Kind.MISSING, shown, identity, flags(a, null, moved), outcomes(a, options),
                        List.of()));
            } else if (a == null) {
                out.add(difference(Kind.NEW, shown, identity, b.flags, List.of(), outcomes(b, options)));
            } else {
                Set<String> flags = flags(a, b, moved);
                SortedSet<String> outA = outcomes(a, options);
                SortedSet<String> outB = outcomes(b, options);
                if (!outA.equals(outB)) {
                    Function<String, String> newState = o -> GoldenResults.coarse(GoldenResults.newState(o));
                    Kind kind = map(outA, GoldenResults::effective).equals(map(outB, GoldenResults::effective))
                            ? Kind.OLD
                            : map(outA, GoldenResults::coarse).equals(map(outB, GoldenResults::coarse))
                            ? Kind.TEXT
                            : options.newState && map(outA, newState).equals(map(outB, newState))
                            ? Kind.OLD_STATE : Kind.CHANGED;
                    out.add(difference(kind, shown, identity, flags, minus(outA, outB), minus(outB, outA)));
                }
                if (a.count != b.count) {
                    out.add(difference(Kind.COUNT, shown, identity, flags, List.of(String.valueOf(a.count)),
                            List.of(String.valueOf(b.count))));
                }
                if (!a.inputs.equals(b.inputs)) {
                    out.add(inputs(shown, identity, flags, a, b));
                }
            }
        }
    }

    /** The golden places by identity, those of a moved place under the identity it has now. */
    private static Map<String, Place> moved(Map<String, Place> places, Map<String, String> moved) {
        Map<String, Place> out = new TreeMap<>(places);
        moved.forEach((from, to) -> {
            if (out.containsKey(from) && !places.containsKey(to)) {
                out.put(to, out.remove(from));
            }
        });
        return out;
    }

    private static Set<String> flags(Place a, Place b, boolean moved) {
        Set<String> flags = new TreeSet<>(a.flags);
        if (b != null) {
            flags.addAll(b.flags);
        }
        if (moved) {
            flags.add("moved from " + a.identity);
        }
        return flags;
    }

    private static Difference difference(Kind kind, RunResult run, String identity, Set<String> flags,
            Collection<String> golden, Collection<String> actual) {
        return new Difference(kind, run.index, run.key, run.operation, identity, flags, golden, actual);
    }

    private static SortedSet<String> outcomes(Place place, Options options) {
        return options.allOutcomes ? place.allOutcomes() : place.outputs;
    }

    private static Set<String> map(Set<String> outcomes, Function<String, String> f) {
        return outcomes.stream().map(f).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The inputs only one side has; changed variable names as {@code vars -removed +added}. */
    private static Difference inputs(RunResult run, String identity, Set<String> flags, Place a, Place b) {
        List<String> golden = minus(a.inputs, b.inputs);
        List<String> actual = minus(b.inputs, a.inputs);
        String varsA = vars(golden);
        String varsB = vars(actual);
        if (varsA != null && varsB != null) {
            List<String> namesA = List.of(varsA.substring(5).split(", "));
            List<String> namesB = List.of(varsB.substring(5).split(", "));
            golden.remove(varsA);
            actual.remove(varsB);
            golden.add(0, "vars " + minus(namesA, namesB).stream().map(n -> "-" + n).collect(Collectors.joining(" ")));
            actual.add(0, "vars " + minus(namesB, namesA).stream().map(n -> "+" + n).collect(Collectors.joining(" ")));
        }
        return difference(Kind.INPUTS, run, identity, flags, golden, actual);
    }

    private static String vars(List<String> inputs) {
        return inputs.stream().filter(i -> i.startsWith("vars ")).findFirst().orElse(null);
    }

    private static List<String> minus(Collection<String> a, Collection<String> b) {
        return a.stream().filter(x -> !b.contains(x)).collect(Collectors.toList());
    }

    /** Whether some difference fails: of a failing kind and not a note for another reason. */
    public static boolean fails(List<Difference> differences, Set<Kind> failing) {
        return differences.stream().anyMatch(d -> d.fails(failing));
    }

    /**
     * A Markdown section: a heading with the test and the verdict, then per run the differences (failing ones first,
     * then notes, then those in midPoint's own objects).
     */
    public static String markdown(String test, List<Difference> differences, Set<Kind> failing) {
        return markdown(test, differences, failing, null);
    }

    /** As {@link #markdown(String, List, Set)}, with a remark under the heading (null for none). */
    public static String markdown(String test, List<Difference> differences, Set<Kind> failing, String remark) {
        long failures = differences.stream().filter(d -> d.fails(failing)).count();
        StringBuilder md = new StringBuilder("### ").append(test).append(" - ")
                .append(failures > 0 ? "FAILED" : "passed").append(" (").append(failures).append(" failing, ")
                .append(differences.size() - failures).append(" notes)\n");
        if (remark != null) {
            md.append('\n').append(remark).append('\n');
        }
        int run = -1;
        List<Difference> ordered = new ArrayList<>(differences);
        ordered.sort((x, y) -> x.run != y.run ? Integer.compare(x.run, y.run)
                : Integer.compare(rank(x, failing), rank(y, failing)));
        for (Difference d : ordered) {
            if (d.kind == Kind.NOT_RECORDED) {
                md.append("\nNo golden results for this test (record them with golden.mode=record).\n");
                continue;
            }
            if (d.run != run) {
                run = d.run;
                md.append("\nRun ").append(d.run).append(": `").append(d.runKey).append('`');
                if (!d.runKey.equals(d.operation)) {
                    md.append(" (`").append(d.operation).append("`)");
                }
                md.append("\n\n");
            }
            if (d.kind == Kind.RUN) {
                md.append("- note: run matched by number, its key changed\n");
                lines(md, "golden", d.golden);
                lines(md, "now", d.actual);
                continue;
            }
            md.append("- ").append(d.fails(failing) ? "**" + d.kind.label() + "**" : "note: " + d.kind.label())
                    .append(" `").append(d.identity).append('`');
            if (!d.flags.isEmpty()) {
                md.append(" _(").append(String.join(", ", d.flags)).append(")_");
            }
            if (d.note != null) {
                md.append(" - ").append(d.note);
            }
            md.append('\n');
            switch (d.kind) {
                case COUNT:
                    md.append("  - evaluations: ").append(d.golden.get(0)).append(" -> ").append(d.actual.get(0))
                            .append('\n');
                    break;
                case MISSING:
                    lines(md, "golden", d.golden);
                    break;
                case NEW:
                    lines(md, "now", d.actual);
                    break;
                default:
                    lines(md, "golden", d.golden);
                    lines(md, "now", d.actual);
                    break;
            }
        }
        return md.toString();
    }

    /** Failing first, then notes, then the places of midPoint's own objects. */
    private static int rank(Difference d, Set<Kind> failing) {
        return d.fails(failing) ? 0 : d.flags.contains("baseline") ? 2 : 1;
    }

    private static void lines(StringBuilder md, String label, List<String> lines) {
        for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
            md.append("  - ").append(label).append(": `").append(lines.get(i)).append("`\n");
        }
        if (lines.size() > MAX_LINES) {
            md.append("  - ").append(label).append(": ... ").append(lines.size() - MAX_LINES).append(" more\n");
        }
        if (lines.isEmpty()) {
            md.append("  - ").append(label).append(": (nothing)\n");
        }
    }

    /** {@code changed,missing,new} (also {@code old}, {@code text}, {@code count}, {@code inputs}, {@code run}...); {@code none}. */
    public static Set<Kind> parseKinds(String text) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (String part : text.split(",")) {
            String name = part.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (!name.isEmpty() && !"NONE".equals(name)) {
                kinds.add(Kind.valueOf(name));
            }
        }
        return kinds;
    }
}
