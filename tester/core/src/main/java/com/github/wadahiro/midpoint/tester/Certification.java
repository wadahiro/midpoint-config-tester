package com.github.wadahiro.midpoint.tester;

import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationCampaignType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationCaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationWorkItemType;

/**
 * Access certification ({@link MidPoint#certification()}) as the GUI's campaign buttons do: through
 * {@code AccessCertificationService} on 4.8 and later, {@code CertificationManager} on 4.0 and 4.4. Every driver returns a {@link Run}: {@code itemSelectionExpression} runs when the campaign's first stage
 * opens, {@code reviewerExpression} when each stage opens.
 *
 * <pre>{@code
 * String campaign = mp.certification().createCampaign("role-assignments").oid();
 * Run open = mp.certification().openNextStage(campaign);
 * Selection reviewers = open.select().within("stageDefinition", "number", "1").element("reviewerExpression");
 * Certification.WorkItem item = mp.certification().campaign(campaign).cases().get(0).workItems().get(0);
 * mp.certification().decide(item, "sec-officer", "revoke", "not needed");
 * mp.certification().closeCurrentStage(campaign);
 * mp.certification().startRemediation(campaign);   // revocations executed in a background task: in the run
 * }</pre>
 *
 * Work items are delegated and escalated by a stage's {@code timedActions} only (there is no service call for them):
 * move midPoint's clock and run the trigger scanner ({@code MidPointConfigTester#clock()}).
 */
public interface Certification {

    /** Creates a campaign from the definition (by name or OID); {@link Run#oid()} is the campaign's OID. */
    Run createCampaign(String definition);

    /** Opens the next stage: on the first, selects the cases; on each, assigns reviewers. */
    Run openNextStage(String campaignOid);

    Run closeCurrentStage(String campaignOid);

    /** Starts the remediation (after the last stage is closed): revoked assignments are removed in a task. */
    Run startRemediation(String campaignOid);

    Run closeCampaign(String campaignOid);

    /**
     * Reiterates a closed campaign: the cases without a response go back to stage 0 of the next iteration, the next
     * {@link #openNextStage(String)} evaluates their reviewers again. Labels of a later iteration name it
     * ({@code open stage 1 of <definition> iteration 2}).
     */
    Run reiterateCampaign(String campaignOid);

    /**
     * Records a reviewer's decision as this user (by name): {@code accept}, {@code revoke}, {@code reduce},
     * {@code notDecided} or {@code noResponse}.
     */
    Run decide(WorkItem item, String reviewer, String response, String comment);

    /** The campaign as stored now, with its cases. */
    Campaign campaign(String campaignOid);

    final class Campaign {
        private final AccessCertificationCampaignType bean;
        private final List<Case> cases;

        public Campaign(AccessCertificationCampaignType bean, List<Case> cases) {
            this.bean = bean;
            this.cases = List.copyOf(cases);
        }

        public String oid() {
            return bean.getOid();
        }

        public String name() {
            return bean.getName() != null ? bean.getName().getOrig() : null;
        }

        /** {@code CREATED}, {@code IN_REVIEW_STAGE}, {@code REVIEW_STAGE_DONE}, {@code IN_REMEDIATION}, {@code CLOSED}. */
        public String state() {
            return bean.getState() != null ? bean.getState().name() : null;
        }

        public int stageNumber() {
            return bean.getStageNumber();
        }

        /** 1 unless reiterated. */
        public int iteration() {
            Integer iteration = bean.getIteration(); // (int or Integer, depending on the line)
            return iteration != null ? iteration : 1;
        }

        public List<Case> cases() {
            return cases;
        }

