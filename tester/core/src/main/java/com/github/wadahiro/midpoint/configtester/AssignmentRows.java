package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * The rows of an Assignments or Inducements tab as a user sees them ({@link EditObjectForm#assignments},
 * {@link EditObjectForm#inducements}), read from the details page the GUI renders for them, on the tab's "All"
 * sub-panel: each row's target and relation, whether its Unassign is shown, and the tab's "+". A row is an assignment
 * the user may read (the page shows the object as they read it); archetype assignments and delegations are not rows
 * (the GUI's query). Both the Unassign and the "+" are the security enforcer's decision with the object in the request
 * phase ({@code #adminUnassign}, {@code #adminAssign}), on every line. A failed claim lists the rows the user sees and
 * those the administrator sees; the user's authorizations follow. Immutable.
 */
public final class AssignmentRows {

    /** The tab: Assignments, or a role's, org's or service's Inducements. */
    public enum Kind { ASSIGNMENTS, INDUCEMENTS }

    static final String ADMIN_ASSIGN = "http://midpoint.evolveum.com/xml/ns/public/security/authorization-ui-3"
            + "#adminAssign";

    private final EditObjectForm form;
    private final Kind kind;
    private final Actor actor;
    private final List<AssignmentRow> all = new ArrayList<>();
    private final List<String> administrator;
    private final boolean assignShown;
    private final boolean assignEnabled;

    AssignmentRows(EditObjectForm form, Kind kind, Actor actor, List<Row> rows, List<String> administrator,
            boolean assignShown, boolean assignEnabled) {
        this.form = form;
        this.kind = kind;
        this.actor = actor;
        for (Row row : rows) {
            all.add(new AssignmentRow(this, row.target, row.relation, row.label, row.unassignShown,
                    row.unassignEnabled));
        }
        this.administrator = Collections.unmodifiableList(new ArrayList<>(administrator));
        this.assignShown = assignShown;
        this.assignEnabled = assignEnabled;
    }

    /** A row as read: its target (null: none), relation, text and Unassign. */
    static final class Row {
        final ObjectRef target;
        final QName relation;
        final String label;
        final boolean unassignShown;
        final boolean unassignEnabled;

        Row(ObjectRef target, QName relation, String label, boolean unassignShown, boolean unassignEnabled) {
            this.target = target;
            this.relation = relation;
            this.label = label;
            this.unassignShown = unassignShown;
            this.unassignEnabled = unassignEnabled;
        }
    }

    public Kind kind() {
        return kind;
    }

    /** Every row the user sees, in the GUI's order. */
    public List<AssignmentRow> all() {
        return Collections.unmodifiableList(all);
    }

    /** The row of the target with relation default (stored as default or as none, which the GUI shows alike). */
    public AssignmentRow row(ObjectRef target) {
        return row(target, null);
    }

    /** The row of the target with the relation (null: default); none fails (IllegalArgumentException) naming the rows. */
    public AssignmentRow row(ObjectRef target, QName relation) {
        AssignmentRow found = find(target, relation);
        if (found == null) {
            throw new IllegalArgumentException(subject() + ": the " + tab() + " tab of " + page()
                    + " shows no row of " + AssignmentRow.describe(target, relation, null) + "; " + text());
        }
        return found;
    }

    /** The user sees a row of the target with relation default. */
    public AssignmentRows hasRow(ObjectRef target) {
        return hasRow(target, null);
    }

    /** The user sees a row of the target with the relation (null: default). */
    public AssignmentRows hasRow(ObjectRef target, QName relation) {
        if (find(target, relation) == null) {
            throw new AssertionError(subject() + ": the " + tab() + " tab of " + page() + " shows no row of "
                    + AssignmentRow.describe(target, relation, null) + "; " + text() + "; the administrator sees: "
                    + (administrator.isEmpty() ? "none" : String.join(", ", administrator)));
        }
        return this;
    }

    /** The user sees no row of the target with relation default: not there, or not readable by them (alike to them). */
    public AssignmentRows hasNoRow(ObjectRef target) {
        return hasNoRow(target, null);
    }

    /** The user sees no row of the target with the relation (null: default). */
    public AssignmentRows hasNoRow(ObjectRef target, QName relation) {
        if (find(target, relation) != null) {
            throw new AssertionError(subject() + ": the " + tab() + " tab of " + page() + " shows a row of "
                    + AssignmentRow.describe(target, relation, null) + "; " + text());
        }
        return this;
    }

    /**
     * The tab's "+" (new assignment) as the user sees it: shown when the security enforcer allows {@code #adminAssign}
     * on the object in the request phase (not Wicket's roles, as the members panels' buttons).
     */
    public Button assignButton() {
        return new Button();
    }

    /** The "+" of the tab, read from the rendered page. */
    public final class Button {

        private Button() {
        }

        /** The GUI action the security enforcer decides for it ({@code ...authorization-ui-3#adminAssign}). */
        public String action() {
            return ADMIN_ASSIGN;
        }

        /** Whether the GUI shows the user the "+", enabled. */
        public boolean shown() {
            return assignShown && assignEnabled;
        }

        /** The GUI shows the user the "+". */
        public Button isShown() {
            if (!assignShown) {
                throw new AssertionError(subject() + ": the " + tab() + " tab of " + page() + " has no \"+\":"
                        + " #adminAssign is not allowed on " + page() + " (the security enforcer, request phase)");
            }
            if (!assignEnabled) {
                throw new AssertionError(subject() + ": the \"+\" of the " + tab() + " tab of " + page()
                        + " is disabled: roleManagement/assignmentApprovalRequestLimit reached");
            }
            return this;
        }

        /** The GUI does not show the user the "+". */
        public Button isHidden() {
            if (assignShown) {
                List<String> naming = new ArrayList<>();
                if (actor != null) {
                    for (com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType authorization
                            : actor.naming(ADMIN_ASSIGN)) {
                        naming.add(Actor.describe(authorization));
                    }
                }
                throw new AssertionError(subject() + ": the " + tab() + " tab of " + page() + " has a \"+\""
                        + (naming.isEmpty() ? "" : " (" + String.join("; ", naming) + ")"));
            }
            return this;
        }

        @Override
        public String toString() {
            return "the \"+\" of the " + tab() + " tab of " + page() + " as " + subjectName();
        }
    }

    private AssignmentRow find(ObjectRef target, QName relation) {
        for (AssignmentRow row : all) {
            if (row.target() != null && row.target().equals(target) && GuiRelations.same(relation, row.relation())) {
                return row;
            }
        }
        return null;
    }

    EditObjectForm form() {
        return form;
    }

    Actor actor() {
        return actor;
    }

    /** {@code Assignments}, {@code Inducements}. */
    String tab() {
        return kind == Kind.ASSIGNMENTS ? "Assignments" : "Inducements";
    }

    String page() {
        return form.page();
    }

    /** The user, or {@code The administrator}. */
    String subject() {
        return actor != null ? actor.name() : "The administrator";
    }

    private String subjectName() {
        return actor != null ? actor.name() : "the administrator";
    }

    /** {@code its rows: Role ar-meta-1 (default), Construction of hr (default)}. */
    private String text() {
        List<String> rows = new ArrayList<>();
        for (AssignmentRow row : all) {
            rows.add(row.toString() + (row.unassignShown() ? "" : " without Unassign"));
        }
        return "its rows: " + (rows.isEmpty() ? "none" : String.join(", ", rows));
    }

    @Override
    public String toString() {
        return "the " + tab() + " tab of " + page() + " as " + subjectName() + ": " + text();
    }

    /** The relations of rows: none stored is default, as the GUI shows it. */
    static final class GuiRelations {
        private GuiRelations() {
        }

        static boolean same(QName a, QName b) {
            QName one = a == null ? com.evolveum.midpoint.schema.constants.SchemaConstants.ORG_DEFAULT : a;
            QName two = b == null ? com.evolveum.midpoint.schema.constants.SchemaConstants.ORG_DEFAULT : b;
            return com.evolveum.midpoint.util.QNameUtil.match(one, two);
        }
    }

    /** The rows read: {@link GuiForms.Rows} as refs. */
    static List<Row> rows(MidPointConfigTester tester, GuiForms.Rows read) {
        List<Row> out = new ArrayList<>();
        for (GuiForms.RowInfo info : read.rows) {
            com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType assignment =
                    (com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType) info.assignment;
            ObjectRef target = info.targetOid == null ? null : ref(tester, info.targetOid, info.targetType);
            String label = target != null ? target.name() : AssignmentRow.label(assignment, resource(tester, assignment));
            out.add(new Row(target, info.relation, label, info.unassignShown, info.unassignEnabled));
        }
        return out;
    }

    /** The rows of the object as the administrator reads it, without archetypes and delegations (the GUI's query). */
    static List<String> administrator(MidPointConfigTester tester,
            com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType object, Kind kind) {
        List<com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType> values = new ArrayList<>();
        if (kind == Kind.INDUCEMENTS) {
            if (object instanceof com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType) {
                values.addAll(((com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType) object)
                        .getInducement());
            }
        } else if (object instanceof com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType) {
            values.addAll(((com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType) object)
                    .getAssignment());
        }
        java.util.Collection<QName> delegations = tester.midpoint()
                .bean(com.evolveum.midpoint.schema.RelationRegistry.class)
                .getAllRelationsFor(com.evolveum.midpoint.xml.ns._public.common.common_3.RelationKindType.DELEGATION);
        List<String> out = new ArrayList<>();
        for (com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType value : values) {
            com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType targetRef = value.getTargetRef();
            if (targetRef != null) {
                if (targetRef.getType() != null && "ArchetypeType".equals(targetRef.getType().getLocalPart())) {
                    continue;
                }
                if (targetRef.getRelation() != null && delegations.stream()
                        .anyMatch(d -> com.evolveum.midpoint.util.QNameUtil.match(d, targetRef.getRelation()))) {
                    continue;
                }
                ObjectRef target = ref(tester, targetRef.getOid(), targetRef.getType());
                out.add(AssignmentRow.describe(target, targetRef.getRelation(), null));
            } else {
                out.add(AssignmentRow.describe(null, null, AssignmentRow.label(value, resource(tester, value))));
            }
        }
        return out;
    }

    private static ObjectRef ref(MidPointConfigTester tester, String oid, QName type) {
        String typeName = type != null ? type.getLocalPart() : "ObjectType";
        com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType object;
        try {
            @SuppressWarnings("unchecked")
            Class<com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType> c =
                    (Class<com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType>) Class.forName(
                            "com.evolveum.midpoint.xml.ns._public.common.common_3." + typeName);
            object = tester.midpoint().find(c, oid);
        } catch (ClassNotFoundException | RuntimeException e) {
            object = null;
        }
        if (object == null) {
            return ObjectRef.of(typeName, oid, oid);
        }
        return ObjectRef.of(object.getClass().getSimpleName(), object.getName().getOrig(), object.getOid());
    }

    private static String resource(MidPointConfigTester tester,
            com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType assignment) {
        if (assignment.getConstruction() == null || assignment.getConstruction().getResourceRef() == null
                || assignment.getConstruction().getResourceRef().getOid() == null) {
            return null;
        }
        com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType resource = tester.midpoint().find(
                com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType.class,
                assignment.getConstruction().getResourceRef().getOid());
        return resource != null ? resource.getName().getOrig() : null;
    }
}
