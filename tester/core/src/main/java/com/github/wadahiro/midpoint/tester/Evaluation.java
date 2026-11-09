package com.github.wadahiro.midpoint.tester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.github.wadahiro.midpoint.tester.internal.ConfigIndex;
import com.github.wadahiro.midpoint.tester.internal.FocusResolver;
import com.github.wadahiro.midpoint.tester.internal.Record;

/**
 * One evaluation midPoint ran during a driver call: a mapping (with its condition and expression nested in it) or an
 * expression evaluator outside of a mapping (correlation, synchronization reaction, assignment condition, policy,
 * approver, search filter, function...).
 *
 * <ul>
 *   <li>Identity: {@link #identity()} (owner object and structural position in it, e.g.
 *       {@code resource:HR/schemaHandling/objectType[account/default]/attribute[ri:mail]/outbound[hr-mail]}),
 *       {@link #mappingName()}, {@link #owner()} (the role, resource or template holding it) and {@link #location()}
 *       (file and line, for display).</li>
 *   <li>Inputs as midPoint gave them: {@link #variables()} and {@link #sources()} are the live objects (not copies);
 *       compare them while the test runs, use {@link Normalizer} for text.</li>
 *   <li>Outputs: the triple as sets ({@link #newValues()} = zero + plus, {@link #oldValues()} = zero + minus), the
 *       condition old/new, or the {@link #error()}.</li>
 *   <li>Nesting: {@link #parent()} and {@link #nested()}; an expression in a search filter is nested in the expression
 *       that searches, which is nested in its mapping.</li>
 * </ul>
 */
public final class Evaluation {

    public enum Kind {
        /** A mapping: name, owner, sources, condition and output of the whole mapping. */
        MAPPING,
        /** One run of an expression evaluator ({@code script}, {@code path}, {@code assignmentTargetSearch}, ...). */
        EXPRESSION
    }

    /**
     * An object by type, OID and name: the object that holds the configuration ({@link #owner()}: its type
     * {@code RoleType}, or {@code role} from a file), or the focus an evaluation is about ({@link #focus()}).
     */
    public static final class Owner {
        private final String type;
        private final String oid;
        private final String name;

        public Owner(String type, String oid, String name) {
            this.type = type;
            this.oid = oid;
            this.name = name;
        }

        public String type() {
            return type;
        }

        public String oid() {
            return oid;
        }

        public String name() {
            return name;
        }

        /** Whether this is the object with this name or OID. */
        public boolean is(String nameOrOid) {
            return nameOrOid != null && (nameOrOid.equals(name) || nameOrOid.equals(oid));
        }

        @Override
        public String toString() {
            return type + ":" + name + "(" + oid + ")";
        }
    }

    /** The values of a mapping source (or an expression's source) before and after the change being processed. */
    public static final class SourceValues {
        private final List<Object> oldValues;
        private final List<Object> newValues;

        public SourceValues(List<Object> oldValues, List<Object> newValues) {
            this.oldValues = Collections.unmodifiableList(oldValues);
            this.newValues = Collections.unmodifiableList(newValues);
        }

        public List<Object> oldValues() {
            return simple(oldValues);
        }

        public List<Object> newValues() {
            return simple(newValues);
        }

        @Override
        public String toString() {
            return Normalizer.plain().values(oldValues) + " -> " + Normalizer.plain().values(newValues);
        }
    }

    private final Record record;

    /** Created by the recorder. */
    public Evaluation(Record record) {
        this.record = record;
    }

    public Kind kind() {
        return record.kind;
    }

    public boolean isMapping() {
        return record.kind == Kind.MAPPING;
    }

    /** Order of start within the driver call, from 1. */
    public int sequence() {
        return record.sequence;
    }

    /** The mapping's {@code <name>}; for an expression, the name of the mapping it is in (null if none). */
    public String mappingName() {
        Evaluation mapping = mapping();
        return mapping != null ? mapping.record.mappingName : null;
    }

    /** The mapping this evaluation is (in), or null. */
    public Evaluation mapping() {
        for (Record r = record; r != null; r = r.parent) {
            if (r.kind == Kind.MAPPING) {
                return r.view;
            }
        }
        return null;
    }

    /** midPoint's local description, e.g. {@code condition} or {@code expression} for the parts of a mapping. */
    public String part() {
        return record.part;
    }

    /** midPoint's context description, as is (diagnostics only: its wording differs between lines). */
    public String description() {
        return record.description;
    }

    /**
     * The first evaluator element, e.g. {@code script}, {@code path}, {@code value}; {@code asIs} for an expression
     * without one (midPoint's default); null for a mapping.
     */
    public String evaluator() {
        String fp = record.fingerprint;
        if (fp == null) {
            return record.kind == Kind.EXPRESSION ? "asIs" : null;
        }
        int brace = fp.indexOf('{');
        return brace < 0 ? fp : fp.substring(0, brace);
    }

