package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.configtester.names.ResourceRef;
import com.github.wadahiro.midpoint.tester.internal.ItemValues;

/**
 * A focus of the scenario by name or OID: a {@link User}, {@link Role}, {@link Org} or {@link Service}, read anew by
 * every call. {@link #asObject()} gives it as midPoint has it now, to check with any assertion library; a failed test then
 * shows the mappings of the test that set an item whose value appears in the failure. The assertions
 * ({@code hasAssignment}, {@code isEnabled}...) return the focus for chaining and fail with what is there and the mappings
 * behind it; the verbs ({@code assign}, {@code recompute}) return what midPoint did ({@link Effects}).
 *
 * @param <SELF> the focus's own class, which the assertions return
 */
public abstract class Focus<SELF extends Focus<SELF>> {

    final MidPointConfigTester tester;
    final Class<? extends FocusType> type;
    /** The word the messages name the focus with: {@code User}, {@code Role}, {@code Org}, {@code Service}. */
    final String kind;
    final String name;

    Focus(MidPointConfigTester tester, Class<? extends FocusType> type, String kind, String name) {
        this.tester = tester;
        this.type = type;
        this.kind = kind;
        this.name = name;
    }

    abstract SELF self();

    /** A new focus of this kind with this name, to add. */
    abstract NewFocus<?, ?> newFocus();

    /**
     * The focus's name as midPoint has it now (an object template may have computed or changed it); the name or OID
     * the focus was taken by when there is no such focus.
     */
    public String name() {
        FocusType focus = tester.deployment().api().find(type, name);
        return focus != null && focus.getName() != null ? focus.getName().getOrig() : name;
    }

    // ---- existence --------------------------------------------------------------------------------------------

    /**
     * Adds the focus through the model with only its name, as an administrator saving a new one in the GUI: the
     * deployment's object templates and policies fill in the rest. Returns what midPoint did ({@link Effects}).
     */
    public Effects add() {
        return add(newFocus());
    }

    Effects add(NewFocus<?, ?> spec) {
        FocusType object = spec.build();
        return tester.effectsOf(() -> tester.deployment().api().addObject(object));
    }

    /** Adds the focus midPoint names ({@link MidPointConfigTester#addOrg}...) and gives its OID. */
    static String addNameless(MidPointConfigTester tester, NewFocus<?, ?> spec) {
        return tester.deployment().api().addObject(spec.build()).oid();
    }

    /** The focus's OID as midPoint has it now: the one it was added with, or generated. */
    public String oid() {
        return existing().getOid();
    }

    public SELF exists() {
        existing();
        return self();
    }

    public SELF doesNotExist() {
        FocusType focus = tester.deployment().api().find(type, name);
        if (focus != null) {
            throw new AssertionError("Expected no " + kind.toLowerCase(java.util.Locale.ROOT) + " " + name
                    + ", but it exists (" + focus.getOid() + ")" + links(focus));
        }
        return self();
    }

    // ---- items ------------------------------------------------------------------------------------------------

    /**
     * The item ({@code costCenter}, {@code activation/validFrom}; names without namespace, so an extension item is
     * found by its local name, e.g. {@code extension/hrId}, not covered by a test yet)
     * has exactly these values, in any order; no values: the item is empty. Values compare as text: a polystring by
     * its original, an enumeration by its XML value ({@code "enabled"}), a date as an {@link java.time.Instant}.
     */
    public SELF hasItem(String path, Object... expected) {
        List<String> actual = itemValues(path);
        List<String> wanted = Values.texts(expected(path, expected));
        if (!Values.sameValues(wanted, actual)) {
            throw new AssertionError(kind + " " + name + ": " + path + " expected " + wanted + " but was " + actual
                    + Why.mappings(tester, path, name, path + " of " + name));
        }
        return self();
    }

    /**
     * The expected values as the item's type has them, so that they compare as midPoint's values do ({@code "5"} and
     * {@code 5}, a date with an offset and the same instant); as given when the path has no definition.
     */
    private Object[] expected(String path, Object[] expected) {
        com.evolveum.midpoint.prism.ItemDefinition<?> definition;
        try {
            definition = ItemValues.locate(tester.deployment().api().prismContext().getSchemaRegistry()
                    .findObjectDefinitionByCompileTimeClass(type), path, type.getSimpleName()).definition;
        } catch (IllegalArgumentException e) {
            return expected;
        }
        boolean structured = definition instanceof com.evolveum.midpoint.prism.PrismContainerDefinition
                && ((com.evolveum.midpoint.prism.PrismContainerDefinition<?>) definition).getCompileTimeClass() == null;
        if (!(definition instanceof com.evolveum.midpoint.prism.PrismPropertyDefinition) && !structured) {
            return expected;
        }
        List<Object> out = new ArrayList<>();
        for (Object value : expected) {
            for (Object one : value instanceof java.util.Collection ? (java.util.Collection<?>) value : List.of(value)) {
                out.add(Struct.values(tester.deployment().api().prismContext(), path, definition, one)[0]);
            }
        }
        return out.toArray();
    }

