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
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.PipelineItem;
import com.evolveum.midpoint.model.api.ScriptExecutionResult;
import com.evolveum.midpoint.model.api.ScriptingService;
import com.evolveum.midpoint.common.LocalizationService;
import com.evolveum.midpoint.model.common.stringpolicy.UserValuePolicyOriginResolver;
import com.evolveum.midpoint.model.common.stringpolicy.ValuePolicyProcessor;
import com.evolveum.midpoint.model.impl.expr.ExpressionEnvironment;
import com.evolveum.midpoint.model.impl.expr.ModelExpressionThreadLocalHolder;
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
import com.evolveum.midpoint.repo.common.expression.ExpressionVariables;
import com.evolveum.midpoint.repo.common.ObjectResolver;
import com.evolveum.midpoint.util.LocalizableMessage;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import java.util.Locale;
import com.evolveum.midpoint.schema.SchemaConstantsGenerated;
import com.evolveum.midpoint.schema.constants.ExpressionConstants;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.security.enforcer.api.AuthorizationParameters;
import com.evolveum.midpoint.security.enforcer.api.SecurityEnforcer;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ImportOptionsType;
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

/** {@link LineDrivers} of 4.0. */
final class Drivers40 implements LineDrivers {

    private static final String SCRIPT_DESCRIPTION = "tester script";
    /** A generated value's length when the policy does not say. */
    private static final int DEFAULT_LENGTH = 10;

    private final Harness40 harness;

    Drivers40(Harness40 harness) {
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
        // as the GUI: the collection's definition read and compiled privileged (the filter's expressions still see the
        // logged-in user), its objects searched as the user
        com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView view = privileged(() -> {
            PrismObject<com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionType> collection =
                    harness.bean(com.evolveum.midpoint.repo.api.RepositoryService.class).getObject(
                            com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionType.class,
                            collectionOid, null, result);
            return harness.bean(ModelInteractionService.class).compileObjectCollectionView(collection, null, task,
                    result);
        });
        Class<ObjectType> type = view.getTargetClass();
        return new ArrayList<>(harness.bean(com.evolveum.midpoint.model.api.ModelService.class).searchObjects(type,
                harness.bean(PrismContext.class).queryFactory().createQuery(view.getFilter()), null, task, result));
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
        return audit40(con -> {
            try (java.sql.Statement st = con.createStatement();
                    java.sql.ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM m_audit_event")) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    /**
     * The rows of {@code m_audit_event} after the position, read with JDBC: 4.0's audit service reads records back
     * without their target and initiator. The numbers are midPoint's enums ({@code RAuditEventType}...), as it wrote
     * them.
     */
    @Override
    public List<com.github.wadahiro.midpoint.tester.AuditRecord> auditRecordsAfter(long position) {
        PrismContext prismContext = harness.bean(PrismContext.class);
        return audit40(con -> {
            List<com.github.wadahiro.midpoint.tester.AuditRecord> out = new ArrayList<>();
            try (java.sql.PreparedStatement st = con.prepareStatement(
                    "SELECT * FROM m_audit_event WHERE id > ? ORDER BY id")) {
                st.setLong(1, position);
                try (java.sql.ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        java.sql.Timestamp timestamp = rs.getTimestamp("timestampValue");
                        out.add(new com.github.wadahiro.midpoint.tester.AuditRecord(
                                enumName(rs, "eventType", i -> com.evolveum.midpoint.repo.sql.data.audit.RAuditEventType
                                        .values()[i].getType()),
                                enumName(rs, "eventStage", i -> com.evolveum.midpoint.repo.sql.data.audit
                                        .RAuditEventStage.values()[i].getStage()),
                                enumName(rs, "outcome", i -> com.evolveum.midpoint.repo.sql.data.common.enums
                                        .ROperationResultStatus.values()[i].getStatus()),
                                timestamp != null ? timestamp.toInstant() : null,
                                rs.getString("targetOid"), rs.getString("targetName"), objectType(rs, "targetType"),
                                rs.getString("initiatorOid"), rs.getString("initiatorName"), rs.getString("channel"),
                                rs.getString("taskOID"), rs.getString("message"),
                                deltas40(con, rs.getLong("id"), prismContext)));
                    }
                }
            }
            return out;
        });
    }

    private static List<String> deltas40(java.sql.Connection con, long recordId, PrismContext prismContext)
            throws java.sql.SQLException {
        List<String> out = new ArrayList<>();
        try (java.sql.PreparedStatement st = con.prepareStatement("SELECT * FROM m_audit_delta WHERE record_id = ?")) {
            st.setLong(1, recordId);
            try (java.sql.ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    com.evolveum.midpoint.schema.ObjectDeltaOperation<?> operation;
                    try {
                        operation = com.evolveum.midpoint.repo.sql.data.audit.RObjectDeltaOperation.fromRepo(rs,
                                prismContext, false);
                    } catch (com.evolveum.midpoint.repo.sql.util.DtoTranslationException e) {
                        throw new IllegalStateException("Cannot read the audit delta of record " + recordId, e);
                    }
                    ObjectDelta<?> delta = operation != null ? operation.getObjectDelta() : null;
                    if (delta != null) {
                        out.add(AuditRecords.delta(delta.getChangeType(), delta.getObjectTypeClass().getSimpleName(),
                                delta.getOid(), operation.getExecutionResult() != null
                                        ? operation.getExecutionResult().getStatus() : null));
                    }
                }
            }
        }
        return out;
    }

    private static String enumName(java.sql.ResultSet rs, String column,
            java.util.function.IntFunction<Enum<?>> value) throws java.sql.SQLException {
        int ordinal = rs.getInt(column);
        return rs.wasNull() ? null : value.apply(ordinal).name();
    }

    private static String objectType(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int ordinal = rs.getInt(column);
        return rs.wasNull() ? null : com.evolveum.midpoint.repo.sql.data.common.other.RObjectType.values()[ordinal]
                .getJaxbClass().getSimpleName();
    }

    private interface Jdbc<T> {
        T on(java.sql.Connection con) throws java.sql.SQLException;
    }

    private <T> T audit40(Jdbc<T> work) {
        try (java.sql.Connection con = harness.bean(javax.sql.DataSource.class).getConnection()) {
            return work.on(con);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot read the audit log", e);
        }
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
                AuthorizationParameters.Builder.buildObject(object), null, task, result);
    }

    @Override
    public boolean decideAccess(java.util.Collection<String> actionUrls, Task task, OperationResult result)
            throws Exception {
        return harness.bean(SecurityEnforcer.class).decideAccess(
                com.evolveum.midpoint.security.api.SecurityUtil.getPrincipal(), new ArrayList<>(actionUrls), task, result)
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
        ObjectSecurityConstraints constraints = enforcer.compileSecurityConstraints(focus, null, task, result);
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
                AuthorizationParameters.Builder.buildObjectDelta(focusObject, delta), null, task, result);
    }

