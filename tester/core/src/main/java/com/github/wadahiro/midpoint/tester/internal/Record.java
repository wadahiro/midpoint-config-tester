package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.Location;

/**
 * What the line's recorder hook fills in for one evaluation; {@link Evaluation} is the read-only view tests get.
 * Values are the objects midPoint handed to the evaluation (not copies), except the output sets, which are copied
 * when the evaluation ends (midPoint keeps changing the triple afterwards).
 */
public final class Record {

    public final Recording recording;
    public final int sequence;
    public final Evaluation.Kind kind;
    public final Record parent;
    public final List<Record> children = new ArrayList<>();
    public final Evaluation view;

    /** The mapping's {@code <name>}; for an expression, null. */
    public String mappingName;
    /** midPoint's local description: {@code condition}, {@code expression}, ...; null when none. */
    public String part;
    /** midPoint's context description, as is. */
    public String description;
    /** The canonical form of the evaluator elements (expressions only; see {@code EvaluatorFingerprint}). */
    public String fingerprint;
    /** The mapping's target (output path or midPoint's mapping QName), as text; null when none. */
    public String target;

    /** The object that holds the configuration (role, resource, template...), when midPoint says it. */
    public String ownerType;
    public String ownerOid;
    public String ownerName;
    /**
     * Where in the owner the mapping is, as element names with 1-based positions among same-named siblings
     * ({@code inducement[1]/construction[1]/attribute[1]/outbound[1]}); null when not known.
     */
    public String elementPath;

    public final Map<String, Object> variables = new TreeMap<>();
    public final Map<String, Evaluation.SourceValues> sources = new TreeMap<>();
    public Boolean conditionOld;
    public Boolean conditionNew;
    /** False when the evaluation produced no triple (e.g. a mapping whose condition is false). */
    public boolean hasOutput;
    public List<Object> zero = List.of();
    public List<Object> plus = List.of();
    public List<Object> minus = List.of();
    public Throwable error;
    /** How long midPoint's evaluation of a mapping took, in nanoseconds (the recorder's own work excluded); -1: not measured. */
    public long nanos = -1;
    /** For the script of a scripting model hook, set by the line's script hook when the hook starts; else null. */
    public com.github.wadahiro.midpoint.tester.HookRun hook;
    /**
     * The configuration element the driver evaluates this in, as {object OID, element, key child, value} (a dashboard
     * widget: {@code {oid, "widget", "identifier", "employees"}}), when midPoint itself does not say it; else null.
     */
    public String[] scope;
    /** The background task the evaluation ran in (its name and OID); null on the driver's thread. */
    public String taskName;
    public String taskOid;
    /**
     * The channel URI of the operation the evaluation ran in (its lens context's, else its task's); null when it has
     * none.
     */
    public String channel;
    /** The focus of a root record ({@link FocusResolver}), set when the run ends; for nested records unused. */
    public Evaluation.Owner focus;
    public boolean focusResolved;

    private Location location;

    Record(Recording recording, int sequence, Evaluation.Kind kind, Record parent) {
        this.recording = recording;
        this.sequence = sequence;
        this.kind = kind;
        this.parent = parent;
        this.view = new Evaluation(this);
    }

    /** The configuration location, resolved on first use (not during midPoint's evaluation). */
    public synchronized Location location() {
        if (location == null) {
            location = recording.locator.locate(this);
        }
        return location;
    }
}
