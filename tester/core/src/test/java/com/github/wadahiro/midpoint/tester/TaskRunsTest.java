package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import com.github.wadahiro.midpoint.tester.internal.Recorder;

/**
 * How often tasks run: an imported task runs once in the import, a task added through the Clockwork once after the
 * change, {@code mp.runTask} runs it exactly once, a reset runs nothing (it restores the tasks and their schedules),
 * and a recurring task runs on its schedule by midPoint's clock with {@code mp.time().runDueTasks()}; once the clock
 * has moved, it no longer fires by itself until the reset, its first run included. The deployment's
 * {@code tasks.recurring: held} is the same hold from the load on ({@code HrLifecycleScenarioTest
 * .theHeldReconciliationRunsWhenDue}). The class runs first in the JVM
 * ({@code junit-platform.properties} orders classes by {@link Order}): a reset that ran a task leaked that run into the
 * first test class (4.4).
 */
@MidPointTest
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
    /** Longer than the interval of the task that runs often. */
    static final long OFTEN_WAIT = 3000;
    /** Users the recomputation processes: kate (Auditor), ext; the Auditor mapping is evaluated for kate. */
    static int perRun;

    /** The task runs counted at the end of the previous method (the reset in between must not add any). */
    static int recurringRuns;
    static int singleRuns;

    @BeforeAll
    static void configure(MidPoint mp) {
        mp.importObject(new File(DIR, "certified-users.xml"));
        int recurring = Recorder.taskRuns(RECURRING);
        int single = Recorder.taskRuns(SINGLE);

        // both are imported runnable: each runs once, in its import (a recurring one as it has never run)
        Run recurringImport = mp.importObject(Fixtures.of(mp, new File(DIR, "task-recompute-users.xml")));
        Run singleImport = mp.importObject(Fixtures.of(mp, new File(DIR, "task-recompute-users-once.xml")));

        assertThat(Recorder.taskRuns(RECURRING) - recurring).as("runs of the recurring task's import").isEqualTo(1);
        assertThat(Recorder.taskRuns(SINGLE) - single).as("runs of the single-run task's import").isEqualTo(1);
        perRun = recurringImport.mappings("auditor-description").list().size();
        assertThat(perRun).isPositive();
        assertThat(singleImport.mappings("auditor-description").list()).hasSize(perRun);
        recurringRuns = Recorder.taskRuns(RECURRING);
        singleRuns = Recorder.taskRuns(SINGLE);
    }

    @BeforeEach
    void nothingRanSinceThePreviousMethod() {
        assertThat(Recorder.taskRuns(RECURRING)).as("runs of the recurring task across the reset").isEqualTo(recurringRuns);
        assertThat(Recorder.taskRuns(SINGLE)).as("runs of the single-run task across the reset").isEqualTo(singleRuns);
    }

    @AfterEach
    void remember() {
        recurringRuns = Recorder.taskRuns(RECURRING);
        singleRuns = Recorder.taskRuns(SINGLE);
    }

    @Test
    @Order(1)
    void runTaskRunsARecurringTaskOnce(MidPoint mp) {
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
    void runTaskRunsASingleRunTaskOnce(MidPoint mp) {
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
    void deleteTheTasks(MidPoint mp) {
        // the reset brings them back (rows and Quartz jobs) without running them: checked before the next method
        mp.deleteObject(TaskType.class, RECURRING);
        mp.deleteObject(TaskType.class, SINGLE);
        assertThat(mp.object(TaskType.class, RECURRING)).isNull();
    }

    @Test
    @Order(4)
    void theResetRestoresTheScheduleOfTheLastRun(MidPoint mp) {
        // the next run is an hour after the run of the import, as at the snapshot: not now
        TaskType task = mp.object(TaskType.class, RECURRING);
        long lastRun = task.getLastRunStartTimestamp().toGregorianCalendar().getTimeInMillis();
        Long next = mp.bean(TaskManager.class).getNextRunStartTime(RECURRING, new OperationResult("next"));
        assertThat(next).isEqualTo(lastRun + 3600_000L);
        assertThat(mp.waitForTasks().evaluations()).isEmpty();
        mp.runTask("recompute users");
    }

    @Test
    @Order(5)
    void recurringTaskRunsOnItsScheduleByMidPointsClock(MidPoint mp) {
        // not due yet: nothing runs
        assertThat(mp.time().runDueTasks().evaluations()).isEmpty();
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);

        // an hour later the recurring task is due (the single-run one has run and is closed)
        mp.time().advance("PT1H");
        // the JVM's clock moved too, but the regular runs are held (aRecurringTaskAddedAfterTheClockHasMovedIsHeldToo)
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);
        Run due = mp.time().runDueTasks();
        assertThat(Recorder.taskRuns(RECURRING) - recurringRuns).isEqualTo(1);
        assertThat(Recorder.taskRuns(SINGLE)).isEqualTo(singleRuns);
        assertThat(due.mappings("auditor-description").list()).hasSize(perRun)
                .allSatisfy(e -> assertThat(e.task()).isEqualTo("recompute users"));

        // it ran at the moved clock: the next run is another hour later
        assertThat(mp.time().runDueTasks().evaluations()).isEmpty();
        mp.time().advance("PT30M");
        assertThat(mp.time().runDueTasks().evaluations()).isEmpty();
        mp.time().advance("PT30M");
        mp.time().runDueTasks();
        assertThat(Recorder.taskRuns(RECURRING) - recurringRuns).isEqualTo(2);
    }

    @Test
    @Order(6)
    void suspendedTaskIsNotDue(MidPoint mp) {
        mp.bean(TaskManager.class).suspendTasks(java.util.List.of(RECURRING), 5000, new OperationResult("suspend"));
        mp.time().advance("PT2H");
        assertThat(mp.time().runDueTasks().evaluations()).isEmpty();
        assertThat(Recorder.taskRuns(RECURRING)).isEqualTo(recurringRuns);
    }

    /**
     * A runnable task added through the Clockwork ({@code addObjectXml}, {@code execute}) runs once, after the change:
     * Quartz is held while the change writes the task, as in an import. A run that started while midPoint still wrote
     * the task lost its work state on 4.0 ("No work state in task") and the task was suspended.
     */
    @Test
    @Order(7)
    void aTaskAddedThroughTheClockworkRunsOnceAfterTheChange(MidPoint mp) throws Exception {
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
    void aRecurringTaskAddedAfterTheClockHasMovedIsHeldToo(MidPoint mp) throws Exception {
        mp.time().advance("PT1M");
        int held = Recorder.heldRuns(OFTEN_LATE);
        Run imported = importOften(mp, OFTEN_LATE);
        assertThat(imported.mappings("auditor-description").list()).isEmpty();
        awaitHeldRun(OFTEN_LATE, held);
        assertThat(Recorder.taskRuns(OFTEN_LATE)).as("runs while held").isZero();

        Run due = mp.time().runDueTasks();
        assertThat(Recorder.taskRuns(OFTEN_LATE)).as("runs after runDueTasks").isEqualTo(1);
        assertThat(due.mappings("auditor-description").list()).hasSize(perRun);

        TaskManager tasks = mp.bean(TaskManager.class);
        tasks.suspendTasks(java.util.List.of(OFTEN_LATE), 5000, new OperationResult("suspend"));
        held = Recorder.heldRuns(OFTEN_LATE);
        tasks.resumeTasks(java.util.List.of(OFTEN_LATE), new OperationResult("resume"));
        awaitHeldRun(OFTEN_LATE, held);
        assertThat(Recorder.taskRuns(OFTEN_LATE)).as("runs after the resume").isEqualTo(1);
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

    /** The task that runs often, under this OID. */
    private static Run importOften(MidPoint mp, String oid) throws Exception {
        String xml = Files.readString(Fixtures.of(mp, new File(DIR, "task-recompute-users-often.xml")).toPath())
                .replace(OFTEN, oid);
        return mp.importObjectXml(xml);
    }

    /** The task's execution state ({@code executionState} since 4.4, {@code executionStatus} before), as text. */
    private static String state(MidPoint mp, String oid) {
        TaskType task = mp.object(TaskType.class, oid);
        String item = Lines.atLeast(mp, "4.4") ? "executionState" : "executionStatus";
        PrismProperty<?> state = task.asPrismObject().findProperty(new ItemName(SchemaConstants.NS_C, item));
        return state != null ? String.valueOf(state.getRealValue()).toLowerCase(java.util.Locale.ROOT) : null;
    }
}
