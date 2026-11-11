package com.github.wadahiro.midpoint.tester.internal;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ConnectorType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ModelExecuteOptionsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SynchronizationSituationType;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.util.QNameUtil;
import com.github.wadahiro.midpoint.tester.Shadow;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ValuePolicyType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.github.wadahiro.midpoint.tester.Access;
import com.github.wadahiro.midpoint.tester.Answer;
import com.github.wadahiro.midpoint.tester.BulkActionOutput;
import com.github.wadahiro.midpoint.tester.Change;
import com.github.wadahiro.midpoint.tester.Approvals;
import com.github.wadahiro.midpoint.tester.Certification;
import com.github.wadahiro.midpoint.tester.CsvFile;
import com.github.wadahiro.midpoint.tester.Dashboards;
import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.LdapDirectory;
import com.github.wadahiro.midpoint.tester.MappedFiles;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.Mail;
import com.github.wadahiro.midpoint.tester.Notification;
import com.github.wadahiro.midpoint.tester.OperationFailed;
import com.github.wadahiro.midpoint.tester.ScriptCall;
import com.github.wadahiro.midpoint.tester.ValueCheck;
import com.github.wadahiro.midpoint.tester.Reports;
import com.github.wadahiro.midpoint.tester.ResourceOptions;
import com.github.wadahiro.midpoint.tester.Run;
import com.github.wadahiro.midpoint.tester.TargetSystem;

/** {@link MidPoint} on top of a line's {@link Harness}; only API that is the same on every line is used here. */
final class MidPointImpl implements MidPoint {

    private static final QName ICFS_NAME = new QName(Xml.NS_ICFS, "name");
    private static final Pattern OID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /**
     * An OID that is not a UUID, which midPoint before 4.4's native repository accepts (e.g. {@code role0000-...}):
     * at most 36 letters, digits and dashes, with a dash.
     */
    private static final Pattern OID_SHAPED = Pattern.compile("(?=[\\w-]*-)[\\w-]{1,36}");

    private final TesterRuntime runtime;
    final Harness harness;
    private final AtomicInteger xmlCount = new AtomicInteger();
    private final AtomicInteger mailCount = new AtomicInteger();
    private LineDrivers drivers;
    private volatile String step;
    /** Whether a partial error fails a call ({@link #allowingPartialErrors}). */
    private volatile boolean partialErrorsAllowed;

    /** What an operation returns besides its evaluations. */
    static final class Outcome {
        static final Outcome NONE = new Outcome(null, null);
        final String oid;
        final ModelContext<?> modelContext;

        Outcome(String oid, ModelContext<?> modelContext) {
            this.oid = oid;
            this.modelContext = modelContext;
        }
    }

    /** One driver call: gets a fresh task and result. */
    interface Operation {
        Outcome run(Task task, OperationResult result) throws Exception;
    }

    /** A lookup outside of a driver call (nothing recorded). */
    interface Query<T> {
        T run(Task task, OperationResult result) throws Exception;
    }

    MidPointImpl(TesterRuntime runtime, Harness harness) {
        this.runtime = runtime;
        this.harness = harness;
    }

    @Override
    public void step(String name) {
        step = name;
    }

    @Override
    public String line() {
        return LineSupport.LINE;
    }

    @Override
    public PrismContext prismContext() {
        return harness.bean(PrismContext.class);
    }

    // ---- configuration ---------------------------------------------------------------------------------------

    @Override
    public Run importObject(File file) {
        ConfigIndex.get().register(file.toPath(), name(file));
        return importXml(read(file), file.getName(), false);
    }

    @Override
    public Run importObjects(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
        Arrays.sort(files);
        List<String> xmls = new ArrayList<>();
        for (File file : files) {
            ConfigIndex.get().register(file.toPath(), name(file));
            xmls.add(read(file));
        }
        return run("import " + directory.getName(), (task, result) -> {
            for (String xml : xmls) {
                importOperation(xml, false).run(task, result);
            }
            return Outcome.NONE;
        });
    }

    @Override
    public Run importObjectXml(String xml) {
        registerText(xml);
        return importXml(xml, objectsIn(xml), false);
    }

    @Override
    public void importObjectRaw(File file) {
        ConfigIndex.get().register(file.toPath(), name(file));
        importXml(read(file), file.getName(), true);
    }

    private Run importXml(String xml, String what, boolean raw) {
        return run("import " + what, importOperation(xml, raw));
    }

    private Operation importOperation(String xml, boolean raw) {
        return (task, result) -> {
            var options = MiscSchemaUtil.getDefaultImportOptions();
            options.setOverwrite(true);
            options.setKeepOid(true);
            // as PageImportObject: "Full processing" (checked by default) sets raw=false, unchecked leaves the model
            // options out, and ObjectImporter.addObject then adds the object raw
            if (!raw) {
                ModelExecuteOptionsType modelOptions = new ModelExecuteOptionsType();
                modelOptions.setRaw(false);
                options.setModelExecutionOptions(modelOptions);
            }
            String redirected = redirectMail(xml);
            // a runnable task starts once the import has ended, not while the import still writes it (4.0 lost the
            // work state the first run had just created, and suspended the task)
            org.quartz.Scheduler scheduler = harness.quartzScheduler();
            TaskSchedules.hold(scheduler);
            try {
                model().importObjectsFromStream(new ByteArrayInputStream(redirected.getBytes(StandardCharsets.UTF_8)),
                        PrismContext.LANG_XML, options, task, result);
            } finally {
                TaskSchedules.release(scheduler);
            }
            return Outcome.NONE;
        };
    }

    /**
     * {@code ModelService.executeChanges}; with Quartz held until it returns when a delta adds or changes a task, as
     * an import does: a task made runnable starts once the change has ended, not while midPoint still writes it.
     */
    private void executeChanges(Collection<ObjectDelta<? extends ObjectType>> deltas, ModelExecuteOptions options,
            Task task, OperationResult result) throws Exception {
        org.quartz.Scheduler scheduler = deltas.stream().anyMatch(MidPointImpl::isTaskChange)
                ? harness.quartzScheduler() : null;
        if (scheduler != null) {
            TaskSchedules.hold(scheduler);
        }
        try {
            model().executeChanges(new ArrayList<>(deltas), options, task, result);
        } finally {
            if (scheduler != null) {
                TaskSchedules.release(scheduler);
            }
        }
    }

    /** Whether the delta adds, modifies or deletes a task. */
    static boolean isTaskChange(ObjectDelta<? extends ObjectType> delta) {
        Class<?> type = delta.getObjectTypeClass();
        if ((type == null || type == ObjectType.class) && delta.getObjectToAdd() != null) {
            type = delta.getObjectToAdd().getCompileTimeClass();
        }
        return type != null && TaskType.class.isAssignableFrom(type);
    }

    @Override
    public TargetSystem importResource(File resourceFile) {
        return importResource(resourceFile, ResourceOptions.defaults());
    }

    @Override
    public TargetSystem importResource(File resourceFile, ResourceOptions options) {
        Retargeted resource = retarget(read(resourceFile), resourceFile.getName(), options);
        // locations refer to the file as written, not to the transformed copy
        ConfigIndex.get().register(resourceFile.toPath(), name(resourceFile));
        importXml(resource.xml, resourceFile.getName(), false);
        query("test resource " + resourceFile.getName(), (task, result) -> {
            harness.checkResource(resource.oid, task, result);
            return null;
        });
        return resource.target;
    }

