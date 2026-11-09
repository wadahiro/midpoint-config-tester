package com.github.wadahiro.midpoint.tester;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.result.OperationResultStatus;
import com.github.wadahiro.midpoint.tester.internal.FileArea;

/**
 * What one driver call ({@link MidPoint#execute}, {@link MidPoint#importAccount}, {@link MidPoint#preview}...)
 * produced: every evaluation midPoint ran during it, in the order they started, and the operation's result.
 *
 * <pre>{@code
 * Run run = mp.importAccount("HR", "1001");
 * Evaluation m = run.mapping("hr-lastName");     // or run.in("HR").mapping(...), run.inbound("HR", "icfs:name")
 * assertThat(m.newValues()).containsExactly("Tanaka");
 * }</pre>
 *
 * The selectors that return one {@link Evaluation} give the last evaluation of one place of the configuration (its
 * {@link Evaluation#identity()}): midPoint evaluates a mapping once per projector wave, the last is the final state.
 * When evaluations of two or more different places match (two resources with an outbound mapping of the same name),
 * they fail and list the places with how to select each one.
 */
public final class Run {

    private final String operation;
    private final List<Evaluation> evaluations;
    private final OperationResult result;
    private final String oid;
    private final ModelContext<?> modelContext;
    private final Throwable failure;
    private final List<Notification> notifications;
    private final List<Mail> mails;
    private final String step;
    private final List<WrittenFile> filesWritten;
    private final List<String> filesDeleted;
    /** For a view of one focus ({@link #forFocus}): its name or OID as given; else null. */
    private final String focus;

    public Run(String operation, List<Evaluation> evaluations, OperationResult result, String oid,
            ModelContext<?> modelContext) {
        this(operation, evaluations, result, oid, modelContext, null, List.of(), null);
    }

    public Run(String operation, List<Evaluation> evaluations, OperationResult result, String oid,
            ModelContext<?> modelContext, Throwable failure, List<Notification> notifications) {
        this(operation, evaluations, result, oid, modelContext, failure, notifications, null);
    }

    public Run(String operation, List<Evaluation> evaluations, OperationResult result, String oid,
            ModelContext<?> modelContext, Throwable failure, List<Notification> notifications, String step) {
        this(operation, evaluations, result, oid, modelContext, failure, notifications, List.of(), step);
    }

    public Run(String operation, List<Evaluation> evaluations, OperationResult result, String oid,
            ModelContext<?> modelContext, Throwable failure, List<Notification> notifications, List<Mail> mails,
            String step) {
        this(operation, evaluations, result, oid, modelContext, failure, notifications, mails, step, List.of(),
                List.of());
    }

    public Run(String operation, List<Evaluation> evaluations, OperationResult result, String oid,
            ModelContext<?> modelContext, Throwable failure, List<Notification> notifications, List<Mail> mails,
            String step, List<WrittenFile> filesWritten, List<String> filesDeleted) {
        this.operation = operation;
        this.step = step;
        this.evaluations = List.copyOf(evaluations);
        this.result = result;
        this.oid = oid;
        this.modelContext = modelContext;
        this.failure = failure;
        this.notifications = List.copyOf(notifications);
        this.mails = List.copyOf(mails);
        this.filesWritten = List.copyOf(filesWritten);
        this.filesDeleted = List.copyOf(filesDeleted);
        this.focus = null;
    }

    private Run(Run run, String focus, List<Evaluation> evaluations) {
        this.operation = run.operation;
        this.step = run.step;
        this.evaluations = List.copyOf(evaluations);
        this.result = run.result;
        this.oid = run.oid;
        this.modelContext = run.modelContext;
        this.failure = run.failure;
        this.notifications = run.notifications;
        this.mails = run.mails;
        this.filesWritten = run.filesWritten;
        this.filesDeleted = run.filesDeleted;
        this.focus = focus;
    }

    /** What was run, e.g. {@code import account jdoe}. */
    public String operation() {
        return operation;
    }

    /** The test's name for this driver call ({@link MidPoint#step}), or null. */
    public String step() {
        return step;
    }

    /** Every evaluation, at any depth, in the order they started. */
    public List<Evaluation> evaluations() {
        return evaluations;
    }

    /** The evaluations not nested in another one (mappings, and expressions outside of mappings). */
    public List<Evaluation> topLevel() {
        return evaluations.stream().filter(e -> e.parent() == null).collect(Collectors.toList());
    }

