package com.github.wadahiro.midpoint.tester;

import java.util.List;

import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseWorkItemType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * Approvals ({@link MidPoint#approvals()}): what midPoint would ask (preview), the cases and work items it creates for
 * a change, and completing them as the approvers. Every driver returns a {@link Run}; the evaluations of approver
 * expressions, {@code automaticallyCompleted}, {@code additionalInformation}, {@code approvalDisplayName} and the
 * approval action's condition are in it, selectable by structure:
 *
 * <pre>{@code
 * Approvals.Preview preview = mp.approvals().preview(assign("jack", "Privileged Access"));
 * assertThat(preview.single().stage(1).expectedApprovers()).containsExactly("cc-owner", "IT department");
 * Run run = mp.approvals().request(assign("jack", "Privileged Access"));
 * Selection approvers = run.select().within("stage", "number", "1").element("approverExpression");
 * Approvals.WorkItem item = mp.approvals().workItems().get(0);
 * mp.approvals().approve(item, "cc-owner");
 * }</pre>
 *
 * Users who complete work items need the authorization to (e.g. the Approver role of the baseline); the administrator
 * can complete any work item.
 */
public interface Approvals {

    /**
     * The approvals midPoint would start for the change, computed as the GUI's preview does
     * ({@code partialProcessing/approvals=process}): approvers per stage, automatic completion; nothing is stored.
     */
    Preview preview(ObjectDelta<? extends ObjectType> delta);

    /** Like {@link #preview(ObjectDelta)}, requested by this user (by name). */
    Preview preview(String requester, ObjectDelta<? extends ObjectType> delta);

    /** Executes the change (as the administrator): what needs approval becomes cases, the rest is executed. */
    Run request(ObjectDelta<? extends ObjectType> delta);

    /** Like {@link #request(ObjectDelta)}, requested by this user (by name), as a self-service request would be. */
    Run request(String requester, ObjectDelta<? extends ObjectType> delta);

    /**
     * The approval cases (one per approval, not the request's root case), oldest first. When only a part of a change
     * needs approval (e.g. a {@code processSpecification} with {@code deltaFrom}), the rest is a case of its own,
     * {@code Changes that do not require approval}, closed at once and executed with the approved parts.
     */
    List<Case> cases();

    /** The open work items of all cases, oldest first. */
    List<WorkItem> workItems();

    /** Approves the work item as the administrator. */
    Run approve(WorkItem item);

    /** Approves the work item as this user (by name). */
    Run approve(WorkItem item, String approver);

    Run reject(WorkItem item, String approver);

    /**
     * Completes the work item as this user ({@code CaseService.completeWorkItem}). When the last stage completes,
     * midPoint executes the approved change in a background task: the run includes it.
     */
    Run complete(WorkItem item, String approver, boolean approve, String comment);

    /** Delegates the work item to other users (by name), as {@code delegator}: they are added as assignees. */
    Run delegate(WorkItem item, String delegator, String... delegates);

    /**
     * Forwards the work item to other users (by name), as {@code forwarder}: they replace its assignees (the GUI's
     * Forward button, a delegation with the method {@code replaceAssignees}).
     */
    Run forward(WorkItem item, String forwarder, String... delegates);

    /**
     * Claims a work item offered to candidates (e.g. the members of an org given as approver) as this user (by name),
     * who must be one of them: the user becomes its assignee.
     */
    Run claim(WorkItem item, String user);

    /** Releases a claimed work item as its only assignee (by name): it is offered to its candidates again. */
    Run release(WorkItem item, String user);

    /** Cancels the approval case as the administrator: it closes without an outcome, the change is not executed. */
    Run cancel(Case approvalCase);

    /** Cancels the approval case as this user (by name; {@code CaseService.cancelCase}). */
    Run cancel(Case approvalCase, String user);

    // ---- what midPoint shows -----------------------------------------------------------------------------------

    /** What a preview returned, and its run. */
    final class Preview {
        private final Run run;
        private final List<Approval> approvals;

        public Preview(Run run, List<Approval> approvals) {
            this.run = run;
            this.approvals = List.copyOf(approvals);
        }

        public Run run() {
            return run;
        }

        /** One per approval case midPoint would start (one per approval action that applies). */
        public List<Approval> approvals() {
            return approvals;
        }

        /** The only approval; fails if there is none or more than one. */
        public Approval single() {
            if (approvals.size() != 1) {
                throw new AssertionError("Expected one approval, found " + approvals.size() + ": " + approvals);
            }
            return approvals.get(0);
        }

        @Override
        public String toString() {
            return approvals.toString();
        }
    }

    /** One approval of a preview: its case name and the stages of its approval schema. */
    final class Approval {
        private final String name;
        private final List<Stage> stages;

        public Approval(String name, List<Stage> stages) {
            this.name = name;
            this.stages = List.copyOf(stages);
        }

        /** The name the case would get (from {@code approvalDisplayName} when set). */
        public String name() {
            return name;
        }

        public List<Stage> stages() {
            return stages;
        }

        public Stage stage(int number) {
            return stages.stream().filter(s -> s.number() == number).findFirst()
                    .orElseThrow(() -> new AssertionError("No stage " + number + " in " + stages));
        }

        @Override
        public String toString() {
            return name + " " + stages;
        }
    }

    /** A stage as previewed: who would approve, or how it would complete automatically. */
    final class Stage {
        private final int number;
        private final String name;
        private final List<String> expectedApprovers;
        private final String automatedOutcome;
        private final String automatedCompletionReason;

        public Stage(int number, String name, List<String> expectedApprovers, String automatedOutcome,
                String automatedCompletionReason) {
            this.number = number;
            this.name = name;
            this.expectedApprovers = List.copyOf(expectedApprovers);
            this.automatedOutcome = automatedOutcome;
            this.automatedCompletionReason = automatedCompletionReason;
        }

        public int number() {
            return number;
        }

        public String name() {
            return name;
        }

        /** Names of the users, roles or orgs that would get work items. */
        public List<String> expectedApprovers() {
            return expectedApprovers;
        }

        /** {@code approve} or {@code reject} when the stage would complete without approvers, else null. */
        public String automatedOutcome() {
            return automatedOutcome;
        }

        /** Why ({@code autoCompletionCondition}, {@code noAssigneesFound}, ...), or null. */
        public String automatedCompletionReason() {
            return automatedCompletionReason;
        }

        @Override
        public String toString() {
            return number + ":" + name + " " + expectedApprovers
                    + (automatedOutcome != null ? " auto " + automatedOutcome + " (" + automatedCompletionReason + ")" : "");
        }
    }

    /** An approval case as stored now. */
    final class Case {
        private final CaseType bean;
        private final String outcome;
        private final List<WorkItem> workItems;

        public Case(CaseType bean, String outcome, List<WorkItem> workItems) {
            this.bean = bean;
            this.outcome = outcome;
            this.workItems = List.copyOf(workItems);
        }

        public String oid() {
            return bean.getOid();
        }

        public String name() {
            return bean.getName() != null ? bean.getName().getOrig() : null;
        }

        /** {@code created}, {@code open}, {@code closing}, {@code closed}. */
        public String state() {
            return bean.getState();
        }

        public Integer stageNumber() {
            return bean.getStageNumber();
        }

        /** {@code approve}, {@code reject}, or null while open. */
        public String outcome() {
            return outcome;
        }

        /** All work items, open and closed. */
        public List<WorkItem> workItems() {
            return workItems;
        }

        public CaseType bean() {
            return bean;
        }

        @Override
        public String toString() {
            return name() + " (" + state() + ", stage " + stageNumber() + (outcome != null ? ", " + outcome : "") + ")";
        }
    }

    /** A work item as stored now; users, roles and orgs by name. */
    final class WorkItem {
        private final String caseOid;
        private final CaseWorkItemType bean;
        private final List<String> assignees;
        private final List<String> candidates;
        private final List<String> additionalInformation;
        private final String outcome;
        private final String performer;

        public WorkItem(String caseOid, CaseWorkItemType bean, List<String> assignees, List<String> candidates,
                List<String> additionalInformation, String outcome, String performer) {
            this.caseOid = caseOid;
            this.bean = bean;
            this.assignees = List.copyOf(assignees);
            this.candidates = List.copyOf(candidates);
            this.additionalInformation = List.copyOf(additionalInformation);
            this.outcome = outcome;
            this.performer = performer;
        }

        public String caseOid() {
            return caseOid;
        }

        public long id() {
            return bean.getId();
        }

        public String name() {
            return bean.getName() != null ? bean.getName().getOrig() : null;
        }

        public Integer stageNumber() {
            return bean.getStageNumber();
        }

        public List<String> assignees() {
            return assignees;
        }

        /** Who may claim it (e.g. the members of an org given as approver). */
        public List<String> candidates() {
            return candidates;
        }

        /**
         * The {@code additionalInformation} as text, one entry per information: {@code "title: part; part"}, or the
         * parts alone when there is no title.
         */
        public List<String> additionalInformation() {
            return additionalInformation;
        }

        public boolean isOpen() {
            return bean.getCloseTimestamp() == null;
        }

        /** {@code approve} or {@code reject} once completed, else null. */
        public String outcome() {
            return outcome;
        }

        /** Who completed it, or null. */
        public String performer() {
            return performer;
        }

        /** 0 unless escalated. */
        public int escalationLevel() {
            return bean.getEscalationLevel() != null && bean.getEscalationLevel().getNumber() != null
                    ? bean.getEscalationLevel().getNumber() : 0;
        }

        public CaseWorkItemType bean() {
            return bean;
        }

        @Override
        public String toString() {
            return name() + " #" + id() + " stage " + stageNumber() + " assignees " + assignees
                    + (candidates.isEmpty() ? "" : " candidates " + candidates) + (isOpen() ? "" : " " + outcome);
        }
    }
}
