package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.tester.internal.Internals;
import com.github.wadahiro.midpoint.tester.internal.Recorder;

/**
 * How often tasks run on a deployment whose recurring tasks are held ({@code tasks.recurring: held}, the default): an
 * imported single-run task runs once in the import, a recurring one does not run by itself; a task added through the
 * Clockwork runs once after the change, {@code mp.runTask} runs a task exactly once, a reset runs nothing, and a
 * recurring task runs on its schedule by midPoint's clock with the due tasks ({@code t.clock().runDue()}), its first
 * run when it never ran ({@code HrLifecycleScenarioTest.theHeldReconciliationRunsWhenDue}). The class runs first in
 * the JVM
 * ({@code junit-platform.properties} orders classes by {@link Order}): a reset that ran a task leaked that run into the
 * first test class (4.4).
 */
@MidPointConfigTest(Fixtures.BARE)
@Order(0)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaskRunsTest {

    static final File DIR = Fixtures.dir("async");
    static final String RECURRING = "10000000-0000-0000-0000-00000000f020";
    static final String SINGLE = "10000000-0000-0000-0000-00000000f021";
    /** The single-run task once more, added by {@code addObjectXml} or {@code execute} instead of an import. */
    static final String ADDED = "10000000-0000-0000-0000-00000000f022";
    /**
     * A recurring task every 2 seconds (loosely bound), imported in a method under one of these OIDs. Not 1 second: the
     * waits for background work count a task due within a second as pending, it would never be idle.
     */
    static final String OFTEN = "10000000-0000-0000-0000-00000000f023";
    static final String OFTEN_LATE = "10000000-0000-0000-0000-00000000f025";
    /** The recurring task once more, on a cron schedule. */
    static final String CRON = "10000000-0000-0000-0000-00000000f026";
    /** Longer than the interval of the task that runs often. */
    static final long OFTEN_WAIT = 3000;
    /** Users the recomputation processes: kate (Auditor), ext; the Auditor mapping is evaluated for kate. */
    static int perRun;

    /** The task runs counted at the end of the previous method (the reset in between must not add any). */
    static int recurringRuns;
    static int singleRuns;

    @BeforeEach
    void configure(MidPointConfigTester t) {
        // the reset after the previous method ran nothing
        assertThat(Recorder.taskRuns(RECURRING)).as("runs of the recurring task across the reset").isEqualTo(recurringRuns);
        assertThat(Recorder.taskRuns(SINGLE)).as("runs of the single-run task across the reset").isEqualTo(singleRuns);

        MidPoint mp = t.midpoint();
        mp.importObject(new File(DIR, "certified-users.xml"));
        int recurring = Recorder.taskRuns(RECURRING);
        int single = Recorder.taskRuns(SINGLE);

        // both are imported runnable: the single-run one runs once, in its import; the recurring one is held
        mp.importObject(Fixtures.of(mp, new File(DIR, "task-recompute-users.xml")));
        Run singleImport = mp.importObject(Fixtures.of(mp, new File(DIR, "task-recompute-users-once.xml")));

        assertThat(Recorder.taskRuns(RECURRING) - recurring).as("runs of the recurring task's import").isZero();
        assertThat(Recorder.taskRuns(SINGLE) - single).as("runs of the single-run task's import").isEqualTo(1);
        perRun = singleImport.mappings("auditor-description").list().size();
        assertThat(perRun).isPositive();
        recurringRuns = Recorder.taskRuns(RECURRING);
        singleRuns = Recorder.taskRuns(SINGLE);
    }

    @AfterEach
    void remember() {
        recurringRuns = Recorder.taskRuns(RECURRING);
        singleRuns = Recorder.taskRuns(SINGLE);
    }

    @Test
    @Order(1)
    void runTaskRunsARecurringTaskOnce(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        for (int i = 0; i < 3; i++) {
            int before = Recorder.taskRuns(RECURRING);
            Run run = mp.runTask("recompute users");
            assertThat(Recorder.taskRuns(RECURRING) - before).as("runs in runTask #" + i).isEqualTo(1);
            assertThat(run.mappings("auditor-description").list()).hasSize(perRun)
                    .allSatisfy(e -> assertThat(e.task()).isEqualTo("recompute users"));
        }
        assertThat(Recorder.taskRuns(SINGLE)).isEqualTo(singleRuns);
    }

    @Test
    @Order(2)
    void runTaskRunsASingleRunTaskOnce(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        for (int i = 0; i < 3; i++) {
            int before = Recorder.taskRuns(SINGLE);
            Run run = mp.runTask("recompute users once");
            assertThat(Recorder.taskRuns(SINGLE) - before).as("runs in runTask #" + i).isEqualTo(1);
            assertThat(run.mappings("auditor-description").list()).hasSize(perRun);
        }
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);
    }

    @Test
    @Order(3)
    void deleteTheTasks(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        // the reset brings them back (rows and Quartz jobs) without running them: checked before the next method
        mp.deleteObject(TaskType.class, RECURRING);
        mp.deleteObject(TaskType.class, SINGLE);
        assertThat(mp.find(TaskType.class, RECURRING)).isNull();
    }

    @Test
    @Order(4)
    void theNextRunIsAnHourAfterTheLastRun(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.runTask("recompute users");
        // the next run is an hour after that run: not now
        TaskType task = mp.find(TaskType.class, RECURRING);
        long lastRun = task.getLastRunStartTimestamp().toGregorianCalendar().getTimeInMillis();
        Long next = mp.bean(TaskManager.class).getNextRunStartTime(RECURRING, new OperationResult("next"));
        assertThat(next).isCloseTo(lastRun + 3600_000L, org.assertj.core.data.Offset.offset(1000L));
        assertThat(ofTask(mp.waitForTasks(), "recompute users")).isEmpty();
    }

    @Test
    @Order(5)
    void recurringTaskRunsOnItsScheduleByMidPointsClock(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        // it never ran: due now, once
        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(++recurringRuns);
        // then not due: it does not run
        assertThat(ofTask(Internals.time(mp).runDueTasks(), "recompute users")).isEmpty();
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);

        // an hour later the recurring task is due (the single-run one has run and is closed)
        Internals.time(mp).advance("PT1H");
        // the JVM's clock moved too, but the regular runs are held (aRecurringTaskAddedAfterTheClockHasMovedIsHeldToo)
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);
        Run due = Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(RECURRING) - recurringRuns).isEqualTo(1);
        assertThat(Recorder.taskRuns(SINGLE)).isEqualTo(singleRuns);
        assertThat(due.mappings("auditor-description").list()).hasSize(perRun)
                .allSatisfy(e -> assertThat(e.task()).isEqualTo("recompute users"));

        // it ran at the moved clock: the next run is another hour later
        assertThat(ofTask(Internals.time(mp).runDueTasks(), "recompute users")).isEmpty();
        Internals.time(mp).advance("PT30M");
        assertThat(ofTask(Internals.time(mp).runDueTasks(), "recompute users")).isEmpty();
        Internals.time(mp).advance("PT30M");
        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(RECURRING) - recurringRuns).isEqualTo(2);
    }

    @Test
    @Order(6)
    void suspendedTaskIsNotDue(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.bean(TaskManager.class).suspendTasks(java.util.List.of(RECURRING), 5000, new OperationResult("suspend"));
        Internals.time(mp).advance("PT2H");
        assertThat(ofTask(Internals.time(mp).runDueTasks(), "recompute users")).isEmpty();
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);
    }

    /**
     * A runnable task added through the Clockwork ({@code addObjectXml}, {@code execute}) runs once, after the change:
     * Quartz is held while the change writes the task, as in an import. A run that started while midPoint still wrote
     * the task lost its work state on 4.0 ("No work state in task") and the task was suspended.
     */
    @Test
    @Order(7)
    void aTaskAddedThroughTheClockworkRunsOnceAfterTheChange(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        String xml = Files.readString(Fixtures.of(mp, new File(DIR, "task-recompute-users-once.xml")).toPath())
                .replace(SINGLE, ADDED).replace("recompute users once", "recompute users added");
        for (int i = 0; i < 3; i++) {
            int before = Recorder.taskRuns(ADDED);
            PrismObject<TaskType> task = mp.prismContext().parserFor(xml).language("xml").parse();
            Run added = i % 2 == 0 ? mp.addObjectXml(xml) : mp.execute(task.createAddDelta());
            assertThat(Recorder.taskRuns(ADDED) - before).as("runs of the added task #" + i).isEqualTo(1);
            assertThat(added.mappings("auditor-description").list()).hasSize(perRun)
                    .allSatisfy(e -> assertThat(e.task()).isEqualTo("recompute users added"));
            assertThat(state(mp, ADDED)).as("state after run #" + i).isEqualTo("closed");
            mp.deleteObject(TaskType.class, ADDED);
        }
        assertThat(Recorder.taskRuns(SINGLE)).isEqualTo(singleRuns);
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);
    }

    /**
     * A recurring task added after the clock has moved is held too, its first run included (midPoint schedules it for
     * now): {@code runDueTasks} runs it, as a task that never ran is due. Resuming it (midPoint re-creates or resumes
     * its trigger) does not start it either.
     */
    @Test
    @Order(8)
    void aRecurringTaskAddedAfterTheClockHasMovedIsHeldToo(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        Internals.time(mp).advance("PT1M");
        int held = Recorder.heldRuns(OFTEN_LATE);
        Run imported = importOften(mp, OFTEN_LATE);
        assertThat(imported.mappings("auditor-description").list()).isEmpty();
        awaitHeldRun(OFTEN_LATE, held);
        assertThat(Recorder.taskRuns(OFTEN_LATE)).as("runs while held").isZero();

        Run due = Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(OFTEN_LATE)).as("runs after runDueTasks").isEqualTo(1);
        assertThat(due.mappings("auditor-description").list()).filteredOn(e -> !"recompute users".equals(e.task()))
                .hasSize(perRun);

        TaskManager tasks = mp.bean(TaskManager.class);
        tasks.suspendTasks(java.util.List.of(OFTEN_LATE), 5000, new OperationResult("suspend"));
        held = Recorder.heldRuns(OFTEN_LATE);
        tasks.resumeTasks(java.util.List.of(OFTEN_LATE), new OperationResult("resume"));
        awaitHeldRun(OFTEN_LATE, held);
        assertThat(Recorder.taskRuns(OFTEN_LATE)).as("runs after the resume").isEqualTo(1);
    }

    /**
     * A cron task (every day at 4:30) that never ran is due at its first cron time, not at once: a day's due tasks
     * before it do not run it, the ones after it do, once; then it waits for the next day. The test starts at midnight
     * whenever it runs (a run at 3:00 would otherwise have today's 4:30 due first).
     */
    @Test
    @Order(9)
    void aCronTaskIsDueAtItsCronTime(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        java.time.LocalDate tomorrow = t.clock().today().plusDays(1);
        t.clock().startAt(tomorrow.atStartOfDay());
        mp.importObjectXml(cronTask(mp));

        t.clock().stopAt(tomorrow.atTime(4, 29));
        assertThat(ofTask(Internals.time(mp).runDueTasks(), "recompute users at 4:30")).isEmpty();
        assertThat(Recorder.taskRuns(CRON)).isZero();

        t.clock().advance("PT2M");
        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(CRON)).isEqualTo(1);

        t.clock().advance("PT12H");
        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(CRON)).as("not again the same day").isEqualTo(1);
        t.clock().advance("PT12H");
        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(CRON)).as("the next day").isEqualTo(2);
    }

    /**
     * A cron task made after the clock has passed its time of the day (made at 5:00) first runs the next day at 4:30,
     * by midPoint's clock: Quartz takes the start of the trigger from the real time, a day or more before.
     */
    @Test
    @Order(10)
    void aCronTaskMadeAfterItsTimeRunsTheNextDay(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        java.time.LocalDate day = t.clock().today().plusDays(1);
        t.clock().startAt(day.atTime(12, 0));
        t.clock().advanceTo(at(day.plusDays(1), 5, 0));
        mp.importObjectXml(cronTask(mp));
        int before = Recorder.taskRuns(CRON);   // runs in this JVM, also of the test before

        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(CRON) - before).as("made after 4:30").isZero();
        t.clock().advanceTo(at(day.plusDays(2), 4, 29));
        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(CRON) - before).as("before the next 4:30").isZero();

        t.clock().advance("PT2M");
        Internals.time(mp).runDueTasks();
        assertThat(Recorder.taskRuns(CRON) - before).as("at the next 4:30").isEqualTo(1);
    }

    /**
     * Back to a checkpoint (a Kotest container's next test), a cron task made after the clock moved still counts from
     * when it was made: the reset re-creates triggers (4.4: every activity task's) at the real time.
     */
    @Test
    @Order(11)
    void aCheckpointKeepsWhenACronTriggerWasMade(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        java.time.LocalDate day = t.clock().today().plusDays(1);
        t.clock().startAt(day.atTime(12, 0));
        t.clock().advanceTo(at(day.plusDays(1), 5, 0));
        mp.importObjectXml(cronTask(mp));
        com.github.wadahiro.midpoint.tester.internal.Checkpoint checkpoint =
                com.github.wadahiro.midpoint.configtester.MidPointConfigTestLifecycle.checkpoint(t);
        try {
            // a first test moves the clock far on
            t.clock().advance("P5D");
            com.github.wadahiro.midpoint.configtester.MidPointConfigTestLifecycle.restore(t, checkpoint);
            // the next one, from the checkpoint, sees the task at its next 4:30
            int before = Recorder.taskRuns(CRON);
            t.clock().advanceTo(at(day.plusDays(2), 4, 29));
            Internals.time(mp).runDueTasks();
            assertThat(Recorder.taskRuns(CRON) - before).as("before the next 4:30").isZero();
            t.clock().advance("PT2M");
            Internals.time(mp).runDueTasks();
            assertThat(Recorder.taskRuns(CRON) - before).as("at the next 4:30").isEqualTo(1);
        } finally {
            com.github.wadahiro.midpoint.configtester.MidPointConfigTestLifecycle.release(t, checkpoint);
        }
    }

    private static java.time.Instant at(java.time.LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    /** The recompute task as a cron task, every day at 4:30. */
    private static String cronTask(MidPoint mp) throws java.io.IOException {
        return Files.readString(Fixtures.of(mp, new File(DIR, "task-recompute-users.xml")).toPath())
                .replace(RECURRING, CRON).replace("recompute users", "recompute users at 4:30")
                .replace("<interval>3600</interval>", "<cronLikePattern>0 30 4 ? * *</cronLikePattern>");
    }

    /**
     * Waits until Quartz has started a regular run of the task and the tester has held it (more than {@code before}
     * held runs), at most {@link #OFTEN_WAIT}, longer than the task's interval: the caller then checks that the task
     * did not run. Without a held run in that time the wait simply ends, as a fixed wait would.
     */
    private static void awaitHeldRun(String oid, int before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + OFTEN_WAIT;
        while (Recorder.heldRuns(oid) <= before && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * The evaluations of the run done by the task: the due tasks also run the deployment's own held tasks (midPoint's
     * cleanup and scanners), due as they never ran.
     */
    private static java.util.List<Evaluation> ofTask(Run run, String task) {
        return run.evaluations().stream().filter(e -> task.equals(e.task())).collect(java.util.stream.Collectors.toList());
    }

    /** The task that runs often, under this OID. */
    private static Run importOften(MidPoint mp, String oid) throws Exception {
        String xml = Files.readString(Fixtures.of(mp, new File(DIR, "task-recompute-users-often.xml")).toPath())
                .replace(OFTEN, oid);
        return mp.importObjectXml(xml);
    }

    /** The task's execution state ({@code executionState} since 4.4, {@code executionStatus} before), as text. */
    private static String state(MidPoint mp, String oid) {
        TaskType task = mp.find(TaskType.class, oid);
        String item = Lines.atLeast(mp, "4.4") ? "executionState" : "executionStatus";
        PrismProperty<?> state = task.asPrismObject().findProperty(new ItemName(SchemaConstants.NS_C, item));
        return state != null ? String.valueOf(state.getRealValue()).toLowerCase(java.util.Locale.ROOT) : null;
    }
}
