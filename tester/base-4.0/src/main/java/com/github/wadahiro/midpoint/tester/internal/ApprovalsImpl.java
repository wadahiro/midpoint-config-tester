package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.model.api.WorkflowService;
import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.util.WorkItemId;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractWorkItemOutputType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ApprovalSchemaExecutionInformationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ApprovalStageExecutionInformationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ApprovalStageExecutionPreviewType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseWorkItemType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.InformationPartType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.InformationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ModelExecuteOptionsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PartialProcessingOptionsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PartialProcessingTypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemObjectsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.WorkItemDelegationMethodType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.WorkItemDelegationRequestType;
import com.github.wadahiro.midpoint.tester.Approvals;
import com.github.wadahiro.midpoint.tester.Run;

/** {@link Approvals} on 4.0 and 4.4: {@code previewChanges} with approvals processed, {@code WorkflowService}. */
final class ApprovalsImpl implements Approvals {

    private final MidPointImpl mp;

    ApprovalsImpl(MidPointImpl mp) {
        this.mp = mp;
    }

    @Override
    public Preview preview(ObjectDelta<? extends ObjectType> delta) {
        return preview(null, delta);
    }

    @Override
    public Preview preview(String requester, ObjectDelta<? extends ObjectType> delta) {
        Run run = mp.runAs(requester, "approval preview " + mp.describe(List.of(delta)), (task, result) -> {
            // (4.0 and 4.4 share no other way to build options)
            ModelExecuteOptions options = ModelExecuteOptions.fromModelExecutionOptionsType(new ModelExecuteOptionsType()
                    .partialProcessing(new PartialProcessingOptionsType().approvals(PartialProcessingTypeType.PROCESS)));
            ModelContext<ObjectType> context = mp.bean(ModelInteractionService.class)
                    .previewChanges(List.of(delta), options, task, result);
            return new MidPointImpl.Outcome(null, context);
        });
        List<Approval> approvals = new ArrayList<>();
        for (ApprovalSchemaExecutionInformationType info
                : run.modelContext().getHookPreviewResults(ApprovalSchemaExecutionInformationType.class)) {
            // the case the preview made (as ApprovalSchemaExecutionInformationUtil of 4.4+ reads it)
            CaseType embedded = info.getCaseRef() != null && info.getCaseRef().asReferenceValue().getObject() != null
                    ? (CaseType) info.getCaseRef().asReferenceValue().getObject().asObjectable() : null;
            List<Stage> stages = new ArrayList<>();
            for (ApprovalStageExecutionInformationType stage : info.getStage()) {
                ApprovalStageExecutionPreviewType preview = stage.getExecutionPreview();
                stages.add(new Stage(stage.getNumber() != null ? stage.getNumber() : 0,
                        stage.getDefinition() != null ? stage.getDefinition().getName() : null,
                        preview != null ? names(preview.getExpectedApproverRef()) : List.of(),
                        preview != null && preview.getExpectedAutomatedOutcome() != null ? preview.getExpectedAutomatedOutcome().value() : null,
                        preview != null && preview.getExpectedAutomatedCompletionReason() != null
                                ? preview.getExpectedAutomatedCompletionReason().value() : null));
            }
            approvals.add(new Approval(embedded != null && embedded.getName() != null ? embedded.getName().getOrig() : null,
                    stages));
        }
        return new Preview(run, approvals);
    }

    @Override
    public Run request(ObjectDelta<? extends ObjectType> delta) {
        return request(null, delta);
    }

    @Override
    public Run request(String requester, ObjectDelta<? extends ObjectType> delta) {
        return mp.runAs(requester, "request " + mp.describe(List.of(delta)), (task, result) -> {
            mp.bean(ModelService.class).executeChanges(List.of(delta), null, task, result);
            return new MidPointImpl.Outcome(delta.getOid(), null);
        });
    }

    @Override
    public List<Case> cases() {
        return mp.query("approval cases", (task, result) -> {
            ObjectQuery query = mp.prismContext().queryFor(CaseType.class)
                    .item(CaseType.F_ARCHETYPE_REF).ref(SystemObjectsType.ARCHETYPE_APPROVAL_CASE.value())
                    .build();
            List<Case> out = new ArrayList<>();
            List<PrismObject<CaseType>> found =
                    mp.bean(RepositoryService.class).searchObjects(CaseType.class, query, null, result);
            found.sort(Comparator.comparing(o -> created(o.asObjectable())));
            for (PrismObject<CaseType> object : found) {
                CaseType bean = object.asObjectable();
                List<WorkItem> items = bean.getWorkItem().stream()
                        .sorted(Comparator.comparing(CaseWorkItemType::getId))
                        .map(w -> workItem(bean.getOid(), w))
                        .collect(Collectors.toList());
                out.add(new Case(bean, fragment(bean.getOutcome()), items));
            }
            return out;
        });
    }

    @Override
    public List<WorkItem> workItems() {
        return cases().stream().flatMap(c -> c.workItems().stream()).filter(WorkItem::isOpen)
                .collect(Collectors.toList());
    }

