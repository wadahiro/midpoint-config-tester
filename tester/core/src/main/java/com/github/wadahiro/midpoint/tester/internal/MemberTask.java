package com.github.wadahiro.midpoint.tester.internal;

import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;

/**
 * The task a members panel's Assign or Unassign starts ({@link LineDrivers#submitMemberTask}): the bulk action
 * {@code assign} or {@code unassign} of the panel's object with the relations, over the members the type and query
 * select. The line's GUI builds and submits it, as the logged-in user who owns it.
 */
public final class MemberTask {

    /** Whether the task assigns (the Assign popup's Add) or unassigns (an Unassign over a selection or all). */
    public final boolean assign;
    /** The panel's object: the role, org or service the members are assigned to. */
    public final PrismObject<? extends AbstractRoleType> target;
    /** The type of the objects the task searches ({@code UserType}...). */
    public final QName memberType;
    /** The search of the members ({@code inOid} of a selection, or the direct members). */
    public final ObjectQuery memberQuery;
    /** Assign: the one relation; unassign: the relations whose assignments go. */
    public final List<QName> relations;
    /**
     * The GUI's scope of an unassignment, which names the task: {@code SELECTED}, {@code ALL_DIRECT}; null for an
     * assignment.
     */
    public final String scope;

    public MemberTask(boolean assign, PrismObject<? extends AbstractRoleType> target, QName memberType,
            ObjectQuery memberQuery, List<QName> relations, String scope) {
        this.assign = assign;
        this.target = target;
        this.memberType = memberType;
        this.memberQuery = memberQuery;
        this.relations = List.copyOf(relations);
        this.scope = scope;
    }

    /**
     * The task's name as the GUI of 4.4 and later gives it ({@code operation.add.members.to.org} and the like of
     * midPoint's localization, the same text on 4.4 to 4.11): {@code Add members to Organization Site 1}, with the
     * object's display name or name.
     */
    public String taskName() {
        AbstractRoleType role = target.asObjectable();
        String shown = role.getDisplayName() != null && role.getDisplayName().getOrig() != null
                && !role.getDisplayName().getOrig().isEmpty() ? role.getDisplayName().getOrig()
                        : role.getName().getOrig();
        String simple = role.getClass().getSimpleName().replaceAll("Type$", "");
        String kind = simple.equals("Org") ? "Organization" : simple;
        if (assign) {
            return "Add members to " + kind + " " + shown;
        }
        String which = scope == null ? "" : scope.equals("SELECTED") ? "selected " : scope.equals("ALL") ? "all "
                : "all direct ";
        return "Unassign " + which + "members from " + kind + " " + shown;
    }

    /** The task's name as the GUI of 4.0 gives it: {@code Add.members}, {@code Remove.SELECTED.members}. */
    public String taskName40() {
        return assign ? "Add.members" : "Remove." + (scope != null ? scope + "." : "") + "members";
    }
}
