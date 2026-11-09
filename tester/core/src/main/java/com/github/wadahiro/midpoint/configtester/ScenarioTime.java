package com.github.wadahiro.midpoint.configtester;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * midPoint's clock in a scenario ({@link Scenario#time()}). Moving it moves midPoint's {@code Clock} and, by the same
 * offset, the JVM's wall clock as midPoint and scripts read it ({@code new Date()}, {@code Instant.now()},
 * {@code basic.currentDateTime()}...). It only moves forward; each test starts at the real time.
 *
 * <p>Nothing runs by itself when the clock moves: {@link #runDue()} runs what midPoint's scheduler would have run by
 * then. From the first move on, recurring tasks run only through {@link #runDue()} or {@link ScenarioTask#run()}.</p>
 *
 * <pre>{@code
 * s.time().advanceTo(LocalDate.of(2030, 4, 1)).runDue();   // the start date has come
 * s.time().advance("P30D").runDue();                        // thirty days later
 * }</pre>
 */
public final class ScenarioTime {

    private final Scenario scenario;

    ScenarioTime(Scenario scenario) {
        this.scenario = scenario;
    }

    /** midPoint's current time. */
    public Instant now() {
        return scenario.deployment().now();
    }

    /** midPoint's current date in the JVM's time zone. */
    public LocalDate today() {
        return now().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** Moves the clock forward by an XML (ISO 8601) duration, e.g. {@code "P30D"} or {@code "PT2H"}. */
    public ScenarioTime advance(String duration) {
        scenario.deployment().api().time().advance(duration);
        return this;
    }

    public ScenarioTime advance(Duration duration) {
        return advanceTo(now().plus(duration));
    }

    /** Moves the clock forward to the instant; fails if it is not in the future. */
    public ScenarioTime advanceTo(Instant instant) {
        scenario.deployment().advanceTo(instant);
        return this;
    }

    /** Moves the clock forward to the start of the day (in the JVM's time zone); fails if it is not in the future. */
    public ScenarioTime advanceTo(LocalDate date) {
        return advanceTo(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Runs what has become due by the clock, as midPoint's scheduler would have: the focus validity scanner
     * (activation dates passed), the trigger scanner (time constraints of mappings, delayed deletes, escalations), then
     * every recurring task of the deployment whose next run has come (each once, by name; a held task that never ran is
     * due). Waits for all of it.
     */
    public ScenarioTime runDue() {
        runValidityScanner();
        runTriggerScanner();
        scenario.deployment().api().time().runDueTasks();
        return this;
    }

    /** Runs the focus validity scanner once and waits for it. */
    public ScenarioTime runValidityScanner() {
        scenario.deployment().api().time().runValidityScanner();
        return this;
    }

    /** Runs the trigger scanner once and waits for it and what it starts. */
    public ScenarioTime runTriggerScanner() {
        scenario.deployment().api().time().runTriggerScanner();
        return this;
    }
}