    @Override
    public Run approve(WorkItem item) {
        return complete(item, null, true, null);
    }

    @Override
    public Run approve(WorkItem item, String approver) {
        return complete(item, approver, true, null);
    }

    @Override
    public Run reject(WorkItem item, String approver) {
        return complete(item, approver, false, null);
    }

    @Override
    public Run complete(WorkItem item, String approver, boolean approve, String comment) {
        // (no work item ID in the label: it is a container ID, which differs between lines)
        String name = (approve ? "approve " : "reject ") + item.name() + stage(item)
                + (approver != null ? " as " + approver : "");
        return mp.runAs(approver, name, (task, result) -> {
            AbstractWorkItemOutputType output = new AbstractWorkItemOutputType()
                    .outcome(approve ? SchemaConstants.MODEL_APPROVAL_OUTCOME_APPROVE
                            : SchemaConstants.MODEL_APPROVAL_OUTCOME_REJECT)
                    .comment(comment);
            mp.bean(WorkflowService.class).completeWorkItem(WorkItemId.create(item.caseOid(), item.id()), output, task,
                    result);
            return new MidPointImpl.Outcome(item.caseOid(), null);
        });
    }

    private static String stage(WorkItem item) {
        Integer stage = item.bean().getStageNumber();
        return stage != null ? " stage " + stage : "";
    }

    @Override
    public Run delegate(WorkItem item, String delegator, String... delegates) {
        return delegate("delegate ", item, delegator, WorkItemDelegationMethodType.ADD_ASSIGNEES, delegates);
    }

    @Override
    public Run forward(WorkItem item, String forwarder, String... delegates) {
        return delegate("forward ", item, forwarder, WorkItemDelegationMethodType.REPLACE_ASSIGNEES, delegates);
    }

    private Run delegate(String verb, WorkItem item, String delegator, WorkItemDelegationMethodType method,
            String... delegates) {
        return mp.runAs(delegator, verb + item.name() + stage(item), (task, result) -> {
            WorkItemDelegationRequestType request = new WorkItemDelegationRequestType();
            request.setMethod(method);
            for (String delegate : delegates) {
                request.getDelegate().add(new ObjectReferenceType()
                        .oid(mp.existing(UserType.class, delegate).getOid()).type(UserType.COMPLEX_TYPE));
            }
            mp.bean(WorkflowService.class).delegateWorkItem(WorkItemId.create(item.caseOid(), item.id()), request, task,
                    result);
            return new MidPointImpl.Outcome(item.caseOid(), null);
        });
    }

    @Override
    public Run claim(WorkItem item, String user) {
        return mp.runAs(user, "claim " + item.name() + stage(item) + " as " + user, (task, result) -> {
            mp.bean(WorkflowService.class).claimWorkItem(WorkItemId.create(item.caseOid(), item.id()), task, result);
            return new MidPointImpl.Outcome(item.caseOid(), null);
        });
    }

    @Override
    public Run release(WorkItem item, String user) {
        return mp.runAs(user, "release " + item.name() + stage(item) + " as " + user, (task, result) -> {
            mp.bean(WorkflowService.class).releaseWorkItem(WorkItemId.create(item.caseOid(), item.id()), task, result);
            return new MidPointImpl.Outcome(item.caseOid(), null);
        });
    }

    @Override
    public Run cancel(Case approvalCase) {
        return cancel(approvalCase, null);
    }

    @Override
    public Run cancel(Case approvalCase, String user) {
        return mp.runAs(user, "cancel " + approvalCase.name() + (user != null ? " as " + user : ""), (task, result) -> {
            mp.bean(WorkflowService.class).cancelCase(approvalCase.oid(), task, result);
            return new MidPointImpl.Outcome(approvalCase.oid(), null);
        });
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private WorkItem workItem(String caseOid, CaseWorkItemType bean) {
        List<String> information = new ArrayList<>();
        for (InformationType info : bean.getAdditionalInformation()) {
            String parts = info.getPart().stream().map(InformationPartType::getText).map(String::valueOf)
                    .collect(Collectors.joining("; "));
            information.add(info.getTitle() != null ? info.getTitle() + ": " + parts : parts);
        }
        return new WorkItem(caseOid, bean, names(bean.getAssigneeRef()), names(bean.getCandidateRef()), information,
                bean.getOutput() != null ? fragment(bean.getOutput().getOutcome()) : null,
                bean.getPerformerRef() != null ? mp.nameOf(ObjectType.class, bean.getPerformerRef().getOid()) : null);
    }

    private List<String> names(List<ObjectReferenceType> refs) {
        return refs.stream().map(r -> mp.nameOf(ObjectType.class, r.getOid())).collect(Collectors.toList());
    }

    private static String created(CaseType bean) {
        return bean.getMetadata() != null && bean.getMetadata().getCreateTimestamp() != null
                ? bean.getMetadata().getCreateTimestamp().toXMLFormat() : "";
    }

    /** {@code approve} of {@code http://.../outcome#approve}. */
    static String fragment(String uri) {
        return uri != null && uri.contains("#") ? uri.substring(uri.indexOf('#') + 1) : uri;
    }
}