    /** The canonical form of the evaluator elements (see {@code EvaluatorFingerprint}); null for a mapping. */
    public String fingerprint() {
        return record.fingerprint;
    }

    /** The mapping's target, e.g. {@code fullName} or {@code attributes/ri:title}; null when none. */
    public String target() {
        if (record.target != null || record.kind == Kind.MAPPING) {
            return record.target;
        }
        Evaluation mapping = mapping();
        return mapping != null ? mapping.target() : null;
    }

    /**
     * The object holding the configuration: as midPoint says for a mapping (inherited by what is nested in it),
     * otherwise the object of the file location; null when neither is known.
     */
    public Owner owner() {
        for (Record r = record; r != null; r = r.parent) {
            if (r.ownerOid != null) {
                return new Owner(r.ownerType, r.ownerOid, r.ownerName);
            }
        }
        Location location = location();
        return location.isKnown() ? new Owner(location.objectType(), location.objectOid(), location.objectName()) : null;
    }

    /**
     * The focus object this evaluation is about (type {@code UserType}, OID, name), decided for the outermost evaluation
     * it ran in and inherited by everything nested in it; null when there is none:
     * <ol>
     *   <li>the object of the {@code focus} variable (of a delta: the object after the change, else the one before: a
     *       user being deleted is still that user), when it has a name or an OID; the other one of the two is looked up
     *       when the run ends (a user the run added gets its OID);</li>
     *   <li>else the owner of the {@code shadow} ({@code projection}, {@code account}) when the run ends: the inbounds
     *       and correlation of an account imported or reconciled before its user exists belong to the user it got
     *       linked to;</li>
     *   <li>else none (an expression without focus or shadow; an account whose user was not created or linked).</li>
     * </ol>
     * Select by it with {@link Run#forFocus(String)} or {@link Selection#focus(String)}.
     */
    public Owner focus() {
        Record root = record;
        while (root.parent != null) {
            root = root.parent;
        }
        return root.focusResolved ? root.focus : FocusResolver.ofVariables(root.variables);
    }

    /** Where the element is in the files: file and line (for display; select by {@link #identity()}). */
    public Location location() {
        return record.location();
    }

    /**
     * Which mapping or expression of the configuration this is, independent of formatting (see {@link Identity}).
     * Evaluations of the same mapping (one per projector wave, per assignment path...) have equal identities.
     */
    public Identity identity() {
        Location location = location();
        Identity identity = location.isKnown() ? ConfigIndex.get().identity(location) : null;
        Evaluation parent = parent();
        if (identity != null) {
            // an expression located at its mapping: midPoint's default expression (asIs) of the mapping
            if (record.kind == Kind.EXPRESSION && parent != null && parent.location() == location) {
                return child(parent.identity(), record.part != null ? record.part : evaluator());
            }
            return identity;
        }
        if (record.kind == Kind.EXPRESSION && parent != null) {
            return child(parent.identity(), record.part != null ? record.part : evaluator());
        }
        List<Identity.Segment> segments = new ArrayList<>();
        if (record.elementPath != null) {
            for (String step : record.elementPath.split("/")) {
                int bracket = step.indexOf('[');
                segments.add(bracket < 0 ? new Identity.Segment(step, null, null, 0)
                        : new Identity.Segment(step.substring(0, bracket), null, null,
                                Integer.parseInt(step.substring(bracket + 1, step.length() - 1))));
            }
        } else if (record.kind == Kind.MAPPING) {
            segments.add(record.mappingName != null ? new Identity.Segment("mapping", "name", record.mappingName, 0)
                    : new Identity.Segment("mapping", "target", record.target, 0));
        } else {
            segments.add(new Identity.Segment(evaluator(), "fingerprint", record.fingerprint, 0));
        }
        Owner owner = owner();
        return new Identity(owner != null ? elementName(owner.type()) : null, owner != null ? owner.oid() : null,
                owner != null ? owner.name() : null, segments, false);
    }

    private static Identity child(Identity parent, String element) {
        List<Identity.Segment> segments = new ArrayList<>(parent.segments());
        segments.add(new Identity.Segment(element, null, null, 0));
        return new Identity(parent.ownerType(), parent.ownerOid(), parent.ownerName(), segments, parent.isLocated());
    }

