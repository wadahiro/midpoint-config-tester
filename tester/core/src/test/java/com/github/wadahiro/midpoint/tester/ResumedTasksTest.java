package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.tester.internal.Recorder;

/**
 * A tightly bound recurring task is not held ({@code tasks.recurring: held} holds loosely bound ones): resumed, it runs
 * at once, as midPoint starts it. Its thread then sleeps until the next run: the driver calls do not wait for it, and
 * the reset after the test suspends it.
 */
@MidPointConfigTest(Fixtures.BARE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResumedTasksTest {

    static final String TIGHT = "10000000-0000-0000-0000-00000000f030";

    @Test
    @Order(1)
    void aTightlyBoundTaskRunsOnTheResume(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        String xml = Files.readString(Fixtures.of(mp, new File(Fixtures.dir("async"), "task-recompute-users-often.xml"))
                .toPath()).replace(TaskRunsTest.OFTEN, TIGHT).replace("recompute users often", "recompute users tightly")
                .replace(">runnable</", ">suspended</").replace("<binding>loose</binding>", "<binding>tight</binding>")
                .replace("<interval>2</interval>", "<interval>3600</interval>");
        mp.importObjectXml(xml);
        int before = Recorder.taskRuns(TIGHT);

        mp.resumeTask(TIGHT);
        assertThat(Recorder.taskRuns(TIGHT) - before).as("runs after the resume").isEqualTo(1);
        assertThat(Recorder.tightlyBoundTasks()).as("tightly bound threads after the run").containsExactly(TIGHT);
        mp.waitForTasks();
        assertThat(Recorder.taskRuns(TIGHT) - before).as("runs after the wait").isEqualTo(1);
    }

    @Test
    @Order(2)
    void theResetSuspendedTheTightlyBoundTask(MidPointConfigTester t) {
        assertThat(Recorder.tightlyBoundTasks()).isEmpty();
        assertThat(Recorder.runningTaskThreads()).isZero();
    }
}
