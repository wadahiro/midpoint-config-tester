package com.github.wadahiro.midpoint.tester;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Evaluations narrowed down step by step, e.g. {@code run.in("HR").attribute("ri:mail").mapping("hr-mail")}. Every
 * filter returns a new selection; the order is the order the evaluations started in.
 *
 * Filters work on what midPoint and the configuration say (owner, {@link Identity}, mapping name, element), never on
 * line numbers. {@link #one()} (and {@link #mapping(String)}) end a selection: evaluations of the same place (a mapping
 * evaluated once per projector wave) count as one, different places are an error that lists them.
 */
public final class Selection implements Iterable<Evaluation> {

    private final List<Evaluation> evaluations;
    /** How the hints of an ambiguity error start: {@code run}, or {@code run.forFocus("jdoe")} in a view of a focus. */
    private final String receiver;

    public Selection(List<Evaluation> evaluations) {
        this(evaluations, "run");
    }

    Selection(List<Evaluation> evaluations, String receiver) {
        this.evaluations = List.copyOf(evaluations);
        this.receiver = receiver;
    }

    public Selection where(Predicate<Evaluation> filter) {
        return new Selection(evaluations.stream().filter(filter).collect(Collectors.toList()), receiver);
    }

    /** The evaluations of the mappings with this {@code <name>} (the mappings themselves, not what is nested). */
    public Selection mappings(String name) {
        return where(e -> e.isMapping() && name.equals(e.mappingName()));
    }

    /**
     * The last evaluation of the mapping with this {@code <name>}; fails if none was evaluated, or if two different
     * mappings with that name were (listing them; narrow down first, e.g. {@code run.in("LDAP").mapping("mail")}).
     */
    public Evaluation mapping(String name) {
        Selection selection = mappings(name);
        if (selection.isEmpty()) {
            throw new AssertionError("No mapping '" + name + "' selected; mappings: " + mappings().stream()
                    .map(Evaluation::mappingName).distinct().collect(Collectors.toList()));
        }
        return selection.one("Mapping '" + name + "'", ".mappings(\"" + name + "\")");
    }

    public Selection mappings() {
        return where(Evaluation::isMapping);
    }

    public Selection expressions() {
        return where(e -> !e.isMapping());
    }

    /** Held by the object with this name or OID (a role, resource, template...). */
    public Selection owner(String nameOrOid) {
        return where(e -> e.owner() != null && e.owner().is(nameOrOid));
    }

    /**
     * About the focus object with this name or OID ({@link Evaluation#focus()}), e.g. one user of a reconciliation
     * that evaluated the same mappings for several users.
     */
    public Selection focus(String nameOrOid) {
        return where(e -> {
            Evaluation.Owner focus = e.focus();
            return focus != null && focus.is(nameOrOid);
        });
    }

    /** Located in this file ({@code role-employee.xml} matches any directory). */
    public Selection file(String file) {
        return where(e -> e.location().isIn(file));
    }

    /**
     * With this identity, as {@link Identity#toString()} gives it, e.g.
     * {@code identity("objectTemplate:User template/mapping[fullName]")}.
     */
    public Selection identity(String identity) {
        return where(e -> e.identity().toString().equals(identity));
    }

    /** With this identity or inside it, e.g. {@code within("role:Employee/inducement[#2]")}. */
    public Selection within(String identity) {
        return where(e -> {
            String id = e.identity().toString();
            return id.equals(identity) || id.startsWith(identity + "/");
        });
    }

    /**
     * Inside the {@code <attribute>} or {@code <association>} with this {@code <ref>} (a resource's or a construction's);
     * {@code ri:mail} and {@code mail} match either form.
     */
    public Selection attribute(String ref) {
        return where(e -> e.identity().segments().stream()
                .anyMatch(s -> ("attribute".equals(s.element()) || "association".equals(s.element()))
                        && sameRef(ref, s.value())));
    }

    /** Inside the resource object type with this kind and intent. */
    public Selection objectType(String kind, String intent) {
        String value = kind + "/" + intent;
        return where(e -> e.identity().segments().stream()
                .anyMatch(s -> "objectType".equals(s.element()) && value.equals(s.value())));
    }

    /** Mappings in an {@code <inbound>} element. */
    public Selection inbounds() {
        return where(e -> e.isMapping() && e.identity().last() != null
                && "inbound".equals(e.identity().last().element()));
    }

    /** Mappings in an {@code <outbound>} element. */
    public Selection outbounds() {
        return where(e -> e.isMapping() && e.identity().last() != null
                && "outbound".equals(e.identity().last().element()));
    }

    static boolean sameRef(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        boolean aPrefixed = a.indexOf(':') >= 0;
        boolean bPrefixed = b.indexOf(':') >= 0;
        return aPrefixed != bPrefixed && local(a).equals(local(b));
    }

    private static String local(String ref) {
        return ref.substring(ref.indexOf(':') + 1);
    }

    /** Whose element path contains this fragment, e.g. {@code inducement/construction} or {@code correlation}. */
    public Selection path(String fragment) {
        return where(e -> e.location().path() != null && e.location().path().contains(fragment));
    }

