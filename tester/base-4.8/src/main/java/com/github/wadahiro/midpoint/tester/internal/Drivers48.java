package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import jakarta.xml.bind.JAXBElement;

import com.evolveum.midpoint.xml.ns._public.common.audit_3.AuditEventRecordType;
import com.evolveum.midpoint.model.api.ModelAuthorizationAction;
import com.evolveum.midpoint.prism.delta.ContainerDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.AccessDecision;
import com.evolveum.midpoint.schema.RelationRegistry;
import com.evolveum.midpoint.security.enforcer.api.ObjectSecurityConstraints;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationDecisionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationPhaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrderConstraintsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationTypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserInterfaceElementVisibilityType;
import com.evolveum.midpoint.model.api.BulkActionExecutionOptions;
import com.evolveum.midpoint.model.api.BulkActionExecutionResult;
import com.evolveum.midpoint.model.api.BulkActionsService;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.PipelineItem;
import com.evolveum.midpoint.model.api.validator.StringLimitationResult;
import com.evolveum.midpoint.model.common.expression.ModelExpressionEnvironment.ExpressionEnvironmentBuilder;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismPropertyValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.delta.PrismValueDeltaSetTriple;
import com.evolveum.midpoint.repo.common.expression.Expression;
import com.evolveum.midpoint.repo.common.expression.ExpressionEnvironmentThreadLocalHolder;
import com.evolveum.midpoint.repo.common.expression.ExpressionEvaluationContext;
import com.evolveum.midpoint.repo.common.expression.ExpressionFactory;
import com.evolveum.midpoint.schema.SchemaConstantsGenerated;
import com.evolveum.midpoint.schema.config.ConfigurationItemOrigin;
import com.evolveum.midpoint.schema.config.ExecuteScriptConfigItem;
import com.evolveum.midpoint.schema.constants.ExpressionConstants;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ScriptingBeansUtil;
import com.evolveum.midpoint.security.enforcer.api.AuthorizationParameters;
import com.evolveum.midpoint.security.enforcer.api.SecurityEnforcer;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ModelExecuteOptionsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ImportOptionsType;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ExpressionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ScriptExpressionEvaluatorType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ValuePolicyType;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ExecuteScriptType;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ScriptingExpressionType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType;
import com.github.wadahiro.midpoint.tester.BulkActionOutput;
import com.github.wadahiro.midpoint.tester.Mail;
import com.github.wadahiro.midpoint.tester.Notification;

/** {@link LineDrivers} of the 4.8+ lines. */
final class Drivers48 implements LineDrivers {

    private static final String SCRIPT_DESCRIPTION = "tester script";
    /** A generated value's length when the policy does not say. */
    private static final int DEFAULT_LENGTH = 10;

    private final Harness48 harness;

    Drivers48(Harness48 harness) {
        this.harness = harness;
    }

    @Override
    public List<Notification> notifications() {
        return harness.notificationTransport().sent();
    }

    @Override
    public void clearNotifications() {
        harness.notificationTransport().clear();
    }

    @Override
    public List<PrismObject<? extends ObjectType>> searchCollection(String collectionOid, Task task,
            OperationResult result) throws Exception {
        com.evolveum.midpoint.xml.ns._public.common.common_3.CollectionRefSpecificationType spec =
                new com.evolveum.midpoint.xml.ns._public.common.common_3.CollectionRefSpecificationType()
                        .collectionRef(collectionOid,
                                com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionType.COMPLEX_TYPE);
        // as the GUI: the collection's definition compiled privileged (the filter's expressions still see the
        // logged-in user), its objects searched as the user
        com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView view = privileged(
                () -> harness.bean(com.evolveum.midpoint.model.api.ModelInteractionService.class)
                        .compileObjectCollectionView(spec, null, task, result));
        @SuppressWarnings("unchecked")
        Class<ObjectType> type = (Class<ObjectType>) (Class<?>) view.getTargetClass();
        // the compiled filter keeps its expressions (the GUI evaluates them for the logged-in user)
        com.evolveum.midpoint.prism.query.ObjectFilter filter = view.getFilter() == null ? null
                : com.evolveum.midpoint.repo.common.expression.ExpressionUtil.evaluateFilterExpressions(view.getFilter(),
                        new com.evolveum.midpoint.schema.expression.VariablesMap(),
                        com.evolveum.midpoint.schema.util.MiscSchemaUtil.getExpressionProfile(),
                        harness.bean(com.evolveum.midpoint.repo.common.expression.ExpressionFactory.class),
                        "collection filter", task, result);
        return new ArrayList<>(harness.bean(com.evolveum.midpoint.model.api.ModelService.class).searchObjects(type,
                harness.bean(PrismContext.class).queryFactory().createQuery(filter), null, task, result));
    }

