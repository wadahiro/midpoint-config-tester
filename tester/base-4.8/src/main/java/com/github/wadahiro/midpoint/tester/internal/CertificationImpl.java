package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.evolveum.midpoint.model.api.AccessCertificationService;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationCampaignType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationCaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationResponseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessCertificationWorkItemType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.Certification;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * {@link Certification} through {@code AccessCertificationService}, as the GUI does. 4.8 opens and closes stages in the
 * call; 4.9+ start a certification task for both (the driver call waits for it, see {@code Harness48}).
 */
final class CertificationImpl implements Certification {

    private final MidPointImpl mp;

    CertificationImpl(MidPointImpl mp) {
        this.mp = mp;
    }

    @Override
    public Run createCampaign(String definition) {
        AccessCertificationDefinitionType bean = mp.existing(AccessCertificationDefinitionType.class, definition);
        String definitionOid = bean.getOid();
        // labels name the run the same on every line: names and stage numbers, no OIDs or container IDs
        return mp.runAs(null, "create campaign " + bean.getName().getOrig(), (task, result) -> {
            AccessCertificationCampaignType campaign = service().createCampaign(definitionOid, task, result);
            return new MidPointImpl.Outcome(campaign.getOid(), null);
        });
    }

    @Override
    public Run openNextStage(String campaignOid) {
        AccessCertificationCampaignType campaign = campaign0(campaignOid);
        return mp.runAs(null, "open stage " + (stage(campaign) + 1) + " of " + name(campaign) + iteration(campaign), (task, result) -> {
            service().openNextStage(campaignOid, task, result);
            return new MidPointImpl.Outcome(campaignOid, null);
        });
    }

    @Override
    public Run closeCurrentStage(String campaignOid) {
        AccessCertificationCampaignType campaign = campaign0(campaignOid);
        return mp.runAs(null, "close stage " + stage(campaign) + " of " + name(campaign) + iteration(campaign), (task, result) -> {
            service().closeCurrentStage(campaignOid, task, result);
            return new MidPointImpl.Outcome(campaignOid, null);
        });
    }

    @Override
    public Run startRemediation(String campaignOid) {
        return mp.runAs(null, "start remediation of " + name(campaign0(campaignOid)), (task, result) -> {
            service().startRemediation(campaignOid, task, result);
            return new MidPointImpl.Outcome(campaignOid, null);
        });
    }

    @Override
    public Run closeCampaign(String campaignOid) {
        return mp.runAs(null, "close campaign " + name(campaign0(campaignOid)), (task, result) -> {
            service().closeCampaign(campaignOid, task, result);
            return new MidPointImpl.Outcome(campaignOid, null);
        });
    }

    @Override
    public Run reiterateCampaign(String campaignOid) {
        return mp.runAs(null, "reiterate campaign " + name(campaign0(campaignOid)), (task, result) -> {
            service().reiterateCampaign(campaignOid, task, result);
            return new MidPointImpl.Outcome(campaignOid, null);
        });
    }

    @Override
    public Run decide(WorkItem item, String reviewer, String response, String comment) {
        AccessCertificationResponseType value = AccessCertificationResponseType.fromValue(response);
        return mp.runAs(reviewer, "decide " + response + " on " + item.objectName() + "/" + item.targetName()
                        + " stage " + item.stageNumber() + (item.iteration() > 1 ? " iteration " + item.iteration() : "")
                        + " as " + reviewer,
                (task, result) -> {
                    service().recordDecision(item.campaignOid(), item.caseId(), item.id(), value, comment, task, result);
                    return new MidPointImpl.Outcome(item.campaignOid(), null);
                });
    }

    @Override
    public Campaign campaign(String campaignOid) {
        return mp.query("campaign " + campaignOid, (task, result) -> {
            AccessCertificationCampaignType bean = mp.bean(ModelService.class).getObject(
                    AccessCertificationCampaignType.class, campaignOid, GetOperationOptions.createRetrieveCollection(),
                    task, result).asObjectable();
            List<Case> cases = new ArrayList<>();
            for (AccessCertificationCaseType aCase : bean.getCase()) {
                String object = name(aCase.getObjectRef());
                String target = name(aCase.getTargetRef());
                List<WorkItem> items = aCase.getWorkItem().stream()
                        .sorted(Comparator.comparing(AccessCertificationWorkItemType::getId))
                        .map(w -> new WorkItem(campaignOid, aCase.getId(), object, target, w,
                                names(w.getAssigneeRef())))
                        .collect(Collectors.toList());
                cases.add(new Case(aCase, object, target, items));
            }
            cases.sort(Comparator.comparing(Case::id));
            return new Campaign(bean, cases);
        });
    }

    private AccessCertificationService service() {
        return mp.bean(AccessCertificationService.class);
    }

    /** The campaign without its cases (for labels). */
    private AccessCertificationCampaignType campaign0(String campaignOid) {
        return mp.existing(AccessCertificationCampaignType.class, campaignOid);
    }

    /**
     * The campaign by its definition's name: the campaign's own name has a counter ({@code <definition> 1}), which
     * depends on the campaigns created before.
     */
    private String name(AccessCertificationCampaignType campaign) {
        return campaign.getDefinitionRef() != null
                ? mp.nameOf(AccessCertificationDefinitionType.class, campaign.getDefinitionRef().getOid())
                : campaign.getName().getOrig();
    }

    /** {@code " iteration 2"} for a reiterated campaign, else empty (labels of the first iteration stay as they were). */
    private static String iteration(AccessCertificationCampaignType campaign) {
        Integer iteration = campaign.getIteration();
        return iteration != null && iteration > 1 ? " iteration " + iteration : "";
    }

    private static int stage(AccessCertificationCampaignType campaign) {
        Integer stage = campaign.getStageNumber(); // (int on 4.8 and later)
        return stage != null ? stage : 0;
    }

    private String name(ObjectReferenceType ref) {
        return ref != null ? mp.nameOf(ObjectType.class, ref.getOid()) : null;
    }

    private List<String> names(List<ObjectReferenceType> refs) {
        return refs.stream().map(this::name).collect(Collectors.toList());
    }
}
