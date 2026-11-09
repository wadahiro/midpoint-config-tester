package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;


import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.Identity;
import com.github.wadahiro.midpoint.tester.Normalizer;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * The golden results of one test class: per test method, per driver call (run) and per configuration place
 * ({@link Identity}), what midPoint computed there, normalized ({@link Normalizer}).
 *
 * Runs are keyed by the test's step name ({@code MidPoint.step}) or else by the driver's label (line-neutral: objects
 * by name, never by generated OID or container ID; deltas by change type, object and modified paths), {@code #2},
 * {@code #3}... for repeated keys; the number
 * (the n-th driver call of the test) orders them and is the fallback when keys differ.
 *
 * Per place:
 * <ul>
 *   <li>{@code out}: the final outcomes, compared by default: per focus object the outcome of its last evaluation
 *       (earlier projector waves differ between lines and are noise), as a set over the focus objects (a task
 *       evaluates a place for many objects, in an order that is not stable); evaluations without a focus all count.
 *       A nested place (an expression in a filter, a function's argument) keeps per focus object the outcomes of all
 *       its evaluations within the last evaluation of its root (outermost enclosing) evaluation: a search evaluates
 *       its filter once per value, in an order that is not stable.
 *       An outcome is the condition old/new, then the new value set (zero + plus), the removed values (old - new) and,
 *       after {@code |}, the old value set (zero + minus) when it differs from the new one; or the error, or
 *       {@code no output};</li>
 *   <li>{@code all}: the other (intermediate) outcomes, compared only with {@code golden.outcomes=all};</li>
 *   <li>{@code count}: how many times it was evaluated (projector waves, objects of a task...);</li>
 *   <li>{@code vars}: the names of the variables it was given (all its evaluations together); {@code src}: the
 *       distinct old and new values of each mapping source, at most {@value #MAX_INPUTS} lines. Variable values are
 *       left out: they are whole objects ({@code focus}, {@code shadow}...) given under several aliases, whose short
 *       text changes from wave to wave and from line to line; what a script makes of them is in its output;</li>
 *   <li>{@code flags}: {@code ordinal} when the identity depends on the order of siblings, {@code unlocated} when the
 *       place was not found in the configuration files, {@code baseline} when it is in one of midPoint's own objects
 *       of the runtime baseline (archetypes...) as the baseline has it, not in the tested configuration (an object
 *       the test re-imported with a baseline OID, e.g. its own system configuration, is tested configuration; see
 *       {@link #isBaseline}).</li>
 * </ul>
 * A mapping's own expression and condition are not places of their own here: the mapping has their outcome. A test
 * that failed is marked ({@code failed <message>}): its results stop where it failed.
 *
 * The text form ({@link #write()}, {@link #parse}) has one fact per line, sorted, so that a diff of two files is the
 * list of changes:
 * <pre>
 * line 4.8
 *
 * test inboundMapping
 *   run 1 import account jdoe
 *     place resource:HR/schemaHandling/objectType[account/default]/attribute[ri:fullname]/inbound[hr-familyName]
 *       count 1
 *       out condition true->true new=["Doe"]
 *       src input = [" John  DOE "] -> [" John  DOE "]
 *       vars account, configuration, focus, operation, projection, resource, shadow, user
 * </pre>
 */
public final class GoldenResults {

    /** The key of the runs of a class's {@code @BeforeAll} methods. */
    public static final String BEFORE_ALL = "@BeforeAll";
    static final int MAX_INPUTS = 30;
    private static final int MAX_INPUT_LENGTH = 200;

    /** One configuration place evaluated in a run. */
    public static final class Place {
        public final String identity;
        public final SortedSet<String> flags = new TreeSet<>();
        public int count;
        /** The final outcomes (see the class description). */
        public final SortedSet<String> outputs = new TreeSet<>();
        /** The other outcomes of intermediate evaluations. */
        public final SortedSet<String> intermediate = new TreeSet<>();
        /** {@code vars ...}, {@code src ...} and {@code more ...} lines. */
        public final SortedSet<String> inputs = new TreeSet<>();
        final SortedSet<String> variables = new TreeSet<>();
        /**
         * While recording: per focus object the outcomes of its last evaluation, or of its evaluations within the last
         * evaluation of their root (outermost enclosing) evaluation; and the outcomes without a focus.
         */
        private final Map<String, Last> lastBySubject = new LinkedHashMap<>();
        private final SortedSet<String> withoutSubject = new TreeSet<>();
        private final SortedSet<String> every = new TreeSet<>();

        Place(String identity) {
            this.identity = identity;
        }

        /** Every outcome, final and intermediate. */
        public SortedSet<String> allOutcomes() {
            SortedSet<String> out = new TreeSet<>(outputs);
            out.addAll(intermediate);
            return out;
        }
    }

    /** The final outcomes of a place for one focus object: those within one evaluation of their root. */
    private static final class Last {
        final Evaluation root;
        final SortedSet<String> outcomes = new TreeSet<>();

        Last(Evaluation root) {
            this.root = root;
        }
    }

    /** The places one driver call evaluated. */
    public static final class RunResult {
        public final int index;
        /** What runs are matched by: the step name or the label, with {@code #n} when repeated. */
        public final String key;
        /** The driver's label (display only). */
        public String operation;
        public final SortedMap<String, Place> places = new TreeMap<>();

        RunResult(int index, String key, String operation) {
            this.index = index;
            this.key = key;
            this.operation = operation;
        }
    }

    /** The runs of one test method (or of {@link #BEFORE_ALL}) that evaluated something, by number. */
    public static final class TestResult {
        public final String test;
        public final SortedMap<Integer, RunResult> runs = new TreeMap<>();
        /** The first line of the test's failure when it was recorded, or null. */
        public String failure;

        TestResult(String test) {
            this.test = test;
        }
    }

    private String line;
    private final SortedMap<String, TestResult> tests = new TreeMap<>();

    public GoldenResults(String line) {
        this.line = line;
    }

    /** The midPoint line the results were recorded with. */
    public String line() {
        return line;
    }

    public SortedMap<String, TestResult> tests() {
        return tests;
    }

    public void put(TestResult test) {
        tests.put(test.test, test);
    }

    /** The other's tests replace these with the same key; the line becomes the other's. */
    public void merge(GoldenResults other) {
        tests.putAll(other.tests);
        line = other.line;
    }

    // ---- from runs ------------------------------------------------------------------------------------------

    /** The results of these runs (in the order of the driver calls), normalized together. */
    public static TestResult of(String test, List<Run> runs) {
        return of(test, runs, null);
    }

    /** As {@link #of(String, List)}, marked with the test's failure (null if it passed). */
    public static TestResult of(String test, List<Run> runs, Throwable failure) {
        List<Evaluation> all = new ArrayList<>();
        runs.forEach(run -> all.addAll(run.evaluations()));
        Normalizer normalizer = Normalizer.of(all);
        TestResult result = new TestResult(test);
        result.failure = failure != null ? firstLine(failure) : null;
        Map<String, Integer> keys = new HashMap<>();
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            String label = oneLine(normalizer.text(run.operation())).replaceAll("\\b(\\w+)Impl\\b", "$1");
            String key = run.step() != null ? oneLine(run.step()) : label;
            int n = keys.merge(key, 1, Integer::sum);
            RunResult runResult = new RunResult(i + 1, n > 1 ? key + " #" + n : key, label);
            for (Evaluation evaluation : run.evaluations()) {
                if (!isMappingPart(evaluation)) {
                    add(runResult, evaluation, normalizer);
                }
            }
            if (!runResult.places.isEmpty()) {
                result.runs.put(runResult.index, runResult);
            }
        }
        result.runs.values().forEach(r -> r.places.values().forEach(GoldenResults::finish));
        return result;
    }

    private static String firstLine(Throwable failure) {
        String text = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        String[] lines = text.split("\\R", 2);
        return oneLine(lines[0].strip());
    }

    /** A mapping's expression or condition: the mapping's record has their outcome. */
    static boolean isMappingPart(Evaluation evaluation) {
        Evaluation parent = evaluation.parent();
        return !evaluation.isMapping() && parent != null && parent.isMapping()
                && ("expression".equals(evaluation.part()) || "condition".equals(evaluation.part()));
    }

    private static void add(RunResult run, Evaluation evaluation, Normalizer normalizer) {
        Identity identity = evaluation.identity();
        Place place = run.places.computeIfAbsent(oneLine(identity.toString()), Place::new);
        if (identity.hasOrdinal()) {
            place.flags.add("ordinal");
        }
        if (!identity.isLocated()) {
            place.flags.add("unlocated");
        }
        if (isBaseline(evaluation, identity)) {
            place.flags.add("baseline");
        }
        place.count++;
        String outcome = oneLine(outcome(evaluation, normalizer));
        place.every.add(outcome);
        String subject = subject(evaluation);
        if (subject != null) {
            // a nested place (a search's filter, a function's argument) can be evaluated several times within one
            // evaluation of its root, e.g. once per value in an order that is not stable: they all count
            Evaluation root = root(evaluation);
            Last last = place.lastBySubject.get(subject);
            if (last == null || last.root != root) {
                last = new Last(root);
                place.lastBySubject.put(subject, last);
            }
            last.outcomes.add(outcome);
        } else {
            place.withoutSubject.add(outcome);
        }
        place.variables.addAll(evaluation.variables().keySet());
        evaluation.sources().forEach((name, source) -> place.inputs.add(cap("src " + name + " = "
                + normalizer.values(source.oldValues()) + " -> " + normalizer.values(source.newValues()))));
    }

    /**
     * Whether an evaluation is of midPoint's own baseline content (the objects every runtime starts with,
     * {@link Baseline}), not of the tested configuration: its owner has the OID of a baseline object, it was not
     * located in a configuration file (the baseline objects are never registered with the index, so a located place
     * is in a file the test imported), and no registered file has an object with that OID (a test that imports its
     * own system configuration, OID ...0001, replaces the baseline's: what midPoint evaluates in it then is the test's,
     * located or not). Call it before the runtime's reset, while the index is the test's.
     */
    static boolean isBaseline(Evaluation evaluation, Identity identity) {
        String ownerOid = evaluation.owner() != null ? evaluation.owner().oid() : null;
        return ownerOid != null && Baseline.oids().contains(ownerOid) && !identity.isLocated()
                && !ConfigIndex.get().hasObject(ownerOid);
    }

    /** The outermost evaluation this one ran in, or itself. */
    static Evaluation root(Evaluation evaluation) {
        Evaluation root = evaluation;
        while (root.parent() != null) {
            root = root.parent();
        }
        return root;
    }

    /**
     * The focus object an evaluation is about (its name, else its OID), or null: the final outcome of a place is the
     * last one per focus object.
     */
    static String subject(Evaluation e) {
        if (!e.hasVariable("focus")) {
            return null;
        }
        Evaluation.Owner focus = FocusResolver.ofVariables(e.variables());
        if (focus == null) {
            return null;
        }
        return focus.name() != null ? focus.name() : focus.oid();
    }

    /**
     * Condition old/new, then the new values, the removed ones (old - new) and the old values when they differ from
     * the new ones; or the error, or {@code no output}.
     */
    static String outcome(Evaluation e, Normalizer normalizer) {
        StringBuilder sb = new StringBuilder();
        if (e.conditionOld() != null || e.conditionNew() != null) {
            sb.append("condition ").append(e.conditionOld()).append("->").append(e.conditionNew()).append(' ');
        }
        if (e.error() != null) {
            sb.append("error ").append(normalizer.error(e.error()));
        } else if (e.hasOutput()) {
            SortedSet<String> oldValues = normalizer.oldValues(e);
            SortedSet<String> newValues = normalizer.newValues(e);
            SortedSet<String> removed = new TreeSet<>(oldValues);
            removed.removeAll(newValues);
            sb.append("new=").append(newValues);
            if (!removed.isEmpty()) {
                sb.append(" removed=").append(removed);
            }
            if (!oldValues.equals(newValues)) {
                sb.append(OLD_SEPARATOR).append(oldValues);
            }
        } else {
            sb.append("no output");
        }
        return sb.toString();
    }

    private static final String OLD_SEPARATOR = " | old=";
    private static final Pattern CONDITION_OLD = Pattern.compile("^condition \\w+->");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("error ([\\w$.]+): .*$");

    /**
     * What an outcome does: the condition's new value, the new and the removed values, or the error; the old values
     * and the old condition left out. Two lines that differ only there compute the same result.
     */
    public static String effective(String outcome) {
        int old = outcome.indexOf(OLD_SEPARATOR);
        String out = old >= 0 ? outcome.substring(0, old) : outcome;
        return CONDITION_OLD.matcher(out).replaceFirst("condition ->");
    }

    /**
     * What an outcome leaves in the new state: {@link #effective} without the removed values, and only the condition
     * when its new value is false (unless there is an error). Two outcomes that differ only in what they compare with the old state (whose old
     * state, operation start or previous wave, differs between lines) give the same new state.
     */
    public static String newState(String outcome) {
        String out = effective(outcome);
        if (out.startsWith(CONDITION_FALSE + " ") && !out.startsWith(CONDITION_FALSE + " error ")) {
            return CONDITION_FALSE;
        }
        int removed = out.indexOf(REMOVED_SEPARATOR);
        return removed >= 0 ? out.substring(0, removed) : out;
    }

    private static final String CONDITION_FALSE = "condition ->false";
    private static final String REMOVED_SEPARATOR = " removed=[";

    /**
     * {@link #effective} without text that differs between lines for the same thing: error messages (only the
     * class is kept) and the JSON form of containers ({@code {...}}).
     */
    public static String coarse(String outcome) {
        String out = ERROR_MESSAGE.matcher(effective(outcome)).replaceFirst("error $1");
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == '{') {
                if (depth++ == 0) {
                    sb.append("{...}");
                }
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The final and intermediate outcomes, the inputs limited. */
    private static void finish(Place place) {
        place.lastBySubject.values().forEach(last -> place.outputs.addAll(last.outcomes));
        place.outputs.addAll(place.withoutSubject);
        place.intermediate.addAll(place.every);
        place.intermediate.removeAll(place.outputs);
        // the recording state holds root evaluations (live objects): not kept with the results
        place.lastBySubject.clear();
        place.withoutSubject.clear();
        place.every.clear();
        limitInputs(place);
    }

    /** At most {@value #MAX_INPUTS} source lines (the first in sort order), then the variable names. */
    private static void limitInputs(Place place) {
        if (place.inputs.size() > MAX_INPUTS) {
            List<String> kept = new ArrayList<>(place.inputs).subList(0, MAX_INPUTS);
            int more = place.inputs.size() - MAX_INPUTS;
            place.inputs.retainAll(kept);
            place.inputs.add("more " + more + " inputs");
        }
        if (!place.variables.isEmpty()) {
            place.inputs.add("vars " + String.join(", ", place.variables));
        }
    }

    private static String cap(String input) {
        String text = oneLine(input);
        return text.length() <= MAX_INPUT_LENGTH ? text
                : text.substring(0, MAX_INPUT_LENGTH) + "... (" + text.length() + " chars)";
    }

    /** Line breaks and backslashes escaped: every fact is one line of the file. */
    static String oneLine(String text) {
        return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    // ---- text form ------------------------------------------------------------------------------------------

    /** The file content; {@code title} goes into the comment at the top. */
    public String write(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Golden results of ").append(title).append(" (midPoint tester, docs/redesign.md step 4).\n")
                .append("# Per test, driver call (run) and configuration place: out = final outcomes (compared),\n")
                .append("# all = intermediate ones, count = evaluations, vars/src = inputs (differences are notes).\n")
                .append("line ").append(line).append('\n');
        for (TestResult test : tests.values()) {
            sb.append("\ntest ").append(test.test).append('\n');
            if (test.failure != null) {
                sb.append("  failed ").append(test.failure).append('\n');
            }
            for (RunResult run : test.runs.values()) {
                sb.append("  run ").append(run.index).append(' ').append(run.key).append('\n');
                if (!run.key.equals(run.operation)) {
                    sb.append("    label ").append(run.operation).append('\n');
                }
                for (Place place : run.places.values()) {
                    sb.append("    place ").append(place.identity).append('\n');
                    if (!place.flags.isEmpty()) {
                        sb.append("      flags ").append(String.join(",", place.flags)).append('\n');
                    }
                    sb.append("      count ").append(place.count).append('\n');
                    place.outputs.forEach(out -> sb.append("      out ").append(out).append('\n'));
                    place.intermediate.forEach(out -> sb.append("      all ").append(out).append('\n'));
                    place.inputs.forEach(input -> sb.append("      ").append(input).append('\n'));
                }
            }
        }
        return sb.toString();
    }

    /** Reads what {@link #write} wrote. */
    public static GoldenResults parse(String text) {
        GoldenResults results = new GoldenResults("?");
        TestResult test = null;
        RunResult run = null;
        Place place = null;
        int number = 0;
        for (String raw : text.split("\r?\n", -1)) {
            number++;
            String line = raw.stripLeading();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            String keyword = space < 0 ? line : line.substring(0, space);
            String rest = space < 0 ? "" : line.substring(space + 1);
            try {
                switch (keyword) {
                    case "line":
                        results.line = rest;
                        break;
                    case "test":
                        test = new TestResult(rest);
                        results.tests.put(rest, test);
                        run = null;
                        place = null;
                        break;
                    case "run":
                        int blank = rest.indexOf(' ');
                        int index = Integer.parseInt(blank < 0 ? rest : rest.substring(0, blank));
                        String key = blank < 0 ? "" : rest.substring(blank + 1);
                        run = new RunResult(index, key, key);
                        test.runs.put(index, run);
                        place = null;
                        break;
                    case "failed":
                        test.failure = rest;
                        break;
                    case "label":
                        run.operation = rest;
                        break;
                    case "place":
                        place = new Place(rest);
                        run.places.put(rest, place);
                        break;
                    case "flags":
                        place.flags.addAll(List.of(rest.split(",")));
                        break;
                    case "count":
                        place.count = Integer.parseInt(rest);
                        break;
                    case "out":
                        place.outputs.add(rest);
                        break;
                    case "all":
                        place.intermediate.add(rest);
                        break;
                    case "vars":
                    case "src":
                    case "more":
                        place.inputs.add(line);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown keyword " + keyword);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Golden results, line " + number + " (" + raw + "): "
                        + e.getMessage(), e);
            }
        }
        return results;
    }
}
