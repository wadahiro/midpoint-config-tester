package com.github.wadahiro.midpoint.configtester;

/** A task of the deployment ({@link Scenario#task}), by name. */
public final class ScenarioTask {

    private final Scenario scenario;
    private final String name;

    ScenarioTask(Scenario scenario, String name) {
        this.scenario = scenario;
        this.name = name;
    }

    /**
     * Runs the task now, as the GUI's "Run now", and waits for it and the work it starts; fails if the task fails. A
     * recurring task held by the manifest ({@code tasks.recurring: held}) runs this way, once.
     */
    public ScenarioTask run() {
        scenario.deployment().api().runTask(name);
        return this;
    }

    /**
     * Resumes the task, as the GUI's "Resume": a task the deployment ships suspended. A recurring task held by the
     * manifest still runs only by {@link #run()} or the due tasks of {@link Scenario#time()}.
     */
    public ScenarioTask resume() {
        scenario.deployment().api().resumeTask(name);
        return this;
    }

    public String name() {
        return name;
    }
}