    /** All evaluations, to narrow down. */
    public Selection select() {
        return new Selection(evaluations, receiver());
    }

    /**
     * This run seen for one focus object (by name or OID): the evaluations about it ({@link Evaluation#focus()}), with
     * every selector of a run, e.g. {@code run.forFocus("jdoe").mapping("hr-familyName")} when a reconciliation
     * evaluated that mapping for several users. Fails if no evaluation is about that focus, listing those that are.
     * The outcome, notifications, mails and files stay those of the whole run.
     */
    public Run forFocus(String nameOrOid) {
        Selection selection = select().focus(nameOrOid);
        if (selection.isEmpty()) {
            throw new AssertionError("No evaluation in " + where() + " is about the focus " + nameOrOid
                    + "; focus objects: " + focuses().stream().map(Run::describe).collect(Collectors.toList()));
        }
        return new Run(this, nameOrOid, selection.list());
    }

    /** The focus objects the evaluations are about ({@link Evaluation#focus()}), in the order first seen. */
    public List<Evaluation.Owner> focuses() {
        Map<String, Evaluation.Owner> out = new LinkedHashMap<>();
        for (Evaluation e : evaluations) {
            Evaluation.Owner f = e.focus();
            if (f != null) {
                out.putIfAbsent(f.type() + "/" + f.oid() + "/" + f.name(), f);
            }
        }
        return List.copyOf(out.values());
    }

    /** For a view of one focus ({@link #forFocus}): the name or OID it was selected by; else null. */
    public String focus() {
        return focus;
    }

    private static String describe(Evaluation.Owner focus) {
        return focus.name() != null ? focus.name() : focus.oid();
    }

    /** How a hint selecting in this run starts: {@code run}, or {@code run.forFocus("jdoe")} in a view of a focus. */
    private String receiver() {
        return focus != null ? "run.forFocus(\"" + focus + "\")" : "run";
    }

    /** The operation, and the focus of a view of one, for messages. */
    private String where() {
        return focus != null ? operation + " for the focus " + focus : operation;
    }

    /**
     * The last evaluation of the mapping with this {@code <name>}; fails if it did not run, or if two or more
     * different mappings with this name ran (e.g. on two resources: narrow down with {@code in("LDAP").mapping(name)}).
     * {@link #mappings(String)} gives all evaluations of all of them.
     */
    public Evaluation mapping(String name) {
        Selection selection = mappings(name);
        if (selection.isEmpty()) {
            throw new AssertionError("Mapping '" + name + "' was not evaluated in " + where() + "; mappings: "
                    + select().mappings().stream().map(Evaluation::mappingName).distinct().collect(Collectors.toList()));
        }
        return selection.one("Mapping '" + name + "' in " + where(), receiver() + ".mappings(\"" + name + "\")");
    }

    public Selection mappings(String name) {
        return select().mappings(name);
    }

    /** The evaluations held by the object with this name or OID (e.g. a role, its inducements' mappings). */
    public Selection in(String ownerNameOrOid) {
        return select().owner(ownerNameOrOid);
    }

    /** The evaluations located in this file. */
    public Selection at(String file) {
        return select().file(file);
    }

    /** The inbound mapping of this attribute (or association) of the resource, by name or OID; see {@link #mapping}. */
    public Evaluation inbound(String resource, String attribute) {
        return one("Inbound of " + attribute + " of " + resource, in(resource).inbounds().attribute(attribute),
                in(resource).inbounds());
    }

    /** The inbound mapping of this attribute of the resource object type with this kind and intent. */
    public Evaluation inbound(String resource, String kind, String intent, String attribute) {
        return one("Inbound of " + attribute + " of " + resource + " " + kind + "/" + intent,
                in(resource).inbounds().objectType(kind, intent).attribute(attribute), in(resource).inbounds());
    }

    /**
     * The outbound mapping of this attribute (or association) in the resource's schema handling, by resource name or
     * OID (a role's construction: {@link #construction}).
     */
    public Evaluation outbound(String resource, String attribute) {
        return one("Outbound of " + attribute + " of " + resource, in(resource).outbounds().attribute(attribute),
                in(resource).outbounds());
    }

    /** The outbound mapping of this attribute of the resource object type with this kind and intent. */
    public Evaluation outbound(String resource, String kind, String intent, String attribute) {
        return one("Outbound of " + attribute + " of " + resource + " " + kind + "/" + intent,
                in(resource).outbounds().objectType(kind, intent).attribute(attribute), in(resource).outbounds());
    }

