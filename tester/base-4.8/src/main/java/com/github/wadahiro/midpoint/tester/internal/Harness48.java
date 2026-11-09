package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.xml.namespace.QName;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContextManager;

import com.evolveum.midpoint.model.test.AbstractModelIntegrationTest;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.repo.api.CacheDispatcher;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.test.MidpointTestContextWithTask;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ScheduleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskExecutionStateType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskRecurrenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * Harness for midPoint 4.8 (native repository). Spring prepares the single instance; its {@code @PostConstruct}
 * {@code initSystem()} (inherited) runs {@link #initSystem(Task, OperationResult)}.
 */
@ContextConfiguration(locations = "classpath:tester/ctx-tester.xml")
public class Harness48 extends AbstractModelIntegrationTest implements Harness {

    private static final String ADMINISTRATOR_OID = "00000000-0000-0000-0000-000000000002";

    @Autowired private CacheDispatcher cacheDispatcher;
    @Autowired private SqaleRepoContext sqaleRepoContext;

    static Harness48 start() throws Exception {
        TestContextManager contextManager = new TestContextManager(Harness48.class);
        Harness48 harness = new Harness48();
        contextManager.beforeTestClass();
        contextManager.prepareTestInstance(harness);
        return harness;
    }

    @Override
    public void initSystem(Task initTask, OperationResult initResult) throws Exception {
        super.initSystem(initTask, initResult);
        for (String xml : Baseline.objects()) {
            PrismObject<ObjectType> object = prismContext.parseObject(xml);
            repositoryService.addObject(object, null, initResult);
        }
        modelService.postInit(initResult);
        // notifications as in production (model tests disable them), sent to a recording dummy transport
        notificationManager.setDisabled(false);
        transportService.registerTransport(notificationTransport);
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
        // midPoint's test helpers (getTestTask(), import requests, ...) expect a test method context
        if (MidpointTestContextWithTask.get() != null) {
            return operation.call();
        }
        MidpointTestContextWithTask.create(getClass(), name, task, task.getResult());
        try {
            return operation.call();
        } finally {
            MidpointTestContextWithTask.destroy();
        }
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
        OperationResult testResult = modelService.testResource(oid, task, result);
        testResult.computeStatus();
        if (!testResult.isSuccess()) {
            throw new AssertionError("Test of resource " + oid + " failed: " + testResult.getMessage() + "\n"
                    + PostInitialImport.errors(testResult));
        }
    }

    @Override
    public void importOneAccount(String resourceOid, QName namingAttribute, String accountName, Task task,
            OperationResult result) throws Exception {
        importAccountsRequest()
                .withResourceOid(resourceOid)
                .withNamingAttribute(namingAttribute)
                .withNameValue(accountName)
                .withTask(task)
                .executeOnForeground(result);
    }

    @Override
    public void resetInternals() {
        LineSupport.clearRepositoryCaches(sqaleRepoContext);
        cacheDispatcher.dispatchInvalidation(null, null, false, null);
        // Quartz keeps its jobs in memory: drop those of tasks the reset removed, reschedule the restored ones (the
        // scheduler is held: TesterRuntime.reset puts the triggers back on the restored schedules, see TaskSchedules)
        long t = Timing.start();
        taskManager.synchronizeTasks(new OperationResult("synchronize tasks"));
        Timing.end("reset.internals.synchronizeTasks", t);
        clock.resetOverride();
        TimeAgent.sync(clock);
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
    public org.quartz.Scheduler quartzScheduler() {
        return bean(com.evolveum.midpoint.task.quartzimpl.quartz.LocalScheduler.class).getQuartzScheduler();
    }

    /** Running task threads, then the tasks that run or are due now (as {@code name (state)}). */
    private List<String> pendingWork() {
        List<String> pending = new ArrayList<>();
        int threads = Recorder.runningTaskThreads();
        if (threads > 0) {
            pending.add(threads + " task thread(s)");
        }
        for (TaskType task : activeTasks()) {
            pending.add(task.getName() + " (" + task.getExecutionState() + ")");
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
                    .item(TaskType.F_EXECUTION_STATE).eq(TaskExecutionStateType.RUNNING, TaskExecutionStateType.RUNNABLE)
                    .build();
            long now = System.currentTimeMillis();
            for (PrismObject<TaskType> object : repositoryService.searchObjects(TaskType.class, query, null,
                    new OperationResult("active tasks"))) {
                TaskType task = object.asObjectable();
                ScheduleType schedule = task.getSchedule();
                // as midPoint: without a recurrence, a task with an interval or cron-like pattern is recurring (e.g.
                // the initial objects' cleanup and scanners)
                boolean recurring = com.evolveum.midpoint.schema.util.task.TaskTypeUtil.getEffectiveRecurrence(task)
                        == TaskRecurrenceType.RECURRING;
                boolean later = schedule != null && schedule.getEarliestStartTime() != null
                        && schedule.getEarliestStartTime().toGregorianCalendar().getTimeInMillis() > now;
                if (task.getExecutionState() == TaskExecutionStateType.RUNNING || !recurring && !later
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