    @Override
    public com.github.wadahiro.midpoint.tester.GuiProfile guiProfile(Task task, OperationResult result)
            throws Exception {
        com.evolveum.midpoint.model.api.authentication.CompiledUserProfile compiled =
                ((com.evolveum.midpoint.model.api.authentication.MidPointUserProfilePrincipal)
                        com.evolveum.midpoint.security.api.SecurityUtil.getPrincipal()).getCompiledUserProfile();
        com.github.wadahiro.midpoint.tester.GuiProfile profile = new com.github.wadahiro.midpoint.tester.GuiProfile()
                .objectDetails(compiled.getObjectDetails())
                .defaultTimezone(compiled.getDefaultTimezone())
                .preferredDataLanguage(compiled.getPreferredDataLanguage())
                .compiled(compiled);
        for (com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView view
                : compiled.getObjectCollectionViews()) {
            profile.views().add(new com.github.wadahiro.midpoint.tester.GuiProfile.CollectionView(
                    view.getObjectType(), view.getViewIdentifier(), label(view.getDisplay()),
                    view.getDisplayOrder(), null, false, null, inMenu(view, task)));
        }
        profile.additionalMenuLinks().addAll(compiled.getAdditionalMenuLink());
        profile.userDashboardLinks().addAll(compiled.getUserDashboardLink());
        profile.features().addAll(compiled.getFeatures());
        return profile;
    }

    /** The types whose side menu section lists their views (PageBase: users, roles, services...; not orgs, tasks). */
    private static final List<String> MENU_TYPES = List.of("UserType", "RoleType", "ServiceType", "ResourceType", "CaseType", "ArchetypeType");

