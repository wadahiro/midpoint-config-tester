package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;

/**
 * A row of the Assignments or Inducements tab as the user sees it ({@link AssignmentRows}): the assignment as they read
 * the object, named by its target and relation, and whether the row's menu shows them its Unassign. Immutable.
 */
public final class AssignmentRow {

    static final String ADMIN_UNASSIGN = "http://midpoint.evolveum.com/xml/ns/public/security/authorization-ui-3"
            + "#adminUnassign";

    private final AssignmentRows rows;
    private final ObjectRef target;
    private final QName relation;
    private final String label;
    private final boolean unassignShown;
    private final boolean unassignEnabled;

    AssignmentRow(AssignmentRows rows, ObjectRef target, QName relation, String label, boolean unassignShown,
            boolean unassignEnabled) {
        this.rows = rows;
        this.target = target;
        this.relation = relation;
        this.label = label;
        this.unassignShown = unassignShown;
        this.unassignEnabled = unassignEnabled;
    }

    /** The target (type, name, OID), or null: a row without a target (a construction, a policy rule...). */
    public ObjectRef target() {
        return target;
    }

    /** The relation as stored (null: none set, which the GUI shows as default). */
    public QName relation() {
        return relation;
    }

    /**
     * The row's text: the target's name, or for a row without a target what it holds ({@code Construction of hr},
     * {@code Policy rule ...}).
     */
    public String label() {
        return label;
    }

    /**
     * Whether the row's menu shows the Unassign, enabled: the GUI asks the security enforcer for {@code #adminUnassign}
     * on the object, in the request phase (and disables it when the changed rows reach the
     * {@code roleManagement/assignmentApprovalRequestLimit}).
     */
    public boolean unassignShown() {
        return unassignShown && unassignEnabled;
    }

    /** The row shows the user its Unassign. */
    public AssignmentRow hasUnassign() {
        if (!unassignShown) {
            throw new AssertionError(rows.subject() + ": the row of " + this + " on the " + rows.tab() + " tab of "
                    + rows.page() + " has no Unassign: #adminUnassign is not allowed on " + rows.page()
                    + " (the security enforcer, request phase)");
        }
        if (!unassignEnabled) {
            throw new AssertionError(rows.subject() + ": the row's Unassign of " + this + " on the " + rows.tab()
                    + " tab of " + rows.page() + " is disabled: roleManagement/assignmentApprovalRequestLimit"
                    + " reached");
        }
        return this;
    }

    /** The row does not show the user its Unassign. */
    public AssignmentRow hasNoUnassign() {
        if (unassignShown) {
            List<String> naming = new ArrayList<>();
            if (rows.actor() != null) {
                for (AuthorizationType authorization : rows.actor().naming(ADMIN_UNASSIGN)) {
                    naming.add(Actor.describe(authorization));
                }
            }
            throw new AssertionError(rows.subject() + ": the row of " + this + " on the " + rows.tab() + " tab of "
                    + rows.page() + " has an Unassign" + (naming.isEmpty() ? "" : " (" + String.join("; ", naming)
                    + ")"));
        }
        return this;
    }

    /**
     * The row's Unassign: the form with this row's removal recorded ({@link EditObjectForm#unassign},
     * {@link EditObjectForm#uninduce}), to be saved by {@code submit()}. A row without a target cannot be named so.
     */
    public EditObjectForm unassign() {
        if (target == null) {
            throw new IllegalArgumentException("The row " + label + " has no target to unassign it by");
        }
        return rows.form().removed(rows.kind() == AssignmentRows.Kind.INDUCEMENTS, target, relation);
    }

    /** {@code Role ar-meta-1 (default)}. */
    @Override
    public String toString() {
        return describe(target, relation, label);
    }

    static String describe(ObjectRef target, QName relation, String label) {
        String kind = target == null ? null : target.type().endsWith("Type")
                ? target.type().substring(0, target.type().length() - 4) : target.type();
        return (target == null ? label : kind + " " + target.name()) + " ("
                + (relation == null ? "default" : relation.getLocalPart()) + ")";
    }

    /** The text of a row without a target, as the GUI names what it holds. */
    static String label(AssignmentType assignment, String resourceName) {
        if (assignment.getConstruction() != null) {
            return "Construction" + (resourceName != null ? " of " + resourceName : "");
        }
        if (assignment.getPolicyRule() != null) {
            return "Policy rule" + (assignment.getPolicyRule().getName() != null
                    ? " " + assignment.getPolicyRule().getName() : "");
        }
        if (assignment.getFocusMappings() != null) {
            return "Focus mappings";
        }
        return "(no name)";
    }
}
