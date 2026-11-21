package com.github.wadahiro.midpoint.tester.internal;

import java.util.List;

import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * The request-access page's Submit for several targets ({@link LineDrivers#submitAccessRequestTask}): the cart's
 * additions for each target user, as the task the line's GUI starts applies them. The deltas are one per target, in
 * the targets' order, each a modification of that user adding the cart's assignments (the same additions for all).
 */
public final class AccessRequest {

    /** The target users, by name, in order. */
    public final List<String> targets;
    /** One modification per target (the same order), adding the cart's assignments. */
    public final List<ObjectDelta<? extends ObjectType>> deltas;
    /** The page's comment (the request's business context); null for none. */
    public final String comment;
    /** The system configuration's {@code roleManagement/defaultExecuteAfterAllApprovals}; null when not set. */
    public final Boolean executeAfterAllApprovals;

    AccessRequest(List<String> targets, List<ObjectDelta<? extends ObjectType>> deltas, String comment,
            Boolean executeAfterAllApprovals) {
        this.targets = List.copyOf(targets);
        this.deltas = List.copyOf(deltas);
        this.comment = comment;
        this.executeAfterAllApprovals = executeAfterAllApprovals;
    }

    /** The request as the page builds it; the system configuration is read when it is submitted. */
    public AccessRequest(List<String> targets, List<ObjectDelta<? extends ObjectType>> deltas, String comment) {
        this(targets, deltas, comment, null);
    }

    /** The request with the targets' names as midPoint has them and the system configuration's option. */
    AccessRequest resolved(List<String> names, Boolean executeAfterAllApprovals) {
        return new AccessRequest(names, deltas, comment, executeAfterAllApprovals);
    }

    /**
     * The task's name as the GUI of 4.0 and 4.4 gives it: {@code createStringResource(OPERATION_REQUEST_ASSIGNMENTS)}
     * of a key no localization has, so the key itself ({@code PageBase.createStringResource} sets the key as the
     * default value).
     */
    public static final String TASK_NAME_40 = "com.evolveum.midpoint.web.page.self.PageAssignmentsList.requestAssignments";

    /** The task's name as the GUI of 4.8 and later gives it ({@code RequestAccess.changeExecutionRequestTaskName}). */
    public String taskName48() {
        return "Requesting access for " + targets.size() + " users";
    }

    /** A target's request in the 4.8+ task ({@code RequestAccess.changeExecutionRequestName}). */
    public static String requestName48(String target) {
        return "Requesting access for " + target;
    }

    @Override
    public String toString() {
        return "access request for " + targets;
    }
}