    /**
     * Whose configuration element is inside an {@code <element>} whose {@code <keyChild>} is {@code value}: structural
     * identity in midPoint's terms, e.g. {@code within("stage", "number", "1")}, {@code within("policyRule", "name",
     * "approve-privileged-access")}, {@code within("column", "name", "fullName")}, {@code within("widget",
     * "identifier", "employees")}.
     */
    public Selection within(String element, String keyChild, String value) {
        return where(e -> value.equals(e.enclosing(element, keyChild)));
    }

    /** Whose configuration element is named so, e.g. {@code approverExpression} or {@code reviewerExpression}. */
    public Selection element(String name) {
        return where(e -> e.location().path() != null
                && (e.location().path().equals(name) || e.location().path().endsWith("/" + name)));
    }

    /** That ran in a background task (approved-change execution, report, certification task...). */
    public Selection inTasks() {
        return where(e -> e.task() != null);
    }

    /** With this local description ({@code condition}, {@code expression}). */
    public Selection part(String part) {
        return where(e -> part.equals(e.part()));
    }

    /** With this evaluator ({@code script}, {@code path}, {@code assignmentTargetSearch}...). */
    public Selection evaluator(String evaluator) {
        return where(e -> evaluator.equals(e.evaluator()));
    }

    /** That threw. */
    public Selection failed() {
        return where(e -> e.error() != null);
    }

    public List<Evaluation> list() {
        return evaluations;
    }

    public Stream<Evaluation> stream() {
        return evaluations.stream();
    }

    @Override
    public Iterator<Evaluation> iterator() {
        return evaluations.iterator();
    }

    public int size() {
        return evaluations.size();
    }

    public boolean isEmpty() {
        return evaluations.isEmpty();
    }

    /** The only evaluation; fails if there is none or more than one. */
    public Evaluation single() {
        if (evaluations.size() != 1) {
            throw new AssertionError("Expected one evaluation, found " + evaluations.size() + ":\n" + this);
        }
        return evaluations.get(0);
    }

    public Evaluation first() {
        return nonEmpty().get(0);
    }

    /** The selected evaluations grouped by {@link Evaluation#identity()}, in the order the places were first evaluated. */
    public Map<Identity, Selection> byIdentity() {
        Map<Identity, List<Evaluation>> groups = new LinkedHashMap<>();
        evaluations.forEach(e -> groups.computeIfAbsent(e.identity(), k -> new ArrayList<>()).add(e));
        Map<Identity, Selection> out = new LinkedHashMap<>();
        groups.forEach((identity, list) -> out.put(identity, new Selection(list, receiver)));
        return out;
    }

    /**
     * The last evaluation of the one place selected: several evaluations of the same mapping (projector waves,
     * assignment paths) are one place and give the last, the final state. Fails if nothing is selected or if
     * evaluations of different places are, listing their identities.
     */
    public Evaluation one() {
        return one("The selection", null);
    }

    Evaluation one(String what, String allOf) {
        Map<Identity, Selection> places = byIdentity();
        if (places.isEmpty()) {
            throw new AssertionError(what + ": no evaluation selected");
        }
        if (places.size() == 1) {
            return last();
        }
        StringBuilder sb = new StringBuilder(what).append(" is ambiguous: ").append(places.size())
                .append(" different places were evaluated:");
        List<Identity> identities = List.copyOf(places.keySet());
        for (Identity identity : identities) {
            Selection evaluations = places.get(identity);
            sb.append("\n  ").append(identity).append(" (").append(evaluations.size())
                    .append(evaluations.size() == 1 ? " evaluation" : " evaluations").append(")\n      select with ")
                    .append(hint(receiver, identity, evaluations.last().mappingName(), identities, places));
        }
        if (allOf != null) {
            sb.append("\nAll their evaluations: ").append(allOf);
        }
        return fail(sb.toString());
    }

    /** How to select this place alone: by owner, then by mapping name in the owner, else by identity. */
    private static String hint(String receiver, Identity identity, String name, List<Identity> identities,
            Map<Identity, Selection> places) {
        String owner = identity.ownerName() != null ? identity.ownerName() : identity.ownerOid();
        List<Identity> sameOwner = identities.stream()
                .filter(i -> Objects.equals(i.ownerOid(), identity.ownerOid())
                        && Objects.equals(i.ownerName(), identity.ownerName()))
                .collect(Collectors.toList());
        boolean nameUnique = name != null && sameOwner.stream()
                .filter(i -> name.equals(places.get(i).last().mappingName())).count() == 1;
        if (owner != null && name != null && nameUnique) {
            return receiver + ".in(\"" + owner + "\").mapping(\"" + name + "\")";
        }
        return receiver + ".select().identity(\"" + identity + "\").one()";
    }

    private static Evaluation fail(String message) {
        throw new AssertionError(message);
    }

    /** The last evaluation, e.g. the one of the final projector run; fails if there is none. */
    public Evaluation last() {
        List<Evaluation> list = nonEmpty();
        return list.get(list.size() - 1);
    }

    private List<Evaluation> nonEmpty() {
        if (evaluations.isEmpty()) {
            throw new AssertionError("No evaluation selected");
        }
        return evaluations;
    }

    @Override
    public String toString() {
        return Normalizer.of(evaluations).describe(evaluations);
    }
}
