package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * The GUI's form of an existing object ({@link MidPointConfigTester#editForm}, as a user: {@link Actor#editForm}): its
 * details page opened by its OID, as the GUI opens an object from a list. The fields show the stored values, and a
 * field's validator sees them in {@code object} (not the value being typed); saving executes the changes of the
 * fields typed into. What the page does is {@link ObjectForm}'s.
 *
 * <pre>{@code
 * t.editForm(OrgType::class.java, "cat-1").validate("costCenter", "CC2").fails("The cost center is set already.")
 * t.actor("site-admin").editForm(OrgType::class.java, "cat-1").set("displayName", "Category 1").submit().saves()
 * t.actor("site-admin").editForm(Orgs.SITE_1).set("extension/tags", listOf("a", "b")).submit().saves()
 * t.actor("site-admin").editForm(Orgs.SITE_1).add("extension/tags", "c").remove("extension/tags", "a").submit().saves()
 * t.actor(Users.HELPDESK).editForm(Orgs.SITE_1).tabs().hasTab("Members").hasNoTab("Governance")
 * t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).inducements().row(Roles.META_1).hasUnassign()
 * t.actor(Users.ROLE_ADMIN).editForm(Roles.TEAM_1).uninduce(Roles.META_1).submit().saves()
 * }</pre>
 */
public final class EditObjectForm extends ObjectForm {

    private final String oid;
    private final String name;
    private final List<GuiForms.Removal> removals;

    EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String nameOrOid, String user) {
        this(tester, type, find(tester, type, nameOrOid), user, Map.of());
    }

    private EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, ObjectType object,
            String user, Map<String, Object> items) {
        this(tester, type, object.getOid(), object.getName() != null ? object.getName().getOrig() : object.getOid(),
                user, items, List.of());
    }

    private EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String oid, String name,
            String user, Map<String, Object> items, List<GuiForms.Removal> removals) {
        super(tester, type, user, items);
        this.oid = oid;
        this.name = name;
        this.removals = List.copyOf(removals);
    }

    private static ObjectType find(MidPointConfigTester tester, Class<? extends ObjectType> type, String nameOrOid) {
        ObjectType object = tester.deployment().api().find(type, nameOrOid);
        if (object == null) {
            throw new IllegalArgumentException("No " + type.getSimpleName() + " " + nameOrOid);
        }
        return object;
    }

    /**
     * A value typed into the item's field first, as the user changes the form before the item validated or saving. A
     * collection is the values of a multi-valued item, exactly these: the rows of the values not in it removed, the
     * missing ones typed into an empty row or a row the item's add button adds, the others kept
     * ({@code set("extension/tags", listOf("a", "b"))}); an empty collection removes every value.
     * The value as the item's type has it, typed as the field shows it: a number or its text, a date
     * ({@code Instant}, {@code ZonedDateTime}, {@code XMLGregorianCalendar}, its ISO text; to the minute), a
     * duration, a drop-down's choice (a boolean, an enumeration, an object type's QName), a lookup table's key or
     * label; a reference or a password fails, naming what to use instead
     * (docs/guide/gui-and-authorizations.md).
     */
    public EditObjectForm set(String item, Object value) {
        return new EditObjectForm(tester, type, oid, name, user, with(items, item, value), removals);
    }

    /**
     * A value added to a multi-valued item, as the user does it: typed into an empty row, else into a row the item's
     * add button adds; a value the item has already is kept as it is. With {@link #remove} and other {@code add}s of
     * the item: its values removed first, then those added.
     */
    public EditObjectForm add(String item, Object value) {
        return new EditObjectForm(tester, type, oid, name, user,
                with(items, item, GuiForms.ValueRows.add(items.get(item), value)), removals);
    }

    /**
     * A value of a multi-valued item removed, as the user does it: its row's remove button pressed (the form fails
     * when the item has no such value).
     */
    public EditObjectForm remove(String item, Object value) {
        return new EditObjectForm(tester, type, oid, name, user,
                with(items, item, GuiForms.ValueRows.remove(items.get(item), value)), removals);
    }

    /**
     * The rows of the Assignments tab as the GUI shows them to the user ({@link AssignmentRows}): the page opened as
     * {@link #tabs} opens it (the items typed and the rows removed on this form not applied), the tab clicked on its
     * "All" sub-panel (4.4 and later: {@code allAssignments}, else the first sub-panel shown; 4.0: the tab, whose type
     * switch opens on All), its rows read once; the claims chain on it. A row is an assignment the user may read,
     * named by its target and relation; archetype assignments and delegations are not rows (the GUI's query). Fails
     * when the user may not open the page or the page shows them no Assignments tab.
     */
    public AssignmentRows assignments() {
        return rows(AssignmentRows.Kind.ASSIGNMENTS);
    }

    /** The rows of the Inducements tab (a role's, org's or service's), as {@link #assignments}. */
    public AssignmentRows inducements() {
        return rows(AssignmentRows.Kind.INDUCEMENTS);
    }

    private AssignmentRows rows(AssignmentRows.Kind kind) {
        boolean inducements = kind == AssignmentRows.Kind.INDUCEMENTS;
        GuiForms.Rows read = gui().rows(new GuiForms.Spec(type, oid, null, null, null, null, user, Map.of()),
                inducements);
        Actor actor = user != null ? tester.actor(user) : null;
        if (actor != null) {
            actor.explainAuthorizations();
        }
        String tab = inducements ? "Inducements" : "Assignments";
        if (!read.tabShown) {
            List<Tab> tabs = new ArrayList<>();
            read.tabs.forEach(info -> tabs.add(new Tab(info)));
            throw new AssertionError((user != null ? user + ": the page of " : "The page of ") + page() + " shows no "
                    + tab + " tab; " + new Tabs(user, page(), tabs).text());
        }
        ObjectType object = tester.deployment().api().find(type, oid);
        return new AssignmentRows(this, kind, actor, AssignmentRows.rows(tester, read),
                AssignmentRows.administrator(tester, object, kind), read.assignShown, read.assignEnabled);
    }

    /**
     * The Assignments tab's row of the target with relation default (stored as default or as none, which the GUI shows
     * alike) removed before saving, as the user presses its Unassign, then Save: recorded on the form, replayed by
     * {@link #submit}: the page opened, the items typed in, the tab opened, the row found and its Unassign pressed (the
     * row is marked deleted), then Save, whose delta deletes the whole assignment, which the model authorizes as an
     * unassignment ({@code #unassign} of the target, then the execution's {@code #modify}): a refusal is the page's
     * message ({@code submit().rejectsWith(...)}, {@code submit().rejectsWithMessageContaining(...)},
     * {@code submit().saved()}). {@code submit()} fails (AssertionError)
     * when the user does not see the tab, the row or its Unassign. The target as a generated name or a handle.
     */
    public EditObjectForm unassign(ObjectRef target) {
        return unassign(target, null);
    }

    /** The Assignments tab's row of the target with the relation (null: default) removed, as {@link #unassign}. */
    public EditObjectForm unassign(ObjectRef target, QName relation) {
        return removed(false, target, relation);
    }

    /** {@link #unassign(ObjectRef, QName)} of a role, org or service of the scenario. */
    public EditObjectForm unassign(AbstractRole<?> target, QName relation) {
        return removed(false, handle(target), relation);
    }

    /** The Inducements tab's row of the target with relation default removed, as {@link #unassign}. */
    public EditObjectForm uninduce(ObjectRef target) {
        return uninduce(target, null);
    }

    /** The Inducements tab's row of the target with the relation (null: default) removed, as {@link #unassign}. */
    public EditObjectForm uninduce(ObjectRef target, QName relation) {
        return removed(true, target, relation);
    }

    /** {@link #uninduce(ObjectRef, QName)} of a role, org or service of the scenario. */
    public EditObjectForm uninduce(AbstractRole<?> target, QName relation) {
        return removed(true, handle(target), relation);
    }

    private static ObjectRef handle(AbstractRole<?> target) {
        ObjectType object = target.tester.deployment().api().find(target.type, target.oid());
        return ObjectRef.of(object.getClass().getSimpleName(), object.getName().getOrig(), object.getOid());
    }

    EditObjectForm removed(boolean inducement, ObjectRef target, QName relation) {
        ObjectType object = tester.deployment().api().find(Actor.typeOf(target),
                target.oid() != null ? target.oid() : target.on(tester.line()));
        if (object == null) {
            throw new IllegalArgumentException("No " + target);
        }
        List<GuiForms.Removal> more = new ArrayList<>(removals);
        more.add(new GuiForms.Removal(inducement, object.getOid(), AssignmentRow.describe(target, null, null)
                .replace(" (default)", ""), relation));
        return new EditObjectForm(tester, type, oid, name, user, items, more);
    }

    @Override
    GuiForms.Spec spec() {
        return new GuiForms.Spec(type, oid, null, null, null, null, user, items, removals);
    }

    @Override
    String page() {
        return kind() + " " + name;
    }
}