    private interface Privileged<T> {
        T run() throws Exception;
    }

    /** The code with all authorizations added to the logged-in user's (midPoint's {@code runPrivileged}). */
    private <T> T privileged(Privileged<T> code) throws Exception {
        Exception[] failure = new Exception[1];
        T value = harness.bean(com.evolveum.midpoint.security.api.SecurityContextManager.class).runPrivileged(() -> {
            try {
                return code.run();
            } catch (Exception e) {
                failure[0] = e;
                return null;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return value;
    }

    @Override
    public long auditPosition() {
        PrismContext prismContext = harness.bean(PrismContext.class);
        List<AuditEventRecordType> last = searchAudit(prismContext.queryFor(AuditEventRecordType.class)
                .desc(AuditEventRecordType.F_REPO_ID).maxSize(1).build());
        return last.isEmpty() ? AuditRecords.START : last.get(0).getRepoId();
    }

    @Override
    public List<com.github.wadahiro.midpoint.tester.AuditRecord> auditRecordsAfter(long position) {
        PrismContext prismContext = harness.bean(PrismContext.class);
        List<com.github.wadahiro.midpoint.tester.AuditRecord> out = new ArrayList<>();
        for (AuditEventRecordType r : searchAudit(prismContext.queryFor(AuditEventRecordType.class)
                .item(AuditEventRecordType.F_REPO_ID).gt(position).asc(AuditEventRecordType.F_REPO_ID).build())) {
            ObjectReferenceType target = r.getTargetRef();
            ObjectReferenceType initiator = r.getInitiatorRef();
            List<String> deltas = new ArrayList<>();
            for (com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectDeltaOperationType d : r.getDelta()) {
                com.evolveum.prism.xml.ns._public.types_3.ObjectDeltaType delta = d.getObjectDelta();
                if (delta != null) {
                    deltas.add(AuditRecords.delta(delta.getChangeType(),
                            delta.getObjectType() != null ? delta.getObjectType().getLocalPart() : null, delta.getOid(),
                            d.getExecutionResult() != null ? d.getExecutionResult().getStatus() : null));
                }
            }
            out.add(new com.github.wadahiro.midpoint.tester.AuditRecord(name(r.getEventType()), name(r.getEventStage()),
                    name(r.getOutcome()),
                    r.getTimestamp() != null ? r.getTimestamp().toGregorianCalendar().toInstant() : null,
                    target != null ? target.getOid() : null, refName(target),
                    target != null && target.getType() != null ? target.getType().getLocalPart() : null,
                    initiator != null ? initiator.getOid() : null, refName(initiator), r.getChannel(), r.getTaskOID(),
                    r.getMessage(), deltas));
        }
        return out;
    }

    /** The audit records of the query, through midPoint's audit service (the repository's, as configured). */
    private List<AuditEventRecordType> searchAudit(com.evolveum.midpoint.prism.query.ObjectQuery query) {
        try {
            return harness.bean(com.evolveum.midpoint.init.AuditServiceProxy.class).searchObjects(query, null,
                    new OperationResult("tester audit"));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the audit log", e);
        }
    }

    private static String refName(ObjectReferenceType ref) {
        return ref != null && ref.getTargetName() != null ? ref.getTargetName().getOrig() : null;
    }

    private static String name(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    @Override
    public List<Mail> mails() {
        return harness.smtpServer().mails();
    }

    @Override
    public void clearMails() {
        harness.smtpServer().clear();
    }

    @Override
    public void keepMailsAndNotifications(int mails, int notifications) {
        harness.smtpServer().keep(mails);
        harness.notificationTransport().keep(notifications);
    }

    @Override
    public int smtpPort() {
        return harness.smtpServer().port();
    }

    @Override
    public void rejectMails(java.util.function.Predicate<String> recipient) {
        harness.smtpServer().reject(recipient);
    }

    @Override
    public boolean isAuthorized(String actionUrl, PrismObject<? extends ObjectType> object, Task task,
            OperationResult result) throws Exception {
        return harness.bean(SecurityEnforcer.class).isAuthorized(actionUrl, null,
                AuthorizationParameters.Builder.buildObject(object), SecurityEnforcer.Options.create(), task, result);
    }

    @Override
    public boolean decideAccess(java.util.Collection<String> actionUrls, Task task, OperationResult result)
            throws Exception {
        return harness.bean(SecurityEnforcer.class).decideAccess(
                com.evolveum.midpoint.security.api.SecurityUtil.getPrincipal(), actionUrls, task, result)
                == com.evolveum.midpoint.schema.AccessDecision.ALLOW;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public com.evolveum.midpoint.prism.query.ObjectFilter selectorFilter(
            com.evolveum.midpoint.xml.ns._public.common.common_3.OwnedObjectSelectorType selector,
            Class<? extends ObjectType> type, Task task, OperationResult result) throws Exception {
        // a #read of the selector alone: midPoint's own translation of the selector into a search filter
        String read = ModelAuthorizationAction.READ.getUrl();
        com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType probe = new com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType();
        probe.getAction().add(read);
        probe.getObject().add(selector.clone());
        com.evolveum.midpoint.security.api.MidPointPrincipal principal =
                com.evolveum.midpoint.security.api.SecurityUtil.getPrincipal().clone();
        // its authorizations cleared: resetAuthorizationsList on 4.8 and 4.10, clearAuthorizations on 4.9
        try {
            principal.getClass().getMethod("resetAuthorizationsList", List.class).invoke(principal, List.of());
        } catch (NoSuchMethodException e) {
            principal.getClass().getMethod("clearAuthorizations").invoke(principal);
        }
        principal.addAuthorization(new com.evolveum.midpoint.security.api.Authorization(probe));
        // 4.9+ also take the actions that allow searching by an item (searchByOperationUrls): no filter here
        SecurityEnforcer enforcer = harness.bean(SecurityEnforcer.class);
        for (java.lang.reflect.Method method : SecurityEnforcer.class.getMethods()) {
            if (!method.getName().equals("preProcessObjectFilter")
                    || method.getParameterCount() != 10 && method.getParameterCount() != 11) {
                continue;
            }
            Object[] args = method.getParameterCount() == 10
                    ? new Object[] {principal, new String[] {read}, null, type, null, null, List.of(),
                            SecurityEnforcer.Options.create(), task, result}
                    : new Object[] {principal, new String[] {read}, new String[] {read}, null, type, null, null,
                            List.of(), SecurityEnforcer.Options.create(), task, result};
            try {
                return (com.evolveum.midpoint.prism.query.ObjectFilter) method.invoke(enforcer, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
        }
        throw new IllegalStateException("No preProcessObjectFilter in " + SecurityEnforcer.class.getName());
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean isAuthorizedToAssign(PrismObject<? extends ObjectType> focus,
            PrismObject<? extends ObjectType> target, QName relation, boolean assign, Task task,
            OperationResult result) throws Exception {
        // as ClockworkRequestAuthorizer (ClockworkAuthorizationHelper before 4.8), then the execution's #modify
        SecurityEnforcer enforcer = harness.bean(SecurityEnforcer.class);
        PrismContext prismContext = harness.bean(PrismContext.class);
        QName effectiveRelation = relation != null ? relation : prismContext.getDefaultRelation();
        ObjectReferenceType targetRef = new ObjectReferenceType();
        targetRef.setOid(target.getOid());
        targetRef.setType(target.getDefinition().getTypeName());
        targetRef.setRelation(effectiveRelation);
        AssignmentType assignment = new AssignmentType();
        assignment.setTargetRef(targetRef);
        PrismObject focusObject = focus;
        ObjectDelta delta = focusObject.createModifyDelta();
        ContainerDelta<AssignmentType> assignmentDelta = delta.createContainerModification(
                AssignmentHolderType.F_ASSIGNMENT);
        if (assign) {
            assignmentDelta.addValuesToAdd(assignment.asPrismContainerValue().clone());
        } else {
            assignmentDelta.addValuesToDelete(assignment.asPrismContainerValue().clone());
        }
        String modify = ModelAuthorizationAction.MODIFY.getUrl();
        ObjectSecurityConstraints constraints = enforcer.compileSecurityConstraints(focus, true, SecurityEnforcer.Options.create(), task, result);
        AccessDecision itemDecision = enforcer.determineItemDecision(constraints, delta, focus, modify, AuthorizationPhaseType.REQUEST,
                AssignmentHolderType.F_ASSIGNMENT);
        boolean request;
        if (itemDecision != AccessDecision.DEFAULT) {
            request = itemDecision == AccessDecision.ALLOW;
        } else {
            AuthorizationDecisionType allItems = constraints.findAllItemsDecision(modify,
                    AuthorizationPhaseType.REQUEST);
            if (allItems != null) {
                request = allItems == AuthorizationDecisionType.ALLOW;
            } else {
                // the clockwork's delta of the value: an add, for an unassignment too
                ObjectDelta valueDelta = focusObject.createModifyDelta();
                valueDelta.createContainerModification(AssignmentHolderType.F_ASSIGNMENT)
                        .addValuesToAdd(assignment.asPrismContainerValue().clone());
                AuthorizationParameters params = new AuthorizationParameters.Builder()
                        .oldObject(focusObject)
                        .delta(valueDelta)
                        .target(target)
                        .relation(effectiveRelation)
                        .orderConstraints(List.of(new OrderConstraintsType().order(0)))
                        .fullInformationAvailable(true)
                        .build();
                String action = (assign ? ModelAuthorizationAction.ASSIGN : ModelAuthorizationAction.UNASSIGN)
                        .getUrl();
                request = enforcer.isAuthorized(action, AuthorizationPhaseType.REQUEST, params, SecurityEnforcer.Options.create(), task,
                        result)
                        || harness.bean(RelationRegistry.class).isDelegation(effectiveRelation)
                        && enforcer.isAuthorized(ModelAuthorizationAction.DELEGATE.getUrl(),
                                AuthorizationPhaseType.REQUEST, params, SecurityEnforcer.Options.create(), task, result);
            }
        }
        return request && enforcer.isAuthorized(modify, AuthorizationPhaseType.EXECUTION,
                AuthorizationParameters.Builder.buildObjectDelta(focusObject, delta, true), SecurityEnforcer.Options.create(), task, result);
    }

    @Override
    public com.github.wadahiro.midpoint.tester.GuiProfile guiProfile(Task task, OperationResult result)
            throws Exception {
        com.evolveum.midpoint.model.api.authentication.CompiledGuiProfile compiled =
                ((com.evolveum.midpoint.model.api.authentication.GuiProfiledPrincipal)
                        com.evolveum.midpoint.security.api.SecurityUtil.getPrincipal())
                        .getCompiledGuiProfile();
        com.github.wadahiro.midpoint.tester.GuiProfile profile = new com.github.wadahiro.midpoint.tester.GuiProfile()
                .objectDetails(compiled.getObjectDetails())
                .defaultTimezone(compiled.getDefaultTimezone())
                .preferredDataLanguage(compiled.getPreferredDataLanguage())
                .compiled(compiled);
        for (com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView view
                : compiled.getObjectCollectionViews()) {
            UserInterfaceElementVisibilityType visibility = view.getVisibility();
            OperationTypeType operation = view.getApplicableForOperation();
            // LeftMenuPanel.addCollectionsMenuItems
            boolean inMenu = view.getContainerType() != null
                    && MENU_TYPES.contains(view.getContainerType().getLocalPart())
                    && !view.isDefaultView() && visibility != UserInterfaceElementVisibilityType.HIDDEN
                    && visibility != UserInterfaceElementVisibilityType.VACANT
                    && (operation == null || operation == OperationTypeType.MODIFY);
            profile.views().add(new com.github.wadahiro.midpoint.tester.GuiProfile.CollectionView(
                    view.getContainerType(), view.getViewIdentifier(), label(view.getDisplay()),
                    view.getDisplayOrder(), visibility, view.isDefaultView(),
                    operation == null ? null : operation.value(), inMenu));
        }
        profile.additionalMenuLinks().addAll(compiled.getAdditionalMenuLink());
        profile.userDashboardLinks().addAll(compiled.getUserDashboardLink());
        profile.features().addAll(compiled.getFeatures());
        return profile;
    }

    /** The types whose side menu section lists their views (LeftMenuPanel.createBasicAssignmentHolderMenuItems). */
    private static final List<String> MENU_TYPES = List.of("UserType", "OrgType", "RoleType", "ServiceType",
            "ResourceType", "CaseType", "TaskType", "ReportType", "ArchetypeType", "ObjectCollectionType",
            "ObjectTemplateType", "MarkType", "MessageTemplateType", "PolicyType", "SchemaType");

    private static String label(com.evolveum.midpoint.xml.ns._public.common.common_3.DisplayType display) {
        return display == null || display.getLabel() == null ? null : display.getLabel().getOrig();
    }

    @Override
    public Map<String, Boolean> validateValue(String value, ValuePolicyType policy,
            PrismObject<? extends ObjectType> object, Task task, OperationResult result) throws Exception {
        List<StringLimitationResult> limitations = harness.bean(ModelInteractionService.class)
                .validateValue(new ProtectedStringType().clearValue(value), policy, object, task, result);
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (StringLimitationResult limitation : limitations) {
            out.put(name(limitation.getName()), Boolean.TRUE.equals(limitation.isSuccess()));
        }
        return out;
    }

    private static String name(PolyStringType name) {
        if (name == null) {
            return null;
        }
        if (name.getOrig() != null) {
            return name.getOrig();
        }
        return name.getTranslation() != null ? name.getTranslation().getKey() : null;
    }

    @Override
    public String generateValue(ValuePolicyType policy, PrismObject<? extends ObjectType> object, Task task,
            OperationResult result) throws Exception {
        return harness.bean(ModelInteractionService.class)
                .generateValue(policy, DEFAULT_LENGTH, false, object, "tester value generation", task, result);
    }

    @Override
    public List<Object> evaluateScript(String languageUri, String code, Map<String, Object> variables,
            Map<String, Class<?>> types, QName returnType, Task task, OperationResult result) throws Exception {
        ExpressionFactory expressionFactory = harness.bean(ExpressionFactory.class);
        ScriptExpressionEvaluatorType script = new ScriptExpressionEvaluatorType().code(code);
        script.setLanguage(languageUri);
        ExpressionType expression = new ExpressionType();
        expression.getExpressionEvaluator()
                .add(new JAXBElement<>(SchemaConstantsGenerated.C_SCRIPT, ScriptExpressionEvaluatorType.class, script));
        PrismPropertyDefinition<Object> output = LineSupport.outputDefinition(ExpressionConstants.OUTPUT_ELEMENT_NAME,
                returnType != null ? returnType : DOMUtil.XSD_STRING);

        VariablesMap variablesMap = new VariablesMap();
        for (Map.Entry<String, Object> e : variables.entrySet()) {
            Object value = e.getValue();
            if (value instanceof Objectable) {
                value = ((Objectable) value).asPrismObject();
            }
            Class<?> type = types.get(e.getKey());
            if (type == null && value instanceof PrismObject) {
                variablesMap.put(e.getKey(), value, ((PrismObject<?>) value).getDefinition());
            } else {
                @SuppressWarnings("unchecked")
                Class<Object> declared = (Class<Object>) (type != null ? type
                        : value != null ? value.getClass() : Object.class);
                variablesMap.put(e.getKey(), value, declared);
            }
        }

        Expression<PrismPropertyValue<Object>, PrismPropertyDefinition<Object>> evaluated =
                expressionFactory.makeExpression(expression, output, null, SCRIPT_DESCRIPTION, task, result);
        ExpressionEvaluationContext context =
                new ExpressionEvaluationContext(null, variablesMap, SCRIPT_DESCRIPTION, task);
        context.setExpressionFactory(expressionFactory);
        ExpressionEnvironmentThreadLocalHolder.pushExpressionEnvironment(
                new ExpressionEnvironmentBuilder<>().currentResult(result).currentTask(task).build());
        PrismValueDeltaSetTriple<PrismPropertyValue<Object>> triple;
        try {
            triple = evaluated.evaluate(context, result);
        } finally {
            ExpressionEnvironmentThreadLocalHolder.popExpressionEnvironment();
        }
        List<Object> out = new ArrayList<>();
        if (triple != null) {
            for (PrismValue value : triple.getNonNegativeValues()) {
                out.add(value != null ? value.getRealValue() : null);
            }
        }
        return out;
    }

    @Override
    public BulkActionOutput executeBulkAction(String xml, Task task, OperationResult result) throws Exception {
        Object parsed = harness.bean(PrismContext.class).parserFor(xml).xml().parseRealValue();
        if (!(parsed instanceof ExecuteScriptType) && !(parsed instanceof ScriptingExpressionType)) {
            throw new IllegalArgumentException("Not a bulk action: " + parsed);
        }
        ExecuteScriptType command = ScriptingBeansUtil.asExecuteScriptCommand(parsed);
        // as the bulk action page does
        BulkActionExecutionResult executed = harness.bean(BulkActionsService.class).executeBulkAction(
                ExecuteScriptConfigItem.of(command, ConfigurationItemOrigin.user()), VariablesMap.emptyMap(),
                BulkActionExecutionOptions.create(), task, result);
        List<Object> data = new ArrayList<>();
        for (PipelineItem item : executed.getDataOutput()) {
            data.add(item.getValue() != null ? item.getValue().getRealValue() : null);
        }
        return new BulkActionOutput(executed.getConsoleOutput(), data);
    }

    @Override
    public String initialObjectsPattern() {
        // InitialDataImport (4.8): the initial objects of system-init, in subdirectories by type
        return "classpath*:/initial-objects/**/*.xml";
    }

    @Override
    public boolean initialObjectsSortedByNumber() {
        return false;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public String importInitialObject(PrismObject<? extends ObjectType> object, boolean overwrite, Task task,
            OperationResult result) throws Exception {
        // InitialDataImport.importObject (4.8): types the repository does not support are skipped, then an add with
        // isImport and overwrite
        ModelService model = harness.bean(ModelService.class);
        if (!model.isSupportedByRepository(object.getCompileTimeClass())) {
            return "a type the repository does not support";
        }
        model.executeChanges((java.util.Collection) java.util.Collections.singletonList(object.createAddDelta()),
                com.evolveum.midpoint.model.api.ModelExecuteOptions.create().setIsImport().overwrite(overwrite),
                task, result);
        return null;
    }

    @Override
    public String postInitialChannel() {
        return SchemaConstants.CHANNEL_INIT_URI;
    }

    @Override
    public void importPostInitialFile(java.io.File file, Task task, OperationResult result) throws Exception {
        // PostInitialDataImport.importObject: overwrite, full processing
        ImportOptionsType options = new ImportOptionsType();
        options.setOverwrite(true);
        ModelExecuteOptionsType modelOptions = new ModelExecuteOptionsType();
        modelOptions.setRaw(false);
        options.setModelExecutionOptions(modelOptions);
        harness.bean(ModelService.class).importObjectsFromFile(file, options, task, result);
    }

    @Override
    public String executePostInitialScript(Object bulkAction, Task task, OperationResult result) throws Exception {
        // PostInitialDataImport.executeScript
        ExecuteScriptType parsed = ScriptingBeansUtil.asExecuteScriptCommand(bulkAction);
        BulkActionExecutionResult executed = harness.bean(BulkActionsService.class).executeBulkAction(
                ExecuteScriptConfigItem.of(parsed, ConfigurationItemOrigin.external(SchemaConstants.CHANNEL_INIT_URI)),
                VariablesMap.emptyMap(), BulkActionExecutionOptions.create().withExecutionPhase(), task, result);
        return executed.getConsoleOutput();
    }
}