    /** The values of the item as text (see {@link #hasItem}). */
    public List<String> values(String path) {
        tester.readWhy(name + "/" + path, () -> why(path));
        return itemValues(path);
    }

    /**
     * The focus as midPoint has it now (read anew by every call: after a change, call it again), as the scripts of the
     * configuration see it: {@code assertThat(t.user("e1001").asObject().getCostCenter()).isEqualTo("Sales")}.
     */
    public FocusType asObject() {
        FocusType focus = existing();
        tester.readObject(this);
        return focus;
    }

    /** The item's values as the failure messages show them ({@link Describe}). */
    List<String> describedValues(String path) {
        List<String> out = new ArrayList<>();
        for (Object value : realValues(path)) {
            out.add(Describe.of(value));
        }
        return out;
    }

    private List<String> itemValues(String path) {
        return Values.texts(realValues(path));
    }

    /** The real values at the path, through every value of a multi-valued container on the way ({@code assignment}). */
    private java.util.Collection<?> realValues(String path) {
        PrismObject<? extends FocusType> focus = existing().asPrismObject();
        List<com.evolveum.midpoint.prism.PrismContainerValue<?>> containers = new ArrayList<>();
        containers.add(focus.getValue());
        String[] segments = path.split("/");
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            List<com.evolveum.midpoint.prism.PrismContainerValue<?>> next = new ArrayList<>();
            for (com.evolveum.midpoint.prism.PrismContainerValue<?> container : containers) {
                Item<?, ?> item = container.findItem(ItemPath.create(new QName(segments[i])));
                if (item == null) {
                    continue;
                }
                if (i == segments.length - 1) {
                    // a structured item of the extension schema has no Java class to give its values as
                    out.addAll(item instanceof com.evolveum.midpoint.prism.PrismContainer
                            && ((com.evolveum.midpoint.prism.PrismContainer<?>) item).getCompileTimeClass() == null
                            ? item.getValues() : item.getRealValues());
                } else {
                    for (Object value : item.getValues()) {
                        if (value instanceof com.evolveum.midpoint.prism.PrismContainerValue) {
                            next.add((com.evolveum.midpoint.prism.PrismContainerValue<?>) value);
                        }
                    }
                }
            }
            containers = next;
        }
        return out;
    }

    /** {@link #hasItem(String, Object...)} by the generated item ({@code UserExtension.SITE}). */
    public SELF hasItem(ItemRef item, Object... expected) {
        return hasItem(item(item), expected);
    }

    public List<String> values(ItemRef item) {
        return values(item(item));
    }

    public Why why(ItemRef item) {
        return why(item(item));
    }

    private String item(ItemRef item) {
        if (!type.getSimpleName().equals(item.ownerType())) {
            throw new IllegalArgumentException(item + " is not an item of " + type.getSimpleName());
        }
        return item.on(tester.line());
    }

    /** {@link #hasAssignment(String)} by the generated name ({@code Roles.DEPT_SALES}, an org, service or archetype). */
    public SELF hasAssignment(AssignableRef target) {
        return hasAssignment(target.on(tester.line()));
    }

    public SELF hasAssignment(AssignableRef target, QName relation) {
        return hasAssignment(target.on(tester.line()), relation);
    }

    public SELF hasNoAssignment(AssignableRef target) {
        return hasNoAssignment(target.on(tester.line()));
    }

    public SELF hasNoAssignment(AssignableRef target, QName relation) {
        return hasNoAssignment(target.on(tester.line()), relation);
    }

    public Effects assign(AssignableRef role) {
        return assign(role.on(tester.line()));
    }

    public Effects unassign(AssignableRef role) {
        return unassign(role.on(tester.line()));
    }

    public SELF hasAccountOn(ResourceRef resource) {
        return hasAccountOn(resource.on(tester.line()));
    }

    public SELF hasNoAccountOn(ResourceRef resource) {
        return hasNoAccountOn(resource.on(tester.line()));
    }

    // ---- assignments ------------------------------------------------------------------------------------------

    /**
     * The focus has an assignment of the target (a role, org, service or archetype, by name or OID) with the default
     * relation, as {@link #assign(String)} makes it: a manager's or an owner's assignment is not one
     * ({@link #hasAssignment(String, QName)}). The focus's own assignments, not what they induce.
     */
    public SELF hasAssignment(String target) {
        return hasAssignment(target, null);
    }

    /** The focus has an assignment of the target with this relation ({@code SchemaConstants.ORG_MANAGER}). */
    public SELF hasAssignment(String target, QName relation) {
        if (!assigned(target, relation)) {
            throw new AssertionError(kind + " " + name + ": expected an assignment of " + target + relationText(relation)
                    + ", assigned: " + assignments() + Why.mappings(tester, "assignment", name, "assignments of " + name));
        }
        return self();
    }

    public SELF hasNoAssignment(String target) {
        return hasNoAssignment(target, null);
    }

    public SELF hasNoAssignment(String target, QName relation) {
        if (assigned(target, relation)) {
            throw new AssertionError(kind + " " + name + ": expected no assignment of " + target + relationText(relation)
                    + ", assigned: " + assignments() + Why.mappings(tester, "assignment", name, "assignments of " + name));
        }
        return self();
    }

    private boolean assigned(String target, QName relation) {
        String wanted = relation == null ? "default" : relation.getLocalPart();
        for (AssignmentType assignment : existing().getAssignment()) {
            ObjectReferenceType ref = assignment.getTargetRef();
            if (ref != null && (target.equals(ref.getOid()) || target.equals(tester.nameOf(ref)))
                    && wanted.equals(relationOf(ref))) {
                return true;
            }
        }
        return false;
    }

    /** The assignments' targets, with their relation where it is not the default one ({@code IT (manager)}). */
    private List<String> assignments() {
        List<String> out = new ArrayList<>();
        for (AssignmentType assignment : existing().getAssignment()) {
            ObjectReferenceType target = assignment.getTargetRef();
            if (target != null) {
                String relation = relationOf(target);
                out.add(tester.nameOf(target) + ("default".equals(relation) ? "" : " (" + relation + ")"));
            }
        }
        return out;
    }

    private static String relationOf(ObjectReferenceType ref) {
        return ref.getRelation() == null ? "default" : ref.getRelation().getLocalPart();
    }

    private static String relationText(QName relation) {
        return relation == null ? "" : " with the relation " + relation.getLocalPart();
    }

    // ---- projections ------------------------------------------------------------------------------------------

    /** The projections the focus links on the resource, of any kind, to narrow and check ({@link Projections}). */
    public Projections<TargetObject> projections(String resource) {
        return new Projections<>(this, resource, null, null, null, null, Projection.ANY);
    }

    /** The accounts the focus links on the resource (live shadows of kind account), to narrow and check. */
    public Projections<Account> accounts(String resource) {
        return new Projections<>(this, resource, "account", null, null, null, Projection.ACCOUNT);
    }

    /** The entitlements the focus links on the resource (live shadows of kind entitlement: its groups there). */
    public Projections<Group> entitlements(String resource) {
        return new Projections<>(this, resource, "entitlement", null, null, null, Projection.GROUP);
    }

    /**
     * The focus's one account on the resource ({@code accounts(resource).single()}): fails if it links none, or
     * several (narrow them: {@code accounts(resource).intent("admin").single()}).
     */
    public Projection<Account> account(String resource) {
        return accounts(resource).single();
    }

    /** The account of this name on the resource, which the focus need not link (nor the target system have). */
    public Projection<Account> account(String resource, String name) {
        return new Projection<>(this, resource, name, Projection.ACCOUNT);
    }

    /** The focus's one entitlement on the resource ({@code entitlements(resource).single()}). */
    public Projection<Group> entitlement(String resource) {
        return entitlements(resource).single();
    }

    public Projection<Group> entitlement(String resource, String name) {
        return new Projection<>(this, resource, name, Projection.GROUP);
    }

    /**
     * The focus has an account on the resource: it links a live shadow of kind account there, and the account is on
     * the target system. The places alone: {@code accounts(resource)} (the repository) and
     * {@code account(resource).onTarget()}.
     */
    public SELF hasAccountOn(String resource) {
        return hasOn(accounts(resource), resource);
    }

    /** {@link #hasAccountOn(String)}, an account of this intent. */
    public SELF hasAccountOn(String resource, String intent) {
        return hasOn(accounts(resource).intent(intent), resource);
    }

    /**
     * The focus links no live account on the resource. An account left on the target system unlinked is not checked:
     * {@code t.target(resource).account(name).doesNotExist()}.
     */
    public SELF hasNoAccountOn(String resource) {
        accounts(resource).none();
        return self();
    }

    public SELF hasNoAccountOn(String resource, String intent) {
        accounts(resource).intent(intent).none();
        return self();
    }

    /** The focus has an entitlement on the resource (its group there): the link, its live shadow and the group. */
    public SELF hasEntitlementOn(String resource) {
        return hasOn(entitlements(resource), resource);
    }

    public SELF hasEntitlementOn(String resource, String intent) {
        return hasOn(entitlements(resource).intent(intent), resource);
    }

    public SELF hasNoEntitlementOn(String resource) {
        entitlements(resource).none();
        return self();
    }

    public SELF hasNoEntitlementOn(String resource, String intent) {
        entitlements(resource).intent(intent).none();
        return self();
    }

    public Projections<TargetObject> projections(ResourceRef resource) {
        return projections(resource.on(tester.line()));
    }

    public Projections<Account> accounts(ResourceRef resource) {
        return accounts(resource.on(tester.line()));
    }

    public Projections<Group> entitlements(ResourceRef resource) {
        return entitlements(resource.on(tester.line()));
    }

    public Projection<Account> account(ResourceRef resource) {
        return account(resource.on(tester.line()));
    }

    public Projection<Account> account(ResourceRef resource, String name) {
        return account(resource.on(tester.line()), name);
    }

    public Projection<Group> entitlement(ResourceRef resource, String name) {
        return entitlement(resource.on(tester.line()), name);
    }

    public Projection<Group> entitlement(ResourceRef resource) {
        return entitlement(resource.on(tester.line()));
    }

    public SELF hasAccountOn(ResourceRef resource, String intent) {
        return hasAccountOn(resource.on(tester.line()), intent);
    }

    public SELF hasNoAccountOn(ResourceRef resource, String intent) {
        return hasNoAccountOn(resource.on(tester.line()), intent);
    }

    public SELF hasEntitlementOn(ResourceRef resource) {
        return hasEntitlementOn(resource.on(tester.line()));
    }

    public SELF hasNoEntitlementOn(ResourceRef resource) {
        return hasNoEntitlementOn(resource.on(tester.line()));
    }

    public SELF hasEntitlementOn(ResourceRef resource, String intent) {
        return hasEntitlementOn(resource.on(tester.line()), intent);
    }

    public SELF hasNoEntitlementOn(ResourceRef resource, String intent) {
        return hasNoEntitlementOn(resource.on(tester.line()), intent);
    }

    private SELF hasOn(Projections<?> selection, String resource) {
        List<? extends Projection<?>> found = selection.list();
        if (found.isEmpty()) {
            throw new AssertionError(kind + " " + name + ": expected " + selection.what() + ", but links none; "
                    + selection.listing());
        }
        List<String> names = new ArrayList<>();
        for (Projection<?> projection : found) {
            if (projection.onTarget().read() != null) {
                return self();
            }
            names.add(projection.name());
        }
        throw new AssertionError(kind + " " + name + ": expected " + selection.what() + ", but " + resource
                + " does not have what it links (" + String.join(", ", names) + ")");
    }

    // ---- activation -------------------------------------------------------------------------------------------

    /**
     * The focus's effective status (as midPoint computed it from the administrative status and validity) is enabled.
     */
    public SELF isEnabled() {
        return hasEffectiveStatus("enabled");
    }

    public SELF isDisabled() {
        return hasEffectiveStatus("disabled");
    }

    /**
     * The focus's effective status is archived: midPoint 4.0's and 4.4's for a focus whose lifecycle state is not an
     * active one ({@code archived}; on 4.0 also {@code proposed} and {@code draft}), whatever the administrative
     * status. 4.8+ gives such a focus {@code disabled} ({@link #isDisabled()}).
     */
    public SELF isArchived() {
        return hasEffectiveStatus("archived");
    }

    private SELF hasEffectiveStatus(String expected) {
        FocusType focus = existing();
        String actual = focus.getActivation() != null ? Values.text(focus.getActivation().getEffectiveStatus()) : null;
        if (!expected.equals(actual)) {
            throw new AssertionError(kind + " " + name + ": expected effectiveStatus " + expected + " but was "
                    + actual + " (" + validity(focus) + ")");
        }
        return self();
    }

    private String validity(FocusType focus) {
        return "lifecycleState " + focus.getLifecycleState() + ", "
                + (focus.getActivation() == null ? "no activation"
                : "administrativeStatus " + Values.text(focus.getActivation().getAdministrativeStatus())
                        + ", validFrom " + Values.text(focus.getActivation().getValidFrom())
                        + ", validTo " + Values.text(focus.getActivation().getValidTo()))
                + "; midPoint's clock " + tester.clock().now();
    }

    // ---- changes ----------------------------------------------------------------------------------------------

    /**
     * Assigns the role (or org, service) of this name, as an administrator in the GUI would; returns what midPoint did
     * ({@link Effects}): claim what it derived (the accounts, their attributes, the mails), not the assignment the test
     * made.
     */
    public Effects assign(String role) {
        return tester.effectsOf(() -> tester.deployment().api().assign(type, name, role));
    }

    public Effects unassign(String role) {
        return tester.effectsOf(() -> tester.deployment().api().unassign(type, name, role));
    }

    /** Assigns the target with this relation ({@code SchemaConstants.ORG_MANAGER}, {@code ORG_OWNER}). */
    public Effects assign(String target, QName relation) {
        return tester.effectsOf(() -> tester.deployment().api().assign(type, name, target, relation));
    }

    public Effects unassign(String target, QName relation) {
        return tester.effectsOf(() -> tester.deployment().api().unassign(type, name, target, relation));
    }

    public Effects assign(AssignableRef target, QName relation) {
        return assign(target.on(tester.line()), relation);
    }

    /**
     * Assigns the target with a relation or a validity ({@link NewAssignment}), as an administrator in the GUI would:
     * {@code t.user("jdoe").assign("Contractor", a -> a.validFrom(start).validTo(end))}.
     */
    public Effects assign(String target, java.util.function.Consumer<NewAssignment> spec) {
        NewAssignment a = NewAssignment.of(tester, spec);
        return tester.effectsOf(() -> tester.deployment().api().change(type, name)
                .assign(target, a.relation, a.validFrom, a.validTo).execute());
    }

    public Effects assign(AssignableRef target, java.util.function.Consumer<NewAssignment> spec) {
        return assign(target.on(tester.line()), spec);
    }

    public Effects unassign(AssignableRef target, QName relation) {
        return unassign(target.on(tester.line()), relation);
    }

    /**
     * Modifies the focus through the model, its items and assignments in one delta ({@link Modification}), as an
     * administrator saves a form in the GUI; returns what midPoint did.
     */
    public Effects modify(java.util.function.Consumer<Modification> change) {
        Modification modification = new Modification(this);
        change.accept(modification);
        return tester.effectsOf(modification::execute);
    }

    /** Deletes the focus through the model: its projections go as the resources say (deleted, or disabled). */
    public Effects delete() {
        existing();
        return tester.effectsOf(() -> tester.deployment().api().deleteObject(type, name));
    }

    /**
     * Recomputes the focus without reconciling its projections ({@code ModelService.recompute} without options), as the
     * role/org member panel's Recompute of one member from its row (4.4+); a recomputation task and the bulk action
     * {@code recompute} reconcile, as {@code modify(Modification::reconcile)}. {@code t.user("jdoe").recompute().none()}:
     * it changed nothing.
     */
    public Effects recompute() {
        return tester.effectsOf(() -> tester.deployment().api().recompute(type, name));
    }

    // ---- why ------------------------------------------------------------------------------------------------

    /**
     * The mappings that set the item of this focus in this test ({@code fullName}, {@code assignment},
     * {@code activation/administrativeStatus}), oldest first; {@code why("fullName").print()} shows them.
     */
    public Why why(String path) {
        return Why.of(tester, path, name, path + " of " + name);
    }

    @Override
    public String toString() {
        return kind.toLowerCase(java.util.Locale.ROOT) + " " + name;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    FocusType existing() {
        FocusType focus = tester.deployment().api().find(type, name);
        if (focus == null) {
            throw new AssertionError("Expected a " + kind.toLowerCase(java.util.Locale.ROOT) + " " + name
                    + ", but there is none (a name an object template computes or changes is no handle: add the "
                    + "focus by t.add" + kind + "(...) and keep what it returns, or take it by its OID)");
        }
        return focus;
    }

    private static String links(FocusType focus) {
        int links = focus.getLinkRef().size();
        return links == 0 ? "; it links nothing" : "; it links " + links + " projection(s)";
    }
}
