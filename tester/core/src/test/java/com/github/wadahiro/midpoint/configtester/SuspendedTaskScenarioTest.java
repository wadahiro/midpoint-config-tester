package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.TaskManager;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;
import com.github.wadahiro.midpoint.tester.Run;
import com.github.wadahiro.midpoint.tester.internal.Recorder;

/**
 * {@code t.task(name).run()} of a suspended task fails, saying why it is suspended: a task the deployment ships
 * suspended (here suspended as an operator would) runs only after {@code t.task(name).resume()}, which itself does
 * not start this recurring task (the tester holds it).
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class SuspendedTaskScenarioTest {

    static final String RECONCILE_HR = "20000000-0000-0000-0000-00000000f001";

    @Test
    void aSuspendedTaskRunsOnceResumed(MidPointConfigTester t) throws Exception {
        t.midpoint().bean(TaskManager.class).suspendTasks(List.of(RECONCILE_HR), 5000, new OperationResult("suspend"));

        Run run = t.midpoint().attempt(() -> t.midpoint().runTask(RECONCILE_HR));
        assertThat(run.isSuccess()).isFalse();
        assertThat(run.failure()).hasMessageContaining("The task is suspended: resume it first");

        int runs = Recorder.taskRuns(RECONCILE_HR);
        t.task(Tasks.RECONCILE_HR).resume();
        assertThat(Recorder.taskRuns(RECONCILE_HR)).as("runs after the resume").isEqualTo(runs);
        t.task(Tasks.RECONCILE_HR).run();
        assertThat(Recorder.taskRuns(RECONCILE_HR)).as("runs after the run").isEqualTo(runs + 1);
    }
}
