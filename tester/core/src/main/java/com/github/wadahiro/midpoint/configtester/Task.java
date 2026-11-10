package com.github.wadahiro.midpoint.configtester;

import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.internal.Internals;

/** A task of the deployment ({@link MidPointConfigTester#task}), by name. */
public final class Task {

    private final MidPointConfigTester tester;
    private final String name;
    /** The items after which {@link #run()} stops the task (see {@link #stopAfter}); -1: it runs to its end. */
    private final long stopAfter;
    /** Whether {@link #run()} lets a run that failed on some of its objects pass (see {@link #allowPartialErrors}). */
    private final boolean partialErrorsAllowed;

    Task(MidPointConfigTester tester, String name) {
        this(tester, name, -1, false);
    }

    private Task(MidPointConfigTester tester, String name, long stopAfter, boolean partialErrorsAllowed) {
        this.tester = tester;
        this.name = name;
        this.stopAfter = stopAfter;
        this.partialErrorsAllowed = partialErrorsAllowed;
    }

    /**
     * This task, whose next {@link #run()} is stopped after the items, as an administrator's Suspend stops it in the
     * middle: midPoint's running task says it cannot go on ({@code canRun()} false) once its progress has reached the
     * number, so the handler or the script that checks it between items ends there and records what it did. The task
     * itself is left as it was (not suspended). {@code t.task("Import users").stopAfter(2).run()}.
     */
    public Task stopAfter(long items) {
        return new Task(tester, name, items, partialErrorsAllowed);
    }

    /**
     * This task, whose next {@link #run()} passes when the run failed on some of its objects ({@code PARTIAL_ERROR}:
     * a reconciliation that could not take one account, as tasks often do in production); a run that could not run at
     * all ({@code FATAL_ERROR}) still fails. Without it, {@link #run()} fails on either. What failed is in the logs
     * ({@link MidPointConfigTester#effectsOf}). {@code t.task("Reconcile HR").allowPartialErrors().run()}.
     */
    public Task allowPartialErrors() {
        return new Task(tester, name, stopAfter, true);
    }

    /**
     * Runs the task now, as the GUI's "Run now", and waits for it and the work it starts; fails if the task fails,
     * on all or (unless {@link #allowPartialErrors()}) some of its objects. A recurring task held by the manifest
     * ({@code tasks.recurring: held}) runs this way, once.
     */
    public Task run() {
        MidPoint mp = tester.deployment().api();
        if (stopAfter < 0) {
            Internals.allowingPartialErrors(mp, partialErrorsAllowed, () -> mp.runTask(name));
            return this;
        }
        String oid = mp.find(com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType.class, name).getOid();
        com.github.wadahiro.midpoint.tester.internal.TaskStop.stopAfter(oid, stopAfter);
        try {
            Internals.allowingPartialErrors(mp, partialErrorsAllowed, () -> mp.runTask(name));
        } finally {
            com.github.wadahiro.midpoint.tester.internal.TaskStop.clear();
        }
        return new Task(tester, name, -1, partialErrorsAllowed);
    }

    /**
     * Resumes the task, as the GUI's "Resume": a task the deployment ships suspended. A recurring task held by the
     * manifest still runs only by {@link #run()} or the due tasks of {@link MidPointConfigTester#clock()}.
     */
    public Task resume() {
        tester.deployment().api().resumeTask(name);
        return this;
    }

    public String name() {
        return name;
    }
}
