package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.xml.namespace.QName;

import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContextManager;

import com.evolveum.midpoint.common.refinery.RefinedAttributeDefinition;
import com.evolveum.midpoint.common.refinery.RefinedObjectClassDefinition;
import com.evolveum.midpoint.common.refinery.RefinedResourceSchemaImpl;
import com.evolveum.midpoint.model.test.AbstractModelIntegrationTest;
import com.evolveum.midpoint.model.test.DummyTransport;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.repo.api.CacheDispatcher;
import com.evolveum.midpoint.repo.sql.data.common.dictionary.ExtItemDictionary;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.quartzimpl.TaskManagerQuartzImpl;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ScheduleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowKindType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskExecutionStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskRecurrenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * Harness for midPoint 4.0 (old Hibernate repository). Spring prepares the single instance; the system is initialized
 * by {@code initSystemConditional()}, which TestNG would call before the first test method.
 */
@ContextConfiguration(locations = "classpath:tester/ctx-tester.xml")
public class Harness40 extends AbstractModelIntegrationTest implements Harness {

    private static final String ADMINISTRATOR_OID = "00000000-0000-0000-0000-000000000002";

    @Autowired private CacheDispatcher cacheDispatcher;
    @Autowired private ExtItemDictionary extItemDictionary;

    static Harness40 start() throws Exception {
        // on JDK 16+ Groovy 2.5 fails later, in Spring's context, with a NoClassDefFoundError of its ReflectionCache
        if (Runtime.version().feature() >= 16) {
            throw new IllegalStateException("midPoint 4.0 runs on JDK 11 (its Groovy 2.5 does not start on JDK 16 or"
                    + " later), this is JDK " + Runtime.version() + " (" + System.getProperty("java.home") + "): run"
                    + " Maven on JDK 11, or give it a JDK 11 toolchain (~/.m2/toolchains.xml or mvn -t <file>; the"
                    + " parent's profile jdk11-toolchain selects it when Maven runs on JDK 16 or later)");
        }
        TestContextManager contextManager = new TestContextManager(Harness40.class);
        Harness40 harness = new Harness40();
        contextManager.beforeTestClass();
        contextManager.prepareTestInstance(harness);
        harness.initSystemConditional();
        return harness;
    }

    @Override
    public void initSystem(Task initTask, OperationResult initResult) throws Exception {
        super.initSystem(initTask, initResult);
        for (String xml : Baseline.objects()) {
            // 4.0's parser does not detect XML that starts with a comment (the license header of initial objects)
            PrismObject<ObjectType> object = prismContext.parseObject(xml.replaceFirst("^\\s*<!--(?s:.*?)-->", ""));
            repositoryService.addObject(object, null, initResult);
        }
        modelService.postInit(initResult);
        // notifications as in production (model tests disable them), sent to a recording dummy transport
        notificationManager.setDisabled(false);
        notificationManager.registerTransport(DummyTransport.NAME, notificationTransport);
        taskManager.registerTaskListener(new TaskThreadListener(clock));
    }

    private final RecordingTransport notificationTransport = new RecordingTransport();

    RecordingTransport notificationTransport() {
        return notificationTransport;
    }

    /** Mail transports send here: the system configuration's mail servers are redirected to it on import. */
    private final SmtpServer smtpServer = new SmtpServer();

    SmtpServer smtpServer() {
        return smtpServer;
    }

    @Override
    public <T> T bean(Class<T> type) {
        return SpringBeans.get(applicationContext, type);
    }

    @Override
    public Task createTask(String name) {
        Task task = super.createTask(name);
        if (TaskChannel.current() != null) {
            task.setChannel(TaskChannel.current());
        }
        try {
            // cases (approvals, manual provisioning) and certification fail on a task without an owner
            task.setOwner(repositoryService.getObject(UserType.class, ADMINISTRATOR_OID, null,
                    new OperationResult("owner")));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot set the task owner", e);
        }
        return task;
    }

    @Override
    public <T> T inOperationContext(String name, Task task, Callable<T> operation) throws Exception {
        // 4.0 helpers need no thread-local test context
        return operation.call();
    }

    @Override
    public void loginAs(String username) throws Exception {
        login(username);
    }

    @Override
    public PrismObject<UserType> findUser(String name) throws Exception {
        return findUserByUsername(name);
    }

    @Override
    public void checkResource(String oid, Task task, OperationResult result) throws Exception {
        OperationResult testResult = modelService.testResource(oid, task);
        testResult.computeStatus();
        if (!testResult.isSuccess()) {
            throw new AssertionError("Test of resource " + oid + " failed: " + testResult.getMessage() + "\n"
                    + PostInitialImport.errors(testResult));
        }
    }

    @Override
    public void importOneAccount(String resourceOid, QName namingAttribute, String accountName, Task task,
            OperationResult result) throws Exception {
        // 4.0 has no import request builder (importAccountsRequest): find the account by its naming attribute (creates
        // its shadow), then import that shadow
        PrismObject<ResourceType> resource = modelService.getObject(ResourceType.class, resourceOid, null, task, result);
        RefinedObjectClassDefinition account = RefinedResourceSchemaImpl.getRefinedSchema(resource)
                .getDefaultRefinedDefinition(ShadowKindType.ACCOUNT);
        RefinedAttributeDefinition<?> naming = account.findAttributeDefinition(namingAttribute);
        if (naming == null) {
            throw new AssertionError("No attribute " + namingAttribute + " in " + account);
        }
        ObjectQuery query = prismContext.queryFor(ShadowType.class)
                .itemWithDef(naming, ShadowType.F_ATTRIBUTES, naming.getItemName()).eq(accountName)
                .and().item(ShadowType.F_OBJECT_CLASS).eq(account.getObjectClassDefinition().getTypeName())
                .and().item(ShadowType.F_RESOURCE_REF).ref(resourceOid)
                .build();
        List<PrismObject<ShadowType>> shadows = modelService.searchObjects(ShadowType.class, query, null, task, result);
        if (shadows.size() != 1) {
            throw new AssertionError("Expected one account " + accountName + " on resource " + resourceOid + ": " + shadows);
        }
        modelService.importFromResource(shadows.get(0).getOid(), task, result);
    }