        /** The case of this object and target (by name); fails if there is none. */
        public Case caseOf(String object, String target) {
            return cases.stream().filter(c -> object.equals(c.objectName()) && target.equals(c.targetName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No case " + object + " / " + target + " in " + cases));
        }

        public AccessCertificationCampaignType bean() {
            return bean;
        }

        @Override
        public String toString() {
            return name() + " (" + state() + ", stage " + stageNumber() + ") " + cases;
        }
    }

    /** One certified item (e.g. an assignment): object and target by name. */
    final class Case {
        private final AccessCertificationCaseType bean;
        private final String objectName;
        private final String targetName;
        private final List<WorkItem> workItems;

        public Case(AccessCertificationCaseType bean, String objectName, String targetName, List<WorkItem> workItems) {
            this.bean = bean;
            this.objectName = objectName;
            this.targetName = targetName;
            this.workItems = List.copyOf(workItems);
        }

        public long id() {
            return bean.getId();
        }

        public String objectName() {
            return objectName;
        }

        public String targetName() {
            return targetName;
        }

        /** The outcome so far ({@code accept}, {@code revoke}, ... as the URI's fragment), or null. */
        public String outcome() {
            String outcome = bean.getOutcome();
            return outcome != null && outcome.contains("#") ? outcome.substring(outcome.indexOf('#') + 1) : outcome;
        }

        /** Work items of every stage and iteration. */
        public List<WorkItem> workItems() {
            return workItems;
        }

        /** The stage the case is in (0 before the first stage of its iteration opens). */
        public int stageNumber() {
            Integer stage = bean.getStageNumber();
            return stage != null ? stage : 0;
        }

        /** The iteration the case was last reviewed in: 1 unless the campaign was reiterated for it. */
        public int iteration() {
            Integer iteration = bean.getIteration(); // (int or Integer, depending on the line)
            return iteration != null ? iteration : 1;
        }

        /** The reviewers (by name) of the stage's work items. */
        public List<String> reviewers(int stage) {
            return workItems.stream().filter(w -> w.stageNumber() == stage)
                    .flatMap(w -> w.reviewers().stream()).distinct().collect(java.util.stream.Collectors.toList());
        }

        public AccessCertificationCaseType bean() {
            return bean;
        }

        @Override
        public String toString() {
            return objectName + "/" + targetName + " " + workItems;
        }
    }

    /** A reviewer's work item of a case; the case's object and target by name (what driver labels use). */
    final class WorkItem {
        private final String campaignOid;
        private final long caseId;
        private final String objectName;
        private final String targetName;
        private final AccessCertificationWorkItemType bean;
        private final List<String> reviewers;

        public WorkItem(String campaignOid, long caseId, String objectName, String targetName,
                AccessCertificationWorkItemType bean, List<String> reviewers) {
            this.campaignOid = campaignOid;
            this.caseId = caseId;
            this.objectName = objectName;
            this.targetName = targetName;
            this.bean = bean;
            this.reviewers = List.copyOf(reviewers);
        }

        public String campaignOid() {
            return campaignOid;
        }

        public long caseId() {
            return caseId;
        }

        /** The case's object, by name. */
        public String objectName() {
            return objectName;
        }

        /** The case's target (e.g. the assigned role), by name. */
        public String targetName() {
            return targetName;
        }

        public long id() {
            return bean.getId();
        }

        public int stageNumber() {
            return bean.getStageNumber();
        }

        /** 1 unless the campaign was reiterated. */
        public int iteration() {
            Integer iteration = bean.getIteration(); // (int or Integer, depending on the line)
            return iteration != null ? iteration : 1;
        }

        /** 0 unless escalated (a stage's {@code timedActions/escalate}). */
        public int escalationLevel() {
            return bean.getEscalationLevel() != null && bean.getEscalationLevel().getNumber() != null
                    ? bean.getEscalationLevel().getNumber() : 0;
        }

        /** The assignees, by name. */
        public List<String> reviewers() {
            return reviewers;
        }

        /** The response ({@code accept}, {@code revoke}...), or null. */
        public String response() {
            String outcome = bean.getOutput() != null ? bean.getOutput().getOutcome() : null;
            return outcome != null && outcome.contains("#") ? outcome.substring(outcome.indexOf('#') + 1) : outcome;
        }

        public boolean isOpen() {
            return bean.getCloseTimestamp() == null;
        }

        public AccessCertificationWorkItemType bean() {
            return bean;
        }

        @Override
        public String toString() {
            return "stage " + stageNumber() + (iteration() > 1 ? " iteration " + iteration() : "") + " " + reviewers
                    + (response() != null ? " " + response() : "");
        }
    }
}