    /**
     * PageBase.addCollectionsMenuItems, under a type's section: a collection whose reference the user gets (no fetch) and is an archetype or
     * an object collection.
     */
    private boolean inMenu(com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView view,
            Task task) {
        com.evolveum.midpoint.xml.ns._public.common.common_3.CollectionRefSpecificationType collection =
                view.getCollection();
        ObjectReferenceType ref = collection == null ? null : collection.getCollectionRef();
        if (ref == null || view.getObjectType() == null
                || !MENU_TYPES.contains(view.getObjectType().getLocalPart())) {
            return false;
        }
        Class<? extends ObjectType> type = ref.getType() == null ? ObjectType.class
                : com.evolveum.midpoint.schema.constants.ObjectTypes.getObjectTypeFromTypeQName(ref.getType())
                        .getClassDefinition();
        ObjectType object;
        try {
            object = harness.bean(ModelService.class).getObject(type, ref.getOid(),
                    com.evolveum.midpoint.schema.SelectorOptions.createCollection(
                            com.evolveum.midpoint.schema.GetOperationOptions.createNoFetch()),
                    task, new OperationResult("resolve the collection of a view")).asObjectable();
        } catch (Exception e) {
            // the GUI's resolveReferenceNoFetch gives null
            return false;
        }
        return object instanceof com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType
                || object instanceof com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionType;
    }

    private static String label(com.evolveum.midpoint.xml.ns._public.common.common_3.DisplayType display) {
        return display == null || display.getLabel() == null ? null : display.getLabel().getOrig();
    }

    /**
     * 4.0 has no limitation results ({@code StringLimitationResult}, 4.3+): the value policy processor gives the
     * messages of what the value does not meet (a {@code checkExpression}'s {@code failureMessage}), each one a
     * failed limitation here; a valid value has none.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Boolean> validateValue(String value, ValuePolicyType policy,
            PrismObject<? extends ObjectType> object, Task task, OperationResult result) throws Exception {
        UserValuePolicyOriginResolver origin = object != null && object.asObjectable() instanceof UserType
                ? new UserValuePolicyOriginResolver((PrismObject<UserType>) object, harness.bean(ObjectResolver.class))
                : null;
        List<LocalizableMessage> messages = new ArrayList<>();
        harness.bean(ValuePolicyProcessor.class).validateValue(value, policy, origin, messages,
                "tester value validation", task, result);
        LocalizationService localization = harness.bean(LocalizationService.class);
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (LocalizableMessage message : messages) {
            out.put(localization.translate(message, Locale.ENGLISH), false);
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

        ExpressionVariables variablesMap = new ExpressionVariables();
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
        // InitialDataImport.getInitialImportObjects (4.0): the files of initial-objects, no subdirectories
        return "classpath*:tester/initial-objects/*";
    }

    @Override
    public boolean initialObjectsSortedByNumber() {
        return true;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public String importInitialObject(PrismObject<? extends ObjectType> object, boolean overwrite, Task task,
            OperationResult result) throws Exception {
        // InitialDataImport.importObject (4.0): reports get their definition applied, then an add with isImport. 4.0's
        // reports are Jasper reports: their libraries are not available (dead repositories), midPoint's report hook
        // cannot store them
        if (com.evolveum.midpoint.xml.ns._public.common.common_3.ReportType.class.equals(object.getCompileTimeClass())) {
            return "a Jasper report: no Jasper libraries in the tester for 4.0";
        }
        com.evolveum.midpoint.model.api.ModelExecuteOptions options =
                com.evolveum.midpoint.model.api.ModelExecuteOptions.createIsImport();
        if (overwrite) {
            options.setOverwrite(true);
        }
        harness.bean(ModelService.class).executeChanges(
                (java.util.Collection) java.util.Collections.singletonList(object.createAddDelta()), options, task,
                result);
        return null;
    }

    @Override
    public String postInitialChannel() {
        return SchemaConstants.CHANNEL_GUI_INIT_URI;
    }

    @Override
    public void importPostInitialFile(java.io.File file, Task task, OperationResult result) throws Exception {
        // PostInitialDataImport.importObject (4.0): no model execution options, so ObjectImporter imports raw
        ImportOptionsType options = new ImportOptionsType();
        options.setOverwrite(true);
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