    /** The mapping of the object template with this {@code <name>}, or (unnamed) with this target path. */
    public Evaluation templateMapping(String template, String nameOrTarget) {
        Selection mappings = in(template).mappings().where(e -> "mapping".equals(last(e)));
        return one("Mapping " + nameOrTarget + " of template " + template,
                mappings.where(e -> nameOrTarget.equals(e.mappingName()) || isTarget(e, nameOrTarget)), mappings);
    }

    /** The outbound mapping of this attribute (or association) in a construction of the role (inducement or assignment). */
    public Evaluation construction(String role, String attribute) {
        Selection constructions = in(role).outbounds().where(e -> e.identity().segment("construction") != null);
        return one("Construction outbound of " + attribute + " of " + role, constructions.attribute(attribute),
                constructions);
    }

    private Evaluation one(String what, Selection selection, Selection candidates) {
        if (selection.isEmpty()) {
            throw new AssertionError(what + " was not evaluated in " + where() + "; evaluated: "
                    + candidates.byIdentity().keySet());
        }
        return selection.one(what + " in " + where(), null);
    }

    private static String last(Evaluation e) {
        Identity.Segment last = e.identity().last();
        return last != null ? last.element() : null;
    }

    private static boolean isTarget(Evaluation e, String path) {
        Identity.Segment last = e.identity().last();
        if (last != null && "target".equals(last.key()) && path.equals(last.value())) {
            return true;
        }
        String target = e.target();
        return target != null && (target.equals(path) || target.endsWith(":" + path) || target.endsWith("/" + path));
    }

    /** The OID of the object the operation added, or null. */
    public String oid() {
        return oid;
    }

    public OperationResult result() {
        return result;
    }

    /** For {@link MidPoint#preview}: the model context midPoint computed; otherwise null. */
    public ModelContext<?> modelContext() {
        return modelContext;
    }

    // ---- outcome, model hooks, notifications ------------------------------------------------------------------

    /** Whether midPoint neither threw nor reported an error (always true for a run a throwing driver returned). */
    public boolean isSuccess() {
        return failure == null && (result == null || !result.isError());
    }

    /** The operation's status as midPoint computed it (e.g. {@code FATAL_ERROR} for a denied or failed operation). */
    public OperationResultStatus status() {
        return result != null ? result.getStatus() : null;
    }

    /** What midPoint threw (e.g. a {@code PolicyViolationException}), or null. */
    public Throwable failure() {
        return failure;
    }

    /** The runs of the scripting model hooks of the system configuration, in the order they ran. */
    public List<HookRun> hooks() {
        return evaluations.stream().map(Evaluation::hook).filter(h -> h != null).collect(Collectors.toList());
    }

    /** The runs of the hook with this {@code <name>}. */
    public List<HookRun> hooks(String name) {
        return hooks().stream().filter(h -> name.equals(h.name())).collect(Collectors.toList());
    }

    /** The messages notifications sent to {@code dummy} transports during this run. */
    public List<Notification> notifications() {
        return notifications;
    }

    /**
     * The mails midPoint's mail transports delivered to the in-memory SMTP server during this run, in the order
     * received (see {@link Mail}).
     */
    public List<Mail> mails() {
        return mails;
    }

    // ---- files -------------------------------------------------------------------------------------------------

    /**
     * The files created or changed during this run in the mapped directories ({@link MidPoint#files()}) and in
     * midPoint's report output directory, in path order; by whatever wrote them (scripts, report exports).
     */
    public List<WrittenFile> filesWritten() {
        return filesWritten;
    }

    /** The file written at this path (as the configuration names it); fails if this run wrote none there. */
    public WrittenFile fileWritten(String path) {
        // as text: a Path of a mapped path would be redirected
        String wanted = FileArea.normalize(path);
        return filesWritten.stream().filter(f -> f.path().equals(wanted)).findFirst()
                .orElseThrow(() -> new AssertionError("No file " + path + " was written in " + operation
                        + "; written: " + filesWritten.stream().map(WrittenFile::path).collect(Collectors.toList())));
    }

    /** The files deleted during this run, by the paths the configuration uses. */
    public List<String> filesDeleted() {
        return filesDeleted;
    }

    /** The evaluations as normalized text (see {@link Normalizer}). */
    @Override
    public String toString() {
        return where() + ": " + evaluations.size() + " evaluations\n" + Normalizer.of(evaluations).describe(evaluations);
    }
}
