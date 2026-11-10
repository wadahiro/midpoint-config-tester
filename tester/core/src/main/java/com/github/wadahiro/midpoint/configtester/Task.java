package com.github.wadahiro.midpoint.configtester;

/** A task of the deployment ({@link MidPointConfigTester#task}), by name. */
public final class Task {

    private final MidPointConfigTester tester;
    private final String name;
    /** The items after which {@link #run()} stops the task (see {@link #stopAfter}); -1: it runs to its end. */
    private final long stopAfter;

    Task(MidPointConfigTester tester, String name) {
        this(tester, name, -1);
    }

    private Task(MidPointConfigTester tester, String name, long stopAfter) {
        this.tester = tester;
        this.name = name;
        this.stopAfter = stopAfter;
    }

    /**
     * This task, whose next {@link #run()} is stopped after the items, as an administrator's Suspend stops it in the
     * middle: midPoint's running task says it cannot go on ({@code canRun()} false) once its progress has reached the
     * number, so the handler or the script that checks it between items ends there and records what it did. The task
     * itself is left as it was (not suspended). {@code t.task("Import users").stopAfter(2).run()}.
     */
    public Task stopAfter(long items) {
        return new Task(tester, name, items);
    }

    /**
     * Runs the task now, as the GUI's "Run now", and waits for it and the work it starts; fails if the task fails. A
     * recurring task held by the manifest ({@code tasks.recurring: held}) runs this way, once.
     */
    public Task run() {
        if (stopAfter < 0) {
            tester.deployment().api().runTask(name);
            return this;
        }
        String oid = tester.deployment().api()
                .find(com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType.class, name).getOid();
        com.github.wadahiro.midpoint.tester.internal.TaskStop.stopAfter(oid, stopAfter);
        try {
            tester.deployment().api().runTask(name);
        } finally {
            com.github.wadahiro.midpoint.tester.internal.TaskStop.clear();
        }
        return new Task(tester, name);
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
