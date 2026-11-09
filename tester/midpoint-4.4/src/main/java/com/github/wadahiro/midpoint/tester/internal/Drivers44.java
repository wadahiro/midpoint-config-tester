package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import javax.xml.bind.JAXBElement;

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
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.PipelineItem;
import com.evolveum.midpoint.model.api.ScriptExecutionResult;
import com.evolveum.midpoint.model.api.ScriptingService;
import com.evolveum.midpoint.model.api.validator.StringLimitationResult;
import com.evolveum.midpoint.model.common.expression.ExpressionEnvironment;
import com.evolveum.midpoint.model.common.expression.ModelExpressionThreadLocalHolder;
import com.evolveum.midpoint.prism.MutablePrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismPropertyValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.delta.PrismValueDeltaSetTriple;
import com.evolveum.midpoint.repo.common.expression.Expression;
import com.evolveum.midpoint.repo.common.expression.ExpressionEvaluationContext;
import com.evolveum.midpoint.repo.common.expression.ExpressionFactory;
import com.evolveum.midpoint.schema.SchemaConstantsGenerated;
import com.evolveum.midpoint.schema.constants.ExpressionConstants;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
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

/** {@link LineDrivers} of 4.4. */
final class Drivers44 implements LineDrivers {

    private static final String SCRIPT_DESCRIPTION = "tester script";
    /** A generated value's length when the policy does not say. */
    private static final int DEFAULT_LENGTH = 10;

    private final Harness44 harness;

    Drivers44(Harness44 harness) {
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
    public List<Mail> mails() {
        return harness.smtpServer().mails();
    }

    @Override
    public void clearMails() {
        harness.smtpServer().clear();
    }

    @Override
    public int smtpPort() {
        return harness.smtpServer().port();
    }

    @Override
    public boolean isAuthorized(String actionUrl, PrismObject<? extends ObjectType> object, Task task,
            OperationResult result) throws Exception {
        return harness.bean(SecurityEnforcer.class).isAuthorized(actionUrl, null,
                AuthorizationParameters.Builder.buildObject(object), null, task, result);
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
        com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType probe = new com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType(
                harness.bean(PrismContext.class));
        probe.getAction().add(read);
        probe.getObject().add(selector.clone());
        com.evolveum.midpoint.security.api.MidPointPrincipal principal =
                com.evolveum.midpoint.security.api.SecurityUtil.getPrincipal();
        // no principal argument before 4.8: the logged-in principal's own list, restored after
        List<com.evolveum.midpoint.security.api.Authorization> saved = new ArrayList<>(principal.getAuthorities());
        principal.getAuthorities().clear();
        principal.getAuthorities().add(new com.evolveum.midpoint.security.api.Authorization(probe));
        try {
            return harness.bean(SecurityEnforcer.class).preProcessObjectFilter(new String[] {read}, null, (Class) type,
                    null, null, null, List.of(), task, result);
        } finally {
            principal.getAuthorities().clear();
            principal.getAuthorities().addAll(saved);
        }
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
        AssignmentType assignment = new AssignmentType(prismContext);
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
        ObjectSecurityConstraints constraints = enforcer.compileSecurityConstraints(focus, true, null, task, result);
        AccessDecision itemDecision = enforcer.determineSubitemDecision(constraints, delta, focus, modify, AuthorizationPhaseType.REQUEST,
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
                        .orderConstraints(List.of(new OrderConstraintsType(prismContext).order(0)))
                        .fullInformationAvailable(true)
                        .build();
                String action = (assign ? ModelAuthorizationAction.ASSIGN : ModelAuthorizationAction.UNASSIGN)
                        .getUrl();
                request = enforcer.isAuthorized(action, AuthorizationPhaseType.REQUEST, params, null, task,
                        result)
                        || harness.bean(RelationRegistry.class).isDelegation(effectiveRelation)
                        && enforcer.isAuthorized(ModelAuthorizationAction.DELEGATE.getUrl(),
                                AuthorizationPhaseType.REQUEST, params, null, task, result);
            }
        }
        return request && enforcer.isAuthorized(modify, AuthorizationPhaseType.EXECUTION,
                AuthorizationParameters.Builder.buildObjectDelta(focusObject, delta, true), null, task, result);
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
            // LeftMenuPanel.addCollectionsMenuItems (the default view too)
            boolean inMenu = view.getContainerType() != null
                    && MENU_TYPES.contains(view.getContainerType().getLocalPart())
                    && view.getCollection() != null && visibility != UserInterfaceElementVisibilityType.HIDDEN
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
        PrismContext prismContext = harness.bean(PrismContext.class);
        ExpressionFactory expressionFactory = harness.bean(ExpressionFactory.class);
        ScriptExpressionEvaluatorType script = new ScriptExpressionEvaluatorType().code(code);
        script.setLanguage(languageUri);
        ExpressionType expression = new ExpressionType();
        expression.getExpressionEvaluator()
                .add(new JAXBElement<>(SchemaConstantsGenerated.C_SCRIPT, ScriptExpressionEvaluatorType.class, script));
        MutablePrismPropertyDefinition<Object> output = prismContext.definitionFactory()
                .createPropertyDefinition(ExpressionConstants.OUTPUT_ELEMENT_NAME,
                        returnType != null ? returnType : DOMUtil.XSD_STRING);
        output.setMaxOccurs(-1);
        output.freeze();

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
        ModelExpressionThreadLocalHolder.pushExpressionEnvironment(new ExpressionEnvironment<>(task, result));
        PrismValueDeltaSetTriple<PrismPropertyValue<Object>> triple;
        try {
            triple = evaluated.evaluate(context, result);
        } finally {
            ModelExpressionThreadLocalHolder.popExpressionEnvironment();
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
        // as the bulk action page does
        ScriptingService scripting = harness.bean(ScriptingService.class);
        ScriptExecutionResult executed = parsed instanceof ExecuteScriptType
                ? scripting.evaluateExpression((ExecuteScriptType) parsed, VariablesMap.emptyMap(), false, task, result)
                : scripting.evaluateExpression((ScriptingExpressionType) parsed, task, result);
        List<Object> data = new ArrayList<>();
        for (PipelineItem item : executed.getDataOutput()) {
            data.add(item.getValue() != null ? item.getValue().getRealValue() : null);
        }
        return new BulkActionOutput(executed.getConsoleOutput(), data);
    }

    @Override
    public String initialObjectsPattern() {
        // InitialDataImport (4.4): classpath*:/initial-objects/*, here as unpacked from the GUI module
        return "classpath*:tester/initial-objects/*";
    }

    @Override
    public boolean initialObjectsSortedByNumber() {
        return false;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public String importInitialObject(PrismObject<? extends ObjectType> object, boolean overwrite, Task task,
            OperationResult result) throws Exception {
        // InitialDataImport.importObject (4.4): an add with isImport and overwrite
        harness.bean(ModelService.class).executeChanges(
                (java.util.Collection) java.util.Collections.singletonList(object.createAddDelta()),
                com.evolveum.midpoint.model.api.ModelExecuteOptions.create(harness.bean(PrismContext.class))
                        .setIsImport().overwrite(overwrite),
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
        ScriptingService scripting = harness.bean(ScriptingService.class);
        ScriptExecutionResult executed = bulkAction instanceof ExecuteScriptType
                ? scripting.evaluateExpression((ExecuteScriptType) bulkAction, VariablesMap.emptyMap(), false, task,
                        result)
                : scripting.evaluateExpression((ScriptingExpressionType) bulkAction, task, result);
        return executed.getConsoleOutput();
    }
}