    /** {@code RoleType} to {@code role}, as the object's element is named in a file. */
    private static String elementName(String type) {
        if (type == null || type.isEmpty()) {
            return type;
        }
        String name = type.endsWith("Type") ? type.substring(0, type.length() - 4) : type;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** Variable name to value, as midPoint gave them (a variable missing here was not given at all). */
    public Map<String, Object> variables() {
        return Collections.unmodifiableMap(record.variables);
    }

    /** The variable's value (may be null: present but empty); fails if the evaluation was not given the variable. */
    public Object variable(String name) {
        if (!record.variables.containsKey(name)) {
            throw new IllegalArgumentException("No variable " + name + " in " + record.variables.keySet());
        }
        return record.variables.get(name);
    }

    public boolean hasVariable(String name) {
        return record.variables.containsKey(name);
    }

    /** Source name ({@code givenName}, {@code input}...) to its old and new values. */
    public Map<String, SourceValues> sources() {
        return Collections.unmodifiableMap(record.sources);
    }

    /** The condition result for the old state; null when there was no condition to consider. */
    public Boolean conditionOld() {
        return record.conditionOld;
    }

    public Boolean conditionNew() {
        return record.conditionNew;
    }

    /** False when midPoint got no output triple (a mapping whose condition is false, an evaluation that failed). */
    public boolean hasOutput() {
        return record.hasOutput;
    }

    /** Output values that stay, are added, are removed (real values, as midPoint produced them). */
    public List<Object> zeroSet() {
        return Collections.unmodifiableList(record.zero);
    }

    public List<Object> plusSet() {
        return Collections.unmodifiableList(record.plus);
    }

    public List<Object> minusSet() {
        return Collections.unmodifiableList(record.minus);
    }

    /** Zero + plus: the values the output has after the change. Polystrings are given as their {@code orig}. */
    public List<Object> newValues() {
        List<Object> out = new ArrayList<>(record.zero);
        out.addAll(record.plus);
        return simple(out);
    }

    /** Zero + minus: the values the output had before the change. Polystrings are given as their {@code orig}. */
    public List<Object> oldValues() {
        List<Object> out = new ArrayList<>(record.zero);
        out.addAll(record.minus);
        return simple(out);
    }

    /**
     * The name of the background task the evaluation ran in (e.g. {@code Execution of ...} for an approved change, a
     * report or certification task); null when it ran on the driver's own thread.
     */
    public String task() {
        return record.taskName;
    }

    /**
     * The channel of the operation the evaluation ran in, without its namespace ({@code reconciliation},
     * {@code import}, {@code liveSync}, {@code user}...): its lens context's, else its task's; null when it has none.
     */
    public String channel() {
        String channel = record.channel;
        return channel != null && channel.contains("#") ? channel.substring(channel.indexOf('#') + 1) : channel;
    }

    /**
     * The text of the {@code <keyChild>} element of the nearest {@code <element>} that encloses this evaluation's
     * configuration element, e.g. {@code enclosing("stage", "number")} of an approver expression is its stage number,
     * {@code enclosing("column", "name")} of a report column expression its column name; null when there is none or
     * the evaluation is not located.
     */
    public String enclosing(String element, String keyChild) {
        return ConfigIndex.get().enclosingKey(location(), element, keyChild);
    }

    /**
     * How long midPoint's evaluation of the mapping took (the recorder's own work excluded; nested evaluations, such as
     * its condition and script, included); null for an expression, or when not measured. The first evaluation of a
     * script includes its compilation.
     */
    public java.time.Duration duration() {
        return record.nanos >= 0 ? java.time.Duration.ofNanos(record.nanos) : null;
    }

    /** What the evaluation threw, or null. */
    public Throwable error() {
        return record.error;
    }

    /** The evaluation this one ran in (its mapping, or the expression whose filter it is), or null. */
    public Evaluation parent() {
        return record.parent != null ? record.parent.view : null;
    }

    /** The evaluations that ran directly in this one, in order. */
    public List<Evaluation> nested() {
        List<Evaluation> out = new ArrayList<>();
        synchronized (record.children) {
            record.children.forEach(child -> out.add(child.view));
        }
        return out;
    }

    /** Every evaluation that ran in this one, at any depth, in order. */
    public Selection allNested() {
        List<Evaluation> out = new ArrayList<>();
        collect(this, out);
        return new Selection(out);
    }

    /** For a mapping: its expression's evaluation (null if the condition was false), else null. */
    public Evaluation expression() {
        return nestedPart("expression");
    }

    /** For a mapping: its condition's evaluation (null if it has none), else null. */
    public Evaluation condition() {
        return nestedPart("condition");
    }

    private Evaluation nestedPart(String part) {
        Evaluation found = null;
        for (Evaluation child : nested()) {
            if (part.equals(child.part())) {
                found = child;
            }
        }
        return found;
    }

    private static void collect(Evaluation evaluation, List<Evaluation> out) {
        for (Evaluation child : evaluation.nested()) {
            out.add(child);
            collect(child, out);
        }
    }

    static List<Object> simple(List<Object> values) {
        List<Object> out = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof PolyString) {
                out.add(((PolyString) value).getOrig());
            } else if (value instanceof PolyStringType) {
                out.add(((PolyStringType) value).getOrig());
            } else {
                out.add(value);
            }
        }
        return out;
    }

    /** For the script of a scripting model hook: which hook ran, in which state, for which focus; else null. */
    public HookRun hook() {
        return record.hook;
    }

    /** Normalized text (generated OIDs named, random values masked). */
    @Override
    public String toString() {
        List<Evaluation> all = new ArrayList<>();
        record.recording.records().forEach(r -> all.add(r.view));
        return Normalizer.of(all).describe(this);
    }
}
