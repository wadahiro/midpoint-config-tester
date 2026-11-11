package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.common.Clock;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskListener;
import com.evolveum.midpoint.task.api.TaskRunResult;

/**
 * Tells the {@link Recorder} which task a thread runs: called on the task's own thread when its handler starts and
 * finishes, so evaluations there are marked with the task and drivers can wait for running tasks; and of each run
 * (handler cycle) that starts, with midPoint's clock then.
 */
final class TaskThreadListener implements TaskListener {

    private final Clock clock;

    TaskThreadListener(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void onTaskStart(Task task) {
        Recorder.taskRunStarted(task.getOid(), clock.currentTimeMillis(), sleepsBetweenRuns(task));
    }

    @Override
    public void onTaskFinish(Task task, TaskRunResult runResult) {
        Recorder.taskRunFinished(task.getOid(), sleepsBetweenRuns(task));
    }

    /**
     * A recurring tightly bound task: its thread sleeps between runs. Not a single-run task, which 4.0 binds tightly
     * by default ({@code TaskQuartzImpl.DEFAULT_BINDING_TYPE}): its thread ends after the run.
     */
    private static boolean sleepsBetweenRuns(Task task) {
        return task.isRecurring() && task.isTightlyBound();
    }

    @Override
    public void onTaskThreadStart(Task task, boolean isRecovering) {
        Recorder.taskThreadStarted(task.getName() != null ? task.getName().getOrig() : null, task.getOid(),
                task.getChannel());
    }

    @Override
    public void onTaskThreadFinish(Task task) {
        Recorder.taskThreadFinished();
    }
}
