package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AreaCategoryType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RelationDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RelationKindType;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.OperationFailed;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * The model side of a members panel's Assign and Unassign ({@code configtester.MembersPanel}): the relations a panel
 * offers, and the task its Assign or Unassign starts, as the user.
 */
public final class MemberOperations {

    private MemberOperations() {
    }

    /**
     * The relations of the category in the GUI's order ({@code WebComponentUtil.getCategoryRelationChoices}, 4.0;
     * {@code RelationUtil.getCategoryRelationChoices}, 4.4+): the deployment's relation definitions (midPoint's and
     * the system configuration's), sorted by the kind they are the default for, else their highest kind, then by
     * label (here the label as written, not translated).
     */
    public static List<QName> relations(MidPoint api, AreaCategoryType category) {
        List<RelationDefinitionType> definitions = new ArrayList<>(
                ((MidPointImpl) api).harness.bean(ModelInteractionService.class).getRelationDefinitions());
        definitions.sort((d1, d2) -> {
            int compare = Integer.compare(order(d1), order(d2));
            if (compare != 0 || d1.getDisplay() == null || d1.getDisplay().getLabel() == null
                    || d2.getDisplay() == null || d2.getDisplay().getLabel() == null) {
                return compare;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(d1.getDisplay().getLabel().getOrig(),
                    d2.getDisplay().getLabel().getOrig());
        });
        List<QName> out = new ArrayList<>();
        for (RelationDefinitionType definition : definitions) {
            if (definition.getCategory() != null && definition.getCategory().contains(category)) {
                out.add(definition.getRef());
            }
        }
        return out;
    }

    private static int order(RelationDefinitionType definition) {
        RelationKindType kind = definition.getDefaultFor();
        if (kind == null) {
            for (RelationKindType k : definition.getKind()) {
                if (kind == null || kind.ordinal() < k.ordinal()) {
                    kind = k;
                }
            }
        }
        return kind != null ? kind.ordinal() : 100;
    }

    /**
     * Starts the task as the user, the way the line's GUI does ({@link LineDrivers#submitMemberTask}), and waits for
     * it: a driver call that fails ({@code OperationFailed}) when midPoint refuses to start the task, or the task
     * fails on a member (its errors and its last failure). The task is the GUI's, not an effect of the call
     * ({@link TesterTasks#isGuiTask}).
     */
    public static Run submit(MidPoint api, String user, String label, MemberTask spec) {
        MidPointImpl mp = (MidPointImpl) api;
        Run run = mp.runAs(user, label, (task, result) -> {
            String oid = mp.drivers().submitMemberTask(spec, task, result);
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
            String name = mp.find(com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType.class, run.oid())
                    .getName().getOrig();
            throw new OperationFailed(label + " failed: the task \"" + name + "\" (owned by " + user + ") failed: "
                    + errors.trim(), run, null);
        }
        return run;
    }
}