    /** A resource definition moved to the tester's target system ({@link #retarget}). */
    static final class Retargeted {
        final String oid;
        final String xml;
        final ManagedTarget target;

        Retargeted(String oid, String xml, ManagedTarget target) {
            this.oid = oid;
            this.xml = xml;
            this.target = target;
        }
    }

    /**
     * The resource definition (XML of a {@code <resource>}) with its connector replaced by the mirror, or kept on its
     * real CSV or LDAP connector pointed at what the tester provides ({@link ResourceOptions#realConnector()}); the
     * target system is registered with the runtime (or the one already registered for the OID is reused). The
     * definition as rewritten is written to {@code <workDir>/tester-resources/<fileName>}. Nothing is imported.
     */
    Retargeted retarget(String resourceXml, String fileName, ResourceOptions options) {
        if (options.isRealConnector()) {
            return retargetReal(resourceXml, fileName, options);
        }
        MirrorResourceXml resource;
        try {
            resource = MirrorResourceXml.transform(resourceXml, mirrorConnectorOid(), options);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fileName + ": " + e.getMessage(), e);
        }
        runtime.writeWorkFile("tester-resources/" + fileName, resource.xml);
        ManagedTarget existing = runtime.findTarget(resource.oid);
        MirrorTargetImpl target;
        if (existing instanceof MirrorTargetImpl) {
            target = (MirrorTargetImpl) existing;
            target.update(resource);
        } else {
            if (existing != null) {
                existing.restore(null);
            }
            target = runtime.registerTarget(MirrorTargetImpl.create(resource));
        }
        return new Retargeted(resource.oid, resource.xml, target);
    }

    /** The resource on its real CSV or LDAP connector, with the file or server the tester provides. */
    private Retargeted retargetReal(String resourceXml, String fileName, ResourceOptions options) {
        RealResourceXml resource = RealResourceXml.parse(resourceXml, fileName);
        ManagedTarget existing = runtime.findTarget(resource.oid);
        ManagedTarget target;
        String xml;
        if (resource.kind == RealResourceXml.Kind.CSV) {
            CsvTargetImpl csv;
            if (existing instanceof CsvTargetImpl) {
                csv = (CsvTargetImpl) existing;
            } else {
                Path file = runtime.workDir().resolve("tester-csv").resolve(resource.oid + ".csv");
                csv = new CsvTargetImpl(resource, file);
                csv.create(CsvTargetImpl.header(resource));
            }
            File tmp = csv.tmpFolder().toFile();
            tmp.mkdirs();
            xml = resource.transform(this::connectorOid, Map.of("filePath", csv.path().toString(),
                    "tmpFolder", tmp.getPath()), java.util.Set.of(), null);
            target = csv;
        } else {
            LdapTargetImpl ldap = existing instanceof LdapTargetImpl ? (LdapTargetImpl) existing
                    : new LdapTargetImpl(resource, options.hasReferentialIntegrity());
            xml = resource.transform(this::connectorOid, ldap.overrides(), java.util.Set.of("servers"),
                    LdapTargetImpl.BIND_PASSWORD);
            target = ldap;
        }
        if (existing != null && existing != target) {
            existing.restore(null);
        }
        if (existing != target) {
            runtime.registerTarget(target);
        }
        runtime.writeWorkFile("tester-resources/" + fileName, xml);
        return new Retargeted(resource.oid, xml, target);
    }

    @Override
    public CsvFile csv(String resource) {
        TargetSystem target = target(resource);
        if (!(target instanceof CsvFile)) {
            throw new IllegalArgumentException("Resource " + resource + " is not on the real CSV connector"
                    + " (ResourceOptions.realConnector())");
        }
        return (CsvFile) target;
    }

    @Override
    public LdapDirectory ldap(String resource) {
        TargetSystem target = target(resource);
        if (!(target instanceof LdapDirectory)) {
            throw new IllegalArgumentException("Resource " + resource + " is not on the real LDAP connector"
                    + " (ResourceOptions.realConnector())");
        }
        return (LdapDirectory) target;
    }

    @Override
    public TargetSystem target(String resource) {
        return runtime.target(resourceOid(resource));
    }

    // ---- drivers ---------------------------------------------------------------------------------------------

    @Override
    public Run addObject(ObjectType object) {
        @SuppressWarnings("unchecked")
        PrismObject<ObjectType> prismObject = (PrismObject<ObjectType>) object.asPrismObject();
        return add(prismObject);
    }

    @Override
    public Run addObjectXml(String xml) {
        PrismObject<ObjectType> object;
        try {
            object = prismContext().parserFor(redirectMail(xml)).language(PrismContext.LANG_XML).parse();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse object: " + e.getMessage(), e);
        }
        registerText(xml);
        return add(object);
    }

    private Run add(PrismObject<ObjectType> object) {
        if (object.getOid() == null) {
            object.setOid(UUID.randomUUID().toString());
        }
        String oid = object.getOid();
        return run(describe(List.<ObjectDelta<? extends ObjectType>>of(object.createAddDelta())), (task, result) -> {
            executeChanges(List.<ObjectDelta<? extends ObjectType>>of(object.createAddDelta()), null, task, result);
            return new Outcome(oid, null);
        });
    }

    @Override
    public Run execute(ObjectDelta<? extends ObjectType> delta) {
        return execute(List.<ObjectDelta<? extends ObjectType>>of(delta));
    }

    @Override
    public Run execute(Collection<ObjectDelta<? extends ObjectType>> deltas) {
        return run("execute " + describe(deltas), (task, result) -> {
            executeChanges(deltas, null, task, result);
            return new Outcome(deltas.size() == 1 ? deltas.iterator().next().getOid() : null, null);
        });
    }

    @Override
    public <O extends ObjectType> Run modify(Class<O> type, String nameOrOid, ItemPath path, Object... values) {
        String oid = existing(type, nameOrOid).getOid();
        ItemDefinition<?> definition = prismContext().getSchemaRegistry()
                .findObjectDefinitionByCompileTimeClass(type).findItemDefinition(path);
        boolean polyString = definition != null && PolyStringType.COMPLEX_TYPE.equals(definition.getTypeName());
        List<Object> converted = new ArrayList<>();
        for (Object value : values) {
            converted.add(polyString && value instanceof String ? PolyString.fromOrig((String) value) : value);
        }
        ObjectDelta<O> delta;
        try {
            delta = prismContext().deltaFor(type).item(path).replace(converted.toArray()).asObjectDelta(oid);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot build the modification of " + path, e);
        }
        return execute(delta);
    }

    @Override
    public <O extends ObjectType> Run merge(Class<O> type, String left, String right, String mergeConfiguration) {
        String leftOid = existing(type, left).getOid();
        String rightOid = existing(type, right).getOid();
        return run("merge " + nameOf(type, rightOid) + " into " + nameOf(type, leftOid) + " by " + mergeConfiguration,
                (task, result) -> {
                    model().mergeObjects(type, leftOid, rightOid, mergeConfiguration, task, result);
                    return new Outcome(leftOid, null);
                });
    }

    @Override
    public <O extends ObjectType> Run recompute(Class<O> type, String nameOrOid) {
        String oid = existing(type, nameOrOid).getOid();
        return run("recompute " + nameOf(type, oid), (task, result) -> {
            model().recompute(type, oid, null, task, result);
            return new Outcome(oid, null);
        });
    }

    @Override
    public Run importAccount(String resource, String accountName) {
        String resourceOid = resourceOid(resource);
        ManagedTarget target = runtime.findTarget(resourceOid);
        QName namingAttribute = target != null ? target.accountNamingAttribute() : ICFS_NAME;
        return run("import account " + accountName, (task, result) -> {
            harness.importOneAccount(resourceOid, namingAttribute, accountName, task, result);
            return Outcome.NONE;
        });
    }

    @Override
    public Run preview(ObjectDelta<? extends ObjectType> delta) {
        return preview(List.<ObjectDelta<? extends ObjectType>>of(delta));
    }

    @Override
    public Run preview(Collection<ObjectDelta<? extends ObjectType>> deltas) {
        return run("preview " + describe(deltas), (task, result) -> {
            ModelContext<ObjectType> context = harness.bean(ModelInteractionService.class)
                    .previewChanges(new ArrayList<>(deltas), null, task, result);
            return new Outcome(null, context);
        });
    }

    // ---- changes, failures, hooks, notifications, authorizations, value policies, scripts --------------------

    @Override
    public <O extends ObjectType> Change change(Class<O> type, String nameOrOid) {
        return new ChangeImpl<>(this, type, existing(type, nameOrOid).getOid(), nameOrOid);
    }

    @Override
    public <F extends FocusType> Run assign(Class<F> type, String focus, String target) {
        return change(type, focus).assign(target).execute();
    }

    @Override
    public <F extends FocusType> Run assign(Class<F> type, String focus, String target, QName relation) {
        return change(type, focus).assign(target, relation).execute();
    }

    @Override
    public <F extends FocusType> Run unassign(Class<F> type, String focus, String target) {
        return change(type, focus).unassign(target).execute();
    }

    @Override
    public <F extends FocusType> Run unassign(Class<F> type, String focus, String target, QName relation) {
        return change(type, focus).unassign(target, relation).execute();
    }

    @Override
    public <O extends ObjectType> Run modifyAdd(Class<O> type, String nameOrOid, ItemPath path, Object... values) {
        return change(type, nameOrOid).add(path, values).execute();
    }

    @Override
    public <O extends ObjectType> Run modifyDelete(Class<O> type, String nameOrOid, ItemPath path, Object... values) {
        return change(type, nameOrOid).delete(path, values).execute();
    }

    @Override
    public <O extends ObjectType> Run deleteObject(Class<O> type, String nameOrOid) {
        String oid = existing(type, nameOrOid).getOid();
        return execute(prismContext().deltaFactory().object().createDeleteDelta(type, oid));
    }

    @Override
    public Run execute(Collection<ObjectDelta<? extends ObjectType>> deltas, ModelExecuteOptions options) {
        return execute(deltas, options, true);
    }

    @Override
    public Run execute(ObjectDelta<? extends ObjectType> delta, ModelExecuteOptions options) {
        return execute(List.<ObjectDelta<? extends ObjectType>>of(delta), options, true);
    }

    @Override
    public Run tryExecute(ObjectDelta<? extends ObjectType> delta) {
        return execute(List.<ObjectDelta<? extends ObjectType>>of(delta), null, false);
    }

    @Override
    public Run tryExecute(Collection<ObjectDelta<? extends ObjectType>> deltas, ModelExecuteOptions options) {
        return execute(deltas, options, false);
    }

    Run execute(Collection<ObjectDelta<? extends ObjectType>> deltas, ModelExecuteOptions options,
            boolean failOnError) {
        return run("execute " + describe(deltas) + describe(options), (task, result) -> {
            executeChanges(deltas, options, task, result);
            return new Outcome(deltas.size() == 1 ? deltas.iterator().next().getOid() : null, null);
        }, failOnError);
    }

    @Override
    public Run attempt(Supplier<Run> driverCall) {
        try {
            return driverCall.get();
        } catch (OperationFailed e) {
            return e.run();
        }
    }

    @Override
    public List<Notification> notifications() {
        return drivers().notifications();
    }

    @Override
    public List<Mail> mails() {
        return drivers().mails();
    }

    /** The audit log's position when the test method started ({@link #markAuditStart()}). */
    private long auditStart = AuditRecords.START;

    /** midPoint's clock when the test method started: where the schedules of tasks that never ran start from. */
    private long testStart = Long.MIN_VALUE;

    /**
     * Takes the audit log's position ({@link #auditRecords()}) and midPoint's clock ({@link #runDueTasks()}) as the
     * start of the test method.
     */
    void markAuditStart() {
        auditStart = drivers().auditPosition();
        testStart = harness.bean(com.evolveum.midpoint.common.Clock.class).currentTimeMillis();
        dueTasksRun = false;
    }

    /** The test's own positions (audit, schedules) at a checkpoint ({@link #restoreTestMarks}). */
    Object[] testMarks() {
        return new Object[] { auditStart, testStart, dueTasksRun };
    }

    void restoreTestMarks(Object[] marks) {
        auditStart = (Long) marks[0];
        testStart = (Long) marks[1];
        dueTasksRun = (Boolean) marks[2];
    }

    /** Whether {@link #runDueTasks()} has run in this test method ({@link #startTestAt} must come before). */
    private boolean dueTasksRun;

    /**
     * Takes {@code millis} (midPoint's clock, the clock already there) as the start of the test method for the
     * schedules of tasks that never ran: a cron time before it is no run the test missed.
     */
    void startTestAt(long millis) {
        if (dueTasksRun) {
            throw new IllegalStateException("The test's start cannot move after due tasks have run");
        }
        testStart = millis;
    }

    @Override
    public List<com.github.wadahiro.midpoint.tester.AuditRecord> auditRecords() {
        return drivers().auditRecordsAfter(auditStart);
    }

    @Override
    public List<com.github.wadahiro.midpoint.tester.AuditRecord> auditRecords(Runnable during) {
        long before = drivers().auditPosition();
        during.run();
        return drivers().auditRecordsAfter(before);
    }

    @Override
    public List<com.github.wadahiro.midpoint.tester.HttpRequest> httpRequests() {
        return HttpStandIn.get().requests();
    }

    @Override
    public Access actor(String username) {
        if (find(UserType.class, username) == null) {
            throw new AssertionError("No user " + username);
        }
        return new AccessImpl(this, runtime, username);
    }

    @Override
    public ValueCheck validateValue(String valuePolicy, String value) {
        return validateValue(valuePolicy, value, null);
    }

    @Override
    public ValueCheck validateValue(String valuePolicy, String value, ObjectType object) {
        ValuePolicyType policy = existing(ValuePolicyType.class, valuePolicy);
        Map<String, Boolean> limitations = new LinkedHashMap<>();
        Run run = run("validate value by " + valuePolicy, (task, result) -> {
            limitations.putAll(drivers().validateValue(value, policy, prismObject(object), task, result));
            return Outcome.NONE;
        }, false);
        if (run.failure() != null) {
            throw new OperationFailed(run.operation() + " failed: " + run.failure().getMessage(), run, run.failure());
        }
        return new ValueCheck(limitations, run);
    }

    @Override
    public Answer<String> generateValue(String valuePolicy) {
        ValuePolicyType policy = existing(ValuePolicyType.class, valuePolicy);
        String[] value = new String[1];
        Run run = run("generate value by " + valuePolicy, (task, result) -> {
            value[0] = drivers().generateValue(policy, null, task, result);
            return Outcome.NONE;
        });
        return new Answer<>(value[0], run);
    }

    @Override
    public ScriptCall script(String code) {
        return new ScriptCallImpl(this, code);
    }

    @Override
    public Answer<BulkActionOutput> bulkAction(String xml) {
        BulkActionOutput[] output = new BulkActionOutput[1];
        Run run = run("bulk action", (task, result) -> {
            output[0] = drivers().executeBulkAction(xml, task, result);
            return Outcome.NONE;
        });
        return new Answer<>(output[0], run);
    }

    LineDrivers drivers() {
        if (drivers == null) {
            drivers = LineSupport.drivers(harness);
        }
        return drivers;
    }

    private static PrismObject<? extends ObjectType> prismObject(ObjectType object) {
        return object != null ? object.asPrismObject() : null;
    }

    // ---- asynchronous work and services ------------------------------------------------------------------------

    private Approvals approvals;
    private Certification certification;
    private Reports reports;
    private Dashboards dashboards;
    private Time time;

    @Override
    public Run waitForTasks() {
        return run("wait for tasks", (task, result) -> Outcome.NONE);
    }

    /**
     * Runs the calls with a partial error (a task that failed on some of its objects, {@code PARTIAL_ERROR}) not failing
     * a call if {@code allowed}; a fatal error ({@code FATAL_ERROR}, a task that could not run) still fails it.
     */
    void allowingPartialErrors(boolean allowed, Runnable calls) {
        boolean before = partialErrorsAllowed;
        partialErrorsAllowed = allowed;
        try {
            calls.run();
        } finally {
            partialErrorsAllowed = before;
        }
    }

    @Override
    public Run runTask(String taskNameOrOid) {
        TaskType existing = existing(TaskType.class, taskNameOrOid);
        String oid = existing.getOid();
        String name = "run task " + existing.getName().getOrig();
        // a run under way or due now would be a second run in this call (the new one queues behind it)
        awaitBackgroundWork(name);
        return run(name, (task, result) -> {
            runNow(oid, result);
            return new Outcome(oid, null);
        });
    }

    @Override
    public Run resumeTask(String taskNameOrOid) {
        TaskType existing = existing(TaskType.class, taskNameOrOid);
        String oid = existing.getOid();
        return run("resume task " + existing.getName().getOrig(), (task, result) -> {
            harness.bean(com.evolveum.midpoint.model.api.TaskService.class).resumeTask(oid, task, result);
            return Outcome.NONE;
        });
    }

    /**
     * Runs the recurring tasks due by midPoint's clock ({@link Time#runDueTasks()}): each once, in the order of their
     * names, waiting for each run before the next; fails, after them all, if one of them failed: could not run (fatal
     * error) or, unless partial errors are allowed ({@link #allowingPartialErrors}), failed on some of its objects.
     */
    Run runDueTasks() {
        dueTasksRun = true;
        awaitBackgroundWork("run due tasks");
        long now = harness.bean(com.evolveum.midpoint.common.Clock.class).currentTimeMillis();
        List<String> due = new ArrayList<>(TaskSchedules.due(harness.quartzScheduler(), oid -> {
            Long last = lastRunStart(oid);
            return last != null ? Recorder.clockAt(oid, last) : null;
        }, now, testStart));
        // a trigger of a task the repository has suspended (e.g. resumed in an earlier test, the reset restoring the
        // repository, not Quartz) does not run, as in midPoint
        due.removeIf(oid -> !runnable(oid));
        due.sort(java.util.Comparator.comparing(oid -> nameOf(TaskType.class, oid)));
        return run("run due tasks", (task, result) -> {
            List<String> failed = new ArrayList<>();
            for (String oid : due) {
                runNow(oid, result);
                awaitBackgroundWork("run " + nameOf(TaskType.class, oid));
                // as a task's run: a due task that could not run (fatal error) fails the call, one that failed on some
                // of its objects (partial error) too unless allowed; allowed, they stay in the run's result
                OperationResult taskResult = new OperationResult("due task " + nameOf(TaskType.class, oid));
                String errors = taskErrors(oid, taskResult);
                if (errors != null) {
                    result.addSubresult(taskResult);
                    if (resultStatus(oid) == com.evolveum.midpoint.xml.ns._public.common.common_3
                            .OperationResultStatusType.FATAL_ERROR || !partialErrorsAllowed) {
                        failed.add(nameOf(TaskType.class, oid) + ": " + errors);
                    }
                }
            }
            if (!failed.isEmpty()) {
                throw new IllegalStateException("Due tasks failed:\n" + String.join("\n", failed));
            }
            return Outcome.NONE;
        });
    }

    /**
     * Schedules the task now (as the GUI's "Run now") and waits until that run has started: the task's run count
     * ({@link Recorder#taskRuns}) goes up; the caller's driver call then waits for it to end.
     */
    private void runNow(String oid, OperationResult result) throws Exception {
        int runs = Recorder.taskRuns(oid);
        OperationResult runNow = result.createSubresult("run now");
        // an interval task's run now is its standard trigger, re-created to start now: not held (TaskSchedules)
        TaskSchedules.permitRegularRun(oid);
        try {
            harness.bean(TaskManager.class).scheduleTaskNow(oid, runNow);
            runNow.computeStatusIfUnknown();
            if (runNow.isError() || runNow.isWarning()) {
                throw new AssertionError("Task " + nameOf(TaskType.class, oid) + " cannot be run now: "
                        + runNow.getStatus() + " " + firstMessage(runNow) + suspendedHint(oid));
            }
            long deadline = System.nanoTime() + TesterRuntime.TASK_WAIT_MILLIS * 1_000_000L;
            while (Recorder.taskRuns(oid) == runs) {
                if (System.nanoTime() - deadline >= 0) {
                    throw new AssertionError("Task " + nameOf(TaskType.class, oid) + " did not start in "
                            + TesterRuntime.TASK_WAIT_MILLIS / 1000 + " s");
                }
                Thread.sleep(20);
                Timing.count("driver.runNow.startSleep20");
            }
        } finally {
            TaskSchedules.endRegularRunPermit(oid);
        }
    }

    /**
     * Whether the task is runnable (or running) in the repository: its {@code executionStatus} (4.0), or
     * {@code executionState} (4.4+), read by name.
     */
    private boolean runnable(String oid) {
        try {
            String state = state(task(oid));
            return state == null || state.equals("runnable") || state.equals("running");
        } catch (ObjectNotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read task " + oid, e);
        }
    }

    /**
     * Why a task that cannot be run now is suspended, for the failure: midPoint suspended it when its last run failed,
     * or it is suspended as deployed (or by a suspend) and needs a resume first. Empty if it is not suspended.
     */
    private String suspendedHint(String oid) {
        try {
            PrismObject<TaskType> task = task(oid);
            if (!"suspended".equals(state(task))) {
                return "";
            }
            OperationResultStatusType last = task.asObjectable().getResultStatus();
            if (last == OperationResultStatusType.FATAL_ERROR || last == OperationResultStatusType.PARTIAL_ERROR) {
                return ". The task is suspended: midPoint suspended it when its last run failed (" + last + ")";
            }
            return ". The task is suspended: resume it first (t.task(name).resume(), mp.resumeTask), as an operator"
                    + " does. The reset after each test restores the task's state as well, so resume it where the state"
                    + " the tests start from is set up, not in an earlier test";
        } catch (Exception e) {
            return "";
        }
    }

    private PrismObject<TaskType> task(String oid) throws Exception {
        return harness.bean(RepositoryService.class).getObject(TaskType.class, oid, null,
                new OperationResult("task state"));
    }

    /** The task's {@code executionStatus} (4.0) or {@code executionState} (4.4+), read by name, in lower case. */
    private static String state(PrismObject<TaskType> task) {
        for (String name : List.of("executionState", "executionStatus")) {
            com.evolveum.midpoint.prism.PrismProperty<?> state = task.findProperty(
                    com.evolveum.midpoint.prism.path.ItemPath.create(new javax.xml.namespace.QName(Xml.NS_C, name)));
            if (state != null && state.getRealValue() != null) {
                return state.getRealValue().toString().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    /** The task's last run start (real time, as midPoint stores it), or null if it never ran or does not exist. */
    Long lastRunStart(String oid) {
        try {
            javax.xml.datatype.XMLGregorianCalendar start = harness.bean(RepositoryService.class)
                    .getObject(TaskType.class, oid, null, new OperationResult("last run")).asObjectable()
                    .getLastRunStartTimestamp();
            return start != null ? start.toGregorianCalendar().getTimeInMillis() : null;
        } catch (ObjectNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read task " + oid, e);
        }
    }

    @Override
    public Run reconcile(String resource) {
        return reconcile(resource, "account", "default");
    }

    @Override
    public Run reconcile(String resource, String kind, String intent) {
        return resourceTask(ResourceTasks.RECONCILE, resource, kind, intent);
    }

    @Override
    public Run importAccounts(String resource) {
        return resourceTask(ResourceTasks.IMPORT, resource, "account", "default");
    }

    @Override
    public Run liveSync(String resource) {
        return liveSync(resource, "account", "default");
    }

    @Override
    public Run liveSync(String resource, String kind, String intent) {
        String oid = resourceOid(resource);
        String name = nameOf(ResourceType.class, oid);
        String taskOid = ResourceTasks.oid(ResourceTasks.LIVE_SYNC, oid, kind, intent);
        String label = resourceTaskLabel(ResourceTasks.LIVE_SYNC, name, kind, intent);
        // a later call runs the task again: it holds the token of its last run
        boolean again = exists(TaskType.class, taskOid);
        if (again) {
            awaitBackgroundWork(label);
        }
        Operation create = importOperation(ResourceTasks.xml(ResourceTasks.LIVE_SYNC, oid, name, kind, intent), false);
        return run(label, (task, result) -> {
            if (again) {
                runNow(taskOid, result);
            } else {
                create.run(task, result);
            }
            TaskSchedules.awaitBackgroundWork(harness, TesterRuntime.TASK_WAIT_MILLIS);
            // a change the run cannot process fails the task (midPoint then suspends it): the call fails with it
            Task after = harness.bean(TaskManager.class).getTaskWithResult(taskOid,
                    new OperationResult("live sync result"));
            OperationResult taskResult = after.getResult();
            if (taskResult != null && taskResult.isFatalError()) {
                throw new AssertionError("Task " + after.getName().getOrig() + " failed: " + firstMessage(taskResult));
            }
            return new Outcome(taskOid, null);
        });
    }

    private static String resourceTaskLabel(String operation, String resourceName, String kind, String intent) {
        return operation + " " + resourceName + ("account".equals(kind) && "default".equals(intent) ? ""
                : " " + kind + "/" + intent);
    }

    private boolean exists(Class<? extends ObjectType> type, String oid) {
        try {
            harness.bean(RepositoryService.class).getObject(type, oid, null, new OperationResult("exists"));
            return true;
        } catch (ObjectNotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + oid, e);
        }
    }

    /**
     * The errors of the run of the task with this OID (the call ran it): added to the call's result, so that an error
     * the task met on an object (a reconciliation that failed on an account) fails the call as it fails the task in
     * midPoint. Nothing for an OID of another type, or a task that ended without error; else the errors to report
     * (the result's tree and the last item the task failed on).
     */
    /** The result status of the task's last run (null if there is no task). */
    private com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType resultStatus(String oid) {
        TaskType task = find(TaskType.class, oid);
        return task != null ? task.getResultStatus() : null;
    }

    private String taskErrors(String oid, OperationResult result) throws Exception {
        if (oid == null) {
            return null;
        }
        ObjectQuery query = prismContext().queryFor(TaskType.class).id(oid).build();
        List<PrismObject<TaskType>> stored = harness.bean(RepositoryService.class).searchObjects(TaskType.class, query,
                null, new OperationResult("task"));
        if (stored.isEmpty()) {
            return null;
        }
        com.evolveum.midpoint.task.api.Task task = harness.bean(TaskManager.class).getTaskWithResult(oid,
                new OperationResult("task result"));
        com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType status = task.getResultStatus();
        if (status != com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType.FATAL_ERROR
                && status != com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType.PARTIAL_ERROR) {
            return null;
        }
        result.addSubresult(task.getResult());
        // the result counts the failures; the task's statistics keep the last failed item and why
        List<String> failures = TaskFailures.of(prismContext().xmlSerializer().serialize(stored.get(0)));
        return PostInitialImport.errors(result)
                + (failures.isEmpty() ? "" : "last failure: " + String.join("\nlast failure: ", failures) + "\n");
    }

    /** A single-run resource task, imported (overwriting the one of an earlier call) and so run and waited for. */
    private Run resourceTask(String operation, String resource, String kind, String intent) {
        String oid = resourceOid(resource);
        String name = nameOf(ResourceType.class, oid);
        String label = resourceTaskLabel(operation, name, kind, intent);
        Operation create = importOperation(ResourceTasks.xml(operation, oid, name, kind, intent), false);
        String taskOid = ResourceTasks.oid(operation, oid, kind, intent);
        return run(label, (task, result) -> {
            create.run(task, result);
            // the task's run: its errors are this call's (taskErrors)
            return new Outcome(taskOid, null);
        });
    }

    @Override
    public synchronized Approvals approvals() {
        if (approvals == null) {
            approvals = new ApprovalsImpl(this);
        }
        return approvals;
    }

    @Override
    public synchronized Certification certification() {
        if (certification == null) {
            certification = new CertificationImpl(this);
        }
        return certification;
    }

    @Override
    public synchronized Reports reports() {
        if (reports == null) {
            reports = new ReportsImpl(this);
        }
        return reports;
    }

    @Override
    public synchronized Dashboards dashboards() {
        if (dashboards == null) {
            dashboards = new DashboardsImpl(this);
        }
        return dashboards;
    }

    @Override
    public MappedFiles files() {
        return FileArea.get();
    }

    /** The test's clock, as {@code MidPointConfigTester#clock()} drives it ({@link Internals#time}). */
    public synchronized Time time() {
        if (time == null) {
            time = new TimeImpl(this);
        }
        return time;
    }

    /** The name of the object with this OID, or the OID when it does not exist (for display). */
    String nameOf(Class<? extends ObjectType> type, String oid) {
        try {
            return harness.bean(RepositoryService.class).getObject(type, oid, null, new OperationResult("name"))
                    .getName().getOrig();
        } catch (Exception e) {
            return oid;
        }
    }

    // ---- reading ---------------------------------------------------------------------------------------------

    @Override
    public <O extends ObjectType> O find(Class<O> type, String nameOrOid) {
        return query("find " + nameOrOid, (task, result) -> {
            RepositoryService repository = harness.bean(RepositoryService.class);
            if (OID.matcher(nameOrOid).matches()) {
                try {
                    // a result of its own: not found is an answer here, not an error
                    return repository.getObject(type, nameOrOid, null, new OperationResult("find")).asObjectable();
                } catch (ObjectNotFoundException e) {
                    return null;
                }
            }
            ObjectQuery query = prismContext().queryFor(type)
                    .item(ObjectType.F_NAME).eqPoly(nameOrOid).matchingOrig()
                    .build();
            List<PrismObject<O>> found = repository.searchObjects(type, query, null, result);
            if (found.isEmpty() && OID_SHAPED.matcher(nameOrOid).matches()) {
                try {
                    return repository.getObject(type, nameOrOid, null, new OperationResult("find")).asObjectable();
                } catch (ObjectNotFoundException | IllegalArgumentException e) {
                    // not found, or not an OID the repository takes (4.4+: a UUID)
                    return null;
                }
            }
            return found.isEmpty() ? null : found.get(0).asObjectable();
        });
    }

    @Override
    public UserType user(String name) {
        return find(UserType.class, name);
    }

    @Override
    public Shadow shadow(String focus, String resource) {
        return shadow(focus, resource, null, null);
    }

    @Override
    public Shadow shadow(String focus, String resource, String kind, String intent) {
        FocusType owner = find(UserType.class, focus);
        if (owner == null) {
            owner = existing(FocusType.class, focus);
        }
        String resourceOid = resourceOid(resource);
        List<Shadow> candidates = new ArrayList<>();
        for (ObjectReferenceType link : owner.getLinkRef()) {
            ShadowType shadow = find(ShadowType.class, link.getOid());
            if (shadow == null || shadow.getResourceRef() == null
                    || !resourceOid.equals(shadow.getResourceRef().getOid())) {
                continue;
            }
            Shadow candidate = new Shadow(shadow, link.getRelation() == null
                    || QNameUtil.match(link.getRelation(), SchemaConstants.ORG_DEFAULT));
            if ((kind == null || kind.equalsIgnoreCase(candidate.kind()))
                    && (intent == null || intent.equals(candidate.intent()))) {
                candidates.add(candidate);
            }
        }
        List<Shadow> live = candidates.stream().filter(c -> !c.dead()).collect(Collectors.toList());
        List<Shadow> chosen = live.isEmpty() ? candidates : live;
        if (chosen.size() > 1 && live.isEmpty()) {
            return chosen.get(chosen.size() - 1);    // only dead ones: the last linked
        }
        if (chosen.size() > 1) {
            throw new AssertionError(owner.getName().getOrig() + " has " + chosen.size() + " shadows on " + resource
                    + (kind != null ? " (" + kind + "/" + intent + ")" : "") + ": " + chosen
                    + "; give kind and intent: mp.shadow(focus, resource, kind, intent)");
        }
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public SynchronizationSituationType situation(String focus, String resource) {
        Shadow shadow = shadow(focus, resource);
        return shadow != null ? shadow.situation() : null;
    }

    @Override
    public <T> T bean(Class<T> type) {
        return harness.bean(type);
    }

    @Override
    public <T> T createBean(Class<T> type) {
        return harness.bean(org.springframework.context.ApplicationContext.class).getAutowireCapableBeanFactory()
                .createBean(type);
    }

    @Override
    public <T> Answer<T> invoke(String label, com.github.wadahiro.midpoint.tester.Invocation<T> code) {
        java.util.List<T> value = new java.util.ArrayList<>(java.util.Collections.singletonList(null));
        Run run = run(label, (task, result) -> {
            value.set(0, code.run(task, result));
            return Outcome.NONE;
        });
        return new Answer<>(value.get(0), run);
    }

    @Override
    public Object harness() {
        return harness.raw();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /**
     * Runs a driver call with the recorder on, until the background tasks it started have finished; fails (with the
     * run) if midPoint reports an error.
     */
    private Run run(String name, Operation operation) {
        return run(name, operation, true);
    }

    /** Runs a driver call with the recorder on; if {@code failOnError}, fails (with the run) on an error. */
    Run run(String name, Operation operation, boolean failOnError) {
        return runAs(null, name, operation, failOnError);
    }

    /** {@link #run} logged in as this user (by name; null: the administrator), in a task the user owns. */
    Run runAs(String user, String name, Operation operation) {
        return runAs(user, name, operation, true);
    }

    Run runAs(String user, String name, Operation operation, boolean failOnError) {
        long total = Timing.start();
        try {
            return runAsTimed(user, name, operation, failOnError);
        } finally {
            Timing.end("driver.run.total", total);
        }
    }

    /** The caller of the driver calls of a {@link #callAs} block; null outside one, and inside its calls' own work. */
    private final ThreadLocal<com.github.wadahiro.midpoint.tester.Caller> caller = new ThreadLocal<>();

    @Override
    public void callAs(com.github.wadahiro.midpoint.tester.Caller who, Runnable calls) {
        com.github.wadahiro.midpoint.tester.Caller before = caller.get();
        caller.set(who);
        try {
            calls.run();
        } finally {
            caller.set(before);
        }
    }

    private Run runAsTimed(String user, String name, Operation operation, boolean failOnError) {
        // the caller applies to this call, not to the lookups and runs it makes itself
        com.github.wadahiro.midpoint.tester.Caller who = caller.get();
        caller.remove();
        try {
            return runAsTimed(user, name, operation, failOnError, who);
        } finally {
            if (who != null) {
                caller.set(who);
            }
        }
    }

    private Run runAsTimed(String user, String name, Operation operation, boolean failOnError,
            com.github.wadahiro.midpoint.tester.Caller who) {
        if (user == null && who != null) {
            user = who.user();
        }
        String owner = who != null && user.equals(who.user()) ? who.owner() : user;
        String channelBefore = who != null && who.channel() != null ? TaskChannel.use(who.channel()) : null;
        if (who != null && who.isPrivileged() && user.equals(who.user())) {
            Operation unprivileged = operation;
            operation = (task, result) -> privileged(() -> unprivileged.run(task, result));
        }
        try {
            return runAsTimed(user, owner, name, operation, failOnError);
        } finally {
            if (who != null && who.channel() != null) {
                TaskChannel.use(channelBefore);
            }
        }
    }

    /** The body of an operation, as {@link #privileged} runs it. */
    private interface Body {
        Outcome run() throws Exception;
    }

    /** The operation with all authorizations added to the logged-in user's, as midPoint's {@code runPrivileged}. */
    private Outcome privileged(Body call) throws Exception {
        Exception[] failure = new Exception[1];
        Outcome outcome = harness.bean(com.evolveum.midpoint.security.api.SecurityContextManager.class)
                .runPrivileged(() -> {
                    try {
                        return call.run();
                    } catch (Exception e) {
                        failure[0] = e;
                        return null;
                    }
                });
        if (failure[0] != null) {
            throw failure[0];
        }
        return outcome;
    }

    private Run runAsTimed(String user, String owner, String name, Operation operation, boolean failOnError) {
        // the login the call leaves: code of the deployment may set or clear the security context (an API add-on's
        // service clears it at its end), the next call must not run as nobody
        org.springframework.security.core.Authentication loggedIn =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (user != null) {
            runtime.login(user);
        }
        Task task = harness.createTask("tester " + name);
        if (owner != null) {
            task.setOwner(existing(UserType.class, owner).asPrismObject());
        }
        OperationResult result = task.getResult();
        int notificationsBefore = drivers().notifications().size();
        int mailsBefore = drivers().mails().size();
        long t = Timing.start();
        Map<java.nio.file.Path, byte[]> filesBefore = FileArea.get().scan();
        Timing.end("driver.fileScan", t);
        Recording recording = Recorder.start(ConfigIndex.get());
        Outcome outcome = Outcome.NONE;
        Throwable failure = null;
        String taskFailed = null;
        try {
            t = Timing.start();
            outcome = harness.inOperationContext(name, task, () -> operation.run(task, result));
            Timing.end("driver.operation", t);
            t = Timing.start();
            awaitBackgroundWork(name);
            Timing.end("driver.awaitBackgroundWork", t);
            taskFailed = taskErrors(outcome.oid, result);
        } catch (Exception | AssertionError e) {
            failure = e;
            // what midPoint started before failing belongs to this run, not to the next one
            TaskSchedules.awaitBackgroundWork(harness, TesterRuntime.TASK_WAIT_MILLIS);
        } finally {
            Recorder.stop(recording);
            if (user != null) {
                runtime.login(TesterRuntime.ADMINISTRATOR);
            } else {
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(loggedIn);
            }
        }
        // the focus of each evaluation, by the repository as the run left it (a user the run added or linked)
        t = Timing.start();
        FocusResolver.resolve(recording.records(), harness.bean(RepositoryService.class), prismContext());
        Timing.end("driver.focusResolve", t);
        List<Evaluation> evaluations = new ArrayList<>();
        recording.records().forEach(r -> evaluations.add(r.view));
        result.computeStatusIfUnknown();
        List<Notification> notifications = drivers().notifications();
        List<Mail> mails = drivers().mails();
        t = Timing.start();
        Map<java.nio.file.Path, byte[]> filesAfter = FileArea.get().scan();
        Timing.end("driver.fileScan", t);
        Run run = new Run(name, evaluations, result, outcome.oid, outcome.modelContext, failure,
                notifications.subList(Math.min(notificationsBefore, notifications.size()), notifications.size()),
                mails.subList(Math.min(mailsBefore, mails.size()), mails.size()), step,
                FileArea.get().written(filesBefore, filesAfter), FileArea.get().deleted(filesBefore, filesAfter));
        runtime.journal(run);
        if (!failOnError) {
            if (failure == null) {
                result.computeStatus();
            }
            return run;
        }
        if (failure != null) {
            throw new OperationFailed(name + " failed: " + failure.getMessage(), run, failure);
        }
        result.computeStatus();
        if (result.isError() && !(partialErrorsAllowed && !result.isFatalError())) {
            // a task's run: its summary says how many objects failed, the tree says why
            throw new OperationFailed(name + " failed: " + result.getStatus() + " " + firstMessage(result)
                    + (taskFailed != null ? "\n" + taskFailed : ""), run, null);
        }
        return run;
    }

    /**
     * Deltas as a driver label that does not depend on midPoint's delta text or on generated OIDs: change type,
     * object type and name (looked up before the call, so a deleted object is named too), the paths of the modified
     * items, e.g. {@code modify user:jdoe [locality]}: the same on every line and in every run (no OIDs, container
     * IDs, work item IDs, timestamps or counters).
     */
    String describe(Collection<? extends ObjectDelta<? extends ObjectType>> deltas) {
        List<String> out = new ArrayList<>();
        for (ObjectDelta<? extends ObjectType> delta : deltas) {
            StringBuilder sb = new StringBuilder(delta.getChangeType().name().toLowerCase(Locale.ROOT))
                    .append(' ').append(typeName(delta.getObjectTypeClass())).append(':');
            if (delta.isAdd() && delta.getObjectToAdd() != null && delta.getObjectToAdd().getName() != null) {
                sb.append(delta.getObjectToAdd().getName().getOrig());
            } else if (delta.getOid() != null) {
                sb.append(nameOf(delta.getObjectTypeClass() != null ? delta.getObjectTypeClass() : ObjectType.class,
                        delta.getOid()));
            } else {
                sb.append('-');
            }
            if (delta.isModify()) {
                List<String> paths = new ArrayList<>();
                for (ItemDelta<?, ?> modification : delta.getModifications()) {
                    List<String> names = new ArrayList<>();
                    for (Object segment : modification.getPath().getSegments()) {
                        if (ItemPath.isName(segment)) {
                            names.add(ItemPath.toName(segment).getLocalPart());
                        }
                    }
                    paths.add(String.join("/", names));
                }
                sb.append(' ').append(paths);
            }
            out.add(sb.toString());
        }
        return String.join(", ", out);
    }

    /** {@code user} of {@code UserType}. */
    static String typeName(Class<?> type) {
        String name = type != null ? type.getSimpleName().replaceAll("Type$", "") : "object";
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Options as a label: the names of the options set ({@code options [raw]}), from the schema bean all lines share
     * ({@code ModelExecuteOptions.toString()} is line-specific).
     */
    static String describe(ModelExecuteOptions options) {
        if (options == null) {
            return "";
        }
        ModelExecuteOptionsType bean = options.toModelExecutionOptionsType();
        // (a TreeSet: some lines have both isRaw() and getRaw())
        java.util.SortedSet<String> set = new java.util.TreeSet<>();
        for (java.lang.reflect.Method getter : ModelExecuteOptionsType.class.getMethods()) {
            String name = getter.getName();
            if (getter.getParameterCount() != 0 || getter.getDeclaringClass() != ModelExecuteOptionsType.class
                    || !(name.startsWith("get") || name.startsWith("is")) || name.equals("getClass")) {
                continue;
            }
            Object value;
            try {
                value = getter.invoke(bean);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            if (value == null || Boolean.FALSE.equals(value)
                    || value instanceof Collection && ((Collection<?>) value).isEmpty()) {
                continue;
            }
            String option = name.substring(name.startsWith("is") ? 2 : 3);
            set.add(Character.toLowerCase(option.charAt(0)) + option.substring(1));
        }
        return " options " + set;
    }

    /** The objects of an XML text as a label ({@code user:jdoe, role:Employee}), or {@code xml}. */
    private String objectsIn(String xml) {
        try {
            List<String> out = new ArrayList<>();
            for (PrismObject<?> object : prismContext().parserFor(xml).language(PrismContext.LANG_XML)
                    .parseObjects()) {
                out.add(typeName(object.getCompileTimeClass()) + ":"
                        + (object.getName() != null ? object.getName().getOrig() : "-"));
            }
            return out.isEmpty() ? "xml" : String.join(", ", out);
        } catch (Exception e) {
            return "xml";
        }
    }

    void awaitBackgroundWork(String name) {
        List<String> pending = TaskSchedules.awaitBackgroundWork(harness, TesterRuntime.TASK_WAIT_MILLIS);
        if (!pending.isEmpty()) {
            throw new AssertionError("Background work of " + name + " did not finish in "
                    + TesterRuntime.TASK_WAIT_MILLIS / 1000 + " s: " + pending);
        }
    }

    <T> T query(String name, Query<T> query) {
        Task task = harness.createTask("tester " + name);
        OperationResult result = task.getResult();
        T value;
        try {
            value = harness.inOperationContext(name, task, () -> query.run(task, result));
        } catch (Exception e) {
            throw new AssertionError(name + " failed: " + e.getMessage(), e);
        }
        result.computeStatus();
        if (result.isError()) {
            throw new AssertionError(name + " failed: " + result.getStatus() + " " + firstMessage(result));
        }
        return value;
    }

    static String firstMessage(OperationResult result) {
        if (result.getMessage() != null) {
            return result.getMessage();
        }
        for (OperationResult sub : result.getSubresults()) {
            if (sub.isError()) {
                return sub.getOperation() + ": " + firstMessage(sub);
            }
        }
        return "";
    }

    <O extends ObjectType> O existing(Class<O> type, String nameOrOid) {
        O object = find(type, nameOrOid);
        if (object == null) {
            throw new AssertionError("No " + type.getSimpleName() + " " + nameOrOid);
        }
        return object;
    }

    private String resourceOid(String resource) {
        if (runtime.findTarget(resource) != null || OID.matcher(resource).matches()) {
            return resource;
        }
        return existing(ResourceType.class, resource).getOid();
    }

    /**
     * The OID of the mirror connector, looked up every time: the connector objects of a deployment loaded from the
     * cache (another JVM's dump) have that JVM's OIDs.
     */
    String mirrorConnectorOid() {
        return connectorOid(MirrorBundle.CONNECTOR_TYPE);
    }

    /** The objects of the type as stored (repository, raw). */
    <O extends ObjectType> List<PrismObject<O>> stored(Class<O> type) {
        return query("read " + type.getSimpleName(), (task, result) -> {
            List<PrismObject<O>> out = new ArrayList<>();
            for (PrismObject<O> object : harness.bean(RepositoryService.class).searchObjects(type, null, null,
                    result)) {
                out.add(object.clone());
            }
            return out;
        });
    }

    /** Replaces the stored objects of the type (repository, raw) by these, with their OIDs. */
    <O extends ObjectType> void replaceStored(Class<O> type, List<PrismObject<O>> objects) {
        query("replace " + type.getSimpleName(), (task, result) -> {
            RepositoryService repository = harness.bean(RepositoryService.class);
            for (PrismObject<O> object : repository.searchObjects(type, null, null, result)) {
                repository.deleteObject(type, object.getOid(), result);
            }
            for (PrismObject<O> object : objects) {
                repository.addObject(object.clone(), com.evolveum.midpoint.repo.api.RepoAddOptions.createOverwrite(),
                        result);
            }
            return null;
        });
    }

    /**
     * Rewrites top-level items of a stored object (repository, raw): the object's XML goes through {@code transform},
     * and those of {@code items} that differ afterwards are replaced. Nothing when the object does not exist.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    /** The mail servers of the stored system configuration that are not the tester's (see {@link MailRedirect}). */
    List<String> unredirectedMailServers() {
        return query("check the mail servers", (task, result) -> {
            PrismObject<com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType> config =
                    harness.bean(RepositoryService.class).getObject(
                            com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType.class,
                            "00000000-0000-0000-0000-000000000001", null, result);
            return MailRedirect.otherServers(prismContext().xmlSerializer().serialize(config), "127.0.0.1");
        });
    }

    /**
     * Applies the stored system configuration to midPoint's components (mail transports, logging, caches...) as a
     * change of it does: after a patch of the repository, which is no change event.
     */
    void applySystemConfiguration() {
        query("apply the system configuration", (task, result) -> {
            harness.bean(com.evolveum.midpoint.repo.api.SystemConfigurationChangeDispatcher.class)
                    .dispatch(true, true, result);
            return null;
        });
    }

    <O extends ObjectType> void patchStored(Class<O> type, String oid, java.util.function.UnaryOperator<String> transform,
            String... items) {
        query("patch " + oid, (task, result) -> {
            RepositoryService repository = harness.bean(RepositoryService.class);
            PrismObject<O> current;
            try {
                current = repository.getObject(type, oid, null, new OperationResult("patch"));
            } catch (ObjectNotFoundException e) {
                return null;
            }
            String xml = prismContext().xmlSerializer().serialize(current);
            String patched = transform.apply(xml);
            if (patched.equals(xml)) {
                return null;
            }
            PrismObject<O> target = prismContext().parserFor(patched).language(PrismContext.LANG_XML).parse();
            List<ItemDelta<?, ?>> deltas = new ArrayList<>();
            for (String item : items) {
                ItemPath path = ItemPath.create(new QName(SchemaConstants.NS_C, item));
                com.evolveum.midpoint.prism.Item<?, ?> before = current.findItem(path);
                com.evolveum.midpoint.prism.Item<?, ?> after = target.findItem(path);
                if (java.util.Objects.equals(before, after)) {
                    continue;
                }
                List<com.evolveum.midpoint.prism.PrismValue> values = new ArrayList<>();
                if (after != null) {
                    for (Object value : after.getValues()) {
                        values.add(((com.evolveum.midpoint.prism.PrismValue) value).clone());
                    }
                }
                deltas.addAll((Collection) prismContext().deltaFor(type).item(path).replace((Collection) values)
                        .asItemDeltas());
            }
            if (!deltas.isEmpty()) {
                repository.modifyObject(type, oid, (Collection) deltas, result);
            }
            return null;
        });
    }

    /**
     * The XML with the mail servers of its system configuration redirected to the in-memory SMTP server (see
     * {@link MailRedirect}); a changed copy is written to {@code <workDir>/tester-mail/} for inspection.
     */
    String redirectMail(String xml) {
        Path fileDir = runtime.workDir().resolve("tester-mail/files");
        MailRedirect.Result redirect = MailRedirect.apply(xml, "127.0.0.1", drivers().smtpPort(), fileDir);
        if (redirect.changed()) {
            try {
                Files.createDirectories(fileDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Path copy = runtime.writeWorkFile(
                    "tester-mail/system-configuration-" + mailCount.incrementAndGet() + ".xml", redirect.xml);
            System.out.println("Tester: mail transports redirected (" + copy + "): "
                    + String.join("; ", redirect.notes));
        }
        return redirect.xml;
    }

    /** The OID of the discovered connector of this type (connector class name); fails if there is none. */
    String connectorOid(String connectorType) {
        return query("find the connector " + connectorType, (task, result) -> {
            ObjectQuery query = prismContext().queryFor(ConnectorType.class)
                    .item(ConnectorType.F_CONNECTOR_TYPE).eq(connectorType)
                    .build();
            List<PrismObject<ConnectorType>> connectors =
                    harness.bean(RepositoryService.class).searchObjects(ConnectorType.class, query, null, result);
            if (connectors.isEmpty()) {
                throw new IllegalStateException("The connector " + connectorType + " was not discovered (its bundle"
                        + " must be on the classpath)");
            }
            return connectors.get(0).getOid();
        });
    }

    /** Indexes XML given as text (written under the working directory) so its evaluations can be located. */
    private String registerText(String xml) {
        String name = "xml-" + xmlCount.incrementAndGet() + ".xml";
        ConfigIndex.get().register(runtime.writeWorkFile("tester-xml/" + name, xml), name, false);
        return name;
    }

    ModelService model() {
        return harness.bean(ModelService.class);
    }

    static String name(File file) {
        return file.getPath().replace(File.separatorChar, '/');
    }

    private static String read(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file, e);
        }
    }
}
