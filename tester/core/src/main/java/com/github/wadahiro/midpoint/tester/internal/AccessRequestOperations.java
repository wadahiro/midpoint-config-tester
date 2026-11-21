package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemObjectsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.OperationFailed;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * The model side of the request-access page's Submit ({@code configtester.Request}): the page's execute options, and
 * the task its Submit starts for several targets, as the user.
 */
public final class AccessRequestOperations {

    private AccessRequestOperations() {
    }

    /**
     * The system configuration's {@code roleManagement/defaultExecuteAfterAllApprovals} as the page reads it
     * ({@code isDefaultExecuteAfterAllApprovals}); null when the configuration has no {@code roleManagement} or the
     * element is not set.
     */
    static Boolean executeAfterAllApprovals(MidPointImpl mp) {
        SystemConfigurationType config = mp.find(SystemConfigurationType.class,
                SystemObjectsType.SYSTEM_CONFIGURATION.value());
        return config != null && config.getRoleManagement() != null
                ? config.getRoleManagement().isDefaultExecuteAfterAllApprovals() : null;
    }

    /**
     * The options the page passes with a Submit for one target ({@link LineDrivers#accessRequestOptions}, with the
     * initial partial processing).
     */
    public static ModelExecuteOptions options(MidPoint api, String comment) {
        MidPointImpl mp = (MidPointImpl) api;
        return mp.drivers().accessRequestOptions(comment, executeAfterAllApprovals(mp), true);
    }

    /**
     * Starts the task of a Submit for several targets as the user, the way the line's GUI does
     * ({@link LineDrivers#submitAccessRequestTask}), and waits for it: a driver call that fails ({@code
     * OperationFailed}) when midPoint refuses to start the task, or the task fails on a target (its errors and its
     * last failure). The task is the GUI's, not an effect of the call ({@link TesterTasks#isGuiTask}).
     */
    public static Run submit(MidPoint api, String user, String label, AccessRequest spec) {
        MidPointImpl mp = (MidPointImpl) api;
        // the targets' names, as the 4.8+ page names each target's request (the person of interest's targetName)
        java.util.List<String> names = new java.util.ArrayList<>();
        for (com.evolveum.midpoint.prism.delta.ObjectDelta<?> delta : spec.deltas) {
            names.add(mp.nameOf(com.evolveum.midpoint.xml.ns._public.common.common_3.UserType.class, delta.getOid()));
        }
        AccessRequest request = spec.resolved(names, executeAfterAllApprovals(mp));
        Run run = mp.runAs(user, label, (task, result) -> {
            String oid = mp.drivers().submitAccessRequestTask(request, task, result);
            TesterTasks.guiTask(oid);
            return new MidPointImpl.Outcome(oid, null);
        }, false);
        if (run.failure() != null) {
            // midPoint refused to start the task
            throw new OperationFailed(label + " failed: " + run.failure().getMessage(), run, run.failure());
        }
        // the task's run, by its status and statistics (its result as stored may be incomplete)
        String errors;
        try {
            errors = mp.taskRunErrors(run.oid());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the task of " + label, e);
        }
        if (errors != null) {
            String name = mp.find(TaskType.class, run.oid()).getName().getOrig();
            throw new OperationFailed(label + " failed: the task \"" + name + "\" (owned by " + user + ") failed: "
                    + errors.trim(), run, null);
        }
        return run;
    }
}