    @Override
    public void resetInternals() {
        extItemDictionary.initialize();
        cacheDispatcher.dispatchInvalidation(null, null, false, null);
        clearResourceCache();
        // Quartz keeps its jobs in memory: drop those of tasks the reset removed, reschedule the restored ones (the
        // scheduler is held: TesterRuntime.reset puts the triggers back on the restored schedules, see TaskSchedules)
        long t = Timing.start();
        taskManager.synchronizeTasks(new OperationResult("synchronize tasks"));
        Timing.end("reset.internals.synchronizeTasks", t);
        clock.resetOverride();
        TimeAgent.sync(clock);
    }

    /**
     * 4.0's provisioning ResourceCache is not invalidated by the CacheDispatcher (it is since 4.4) and matches entries by
     * OID + version; after a reset the versions repeat, so a resource re-imported with another definition would be
     * served from the cache. It has no clear method, hence reflection.
     */
    private void clearResourceCache() {
        try {
            Object resourceCache = bean(com.evolveum.midpoint.provisioning.impl.ResourceCache.class);
            java.lang.reflect.Field field = resourceCache.getClass().getDeclaredField("cache");
            field.setAccessible(true);
            synchronized (resourceCache) {
                ((java.util.Map<?, ?>) field.get(resourceCache)).clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot clear the provisioning resource cache", e);
        }
    }

    // ---- background work ----------------------------------------------------------------------------------------

    @Override
    public List<String> awaitBackgroundWork(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        List<String> pending;
        while (true) {
            long t = Timing.start();
            pending = pendingWork();
            Timing.end("await.pendingWork (task search)", t);
            if (pending.isEmpty() || System.nanoTime() - deadline >= 0) {
                return pending;
            }
            try {
                long s = Timing.start();
                Thread.sleep(20);
                Timing.end("await.harnessSleep20", s);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pending;
            }
        }
    }

    @Override
    public void stopBackgroundWork() {
        List<String> oids = new ArrayList<>();
        for (TaskType task : activeTasks()) {
            oids.add(task.getOid());
        }
        if (!oids.isEmpty()) {
            taskManager.suspendTasks(oids, 5000, new OperationResult("suspend"));
        }
    }

    @Override
    public Scheduler quartzScheduler() {
        return ((TaskManagerQuartzImpl) taskManager).getExecutionManager().getQuartzScheduler();
    }

    /** Running task threads, then the tasks that run or are due now (as {@code name (state)}). */
    private List<String> pendingWork() {
        List<String> pending = new ArrayList<>();
        int threads = Recorder.runningTaskThreads();
        if (threads > 0) {
            pending.add(threads + " task thread(s)");
        }
        for (TaskType task : activeTasks()) {
            Long next = taskManager.getNextRunStartTime(task.getOid(), new OperationResult("next run"));
            pending.add(task.getName() + " (" + task.getExecutionStatus() + ", last run " + task.getLastRunStartTimestamp()
                    + ", next run " + (next != null ? java.time.Instant.ofEpochMilli(next) : null) + ")");
        }
        return pending;
    }

    /**
     * Whether Quartz is about to start the recurring task: it never ran (its first run is due now) or its next run is
     * within a second. While the regular runs are held ({@link TaskSchedules#holdRegularRuns}), only a run now starts
     * it.
     */
    private boolean isDue(TaskType task, long now) {
        Boolean held = TaskSchedules.heldTaskStarts(quartzScheduler(), task.getOid(), now + 1000);
        if (held != null) {
            return held;
        }
        ScheduleType schedule = task.getSchedule();
        if (schedule == null || schedule.getInterval() == null && schedule.getCronLikePattern() == null) {
            // no trigger: it runs only when it is started (run now)
            return false;
        }
        if (task.getLastRunStartTimestamp() == null) {
            return true;
        }
        Long next = taskManager.getNextRunStartTime(task.getOid(), new OperationResult("next run"));
        return next != null && next <= now + 1000;
    }

    private List<TaskType> activeTasks() {
        List<TaskType> out = new ArrayList<>();
        try {
            ObjectQuery query = prismContext.queryFor(TaskType.class)
                    .item(TaskType.F_EXECUTION_STATUS).eq(TaskExecutionStatusType.RUNNABLE)
                    .build();
            long now = System.currentTimeMillis();
            for (PrismObject<TaskType> object : repositoryService.searchObjects(TaskType.class, query, null,
                    new OperationResult("active tasks"))) {
                TaskType task = object.asObjectable();
                ScheduleType schedule = task.getSchedule();
                boolean recurring = task.getRecurrence() == TaskRecurrenceType.RECURRING;
                boolean later = schedule != null && schedule.getEarliestStartTime() != null
                        && schedule.getEarliestStartTime().toGregorianCalendar().getTimeInMillis() > now;
                // 4.0 has no RUNNING state: a running task is RUNNABLE (and counted as a task thread)
                if (!recurring && !later
                        || recurring && !later && isDue(task, now)) {
                    out.add(task);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot search tasks", e);
        }
        return out;
    }

    @Override
    public Object raw() {
        return this;
    }
}
