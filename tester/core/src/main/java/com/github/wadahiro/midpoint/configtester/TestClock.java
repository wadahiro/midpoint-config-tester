package com.github.wadahiro.midpoint.configtester;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.github.wadahiro.midpoint.tester.internal.Internals;

/**
 * midPoint's clock in a scenario ({@link MidPointConfigTester#clock()}). Moving it moves midPoint's {@code Clock} and, by the same
 * offset, the JVM's wall clock as midPoint and scripts read it ({@code new Date()}, {@code Instant.now()},
 * {@code basic.currentDateTime()}...). It only moves forward; each test starts at the real time, or at a fixed one ({@link #startAt}). It runs on from where
 * it was moved to, unless it is stopped ({@link #stopAt}): then it says one instant, to the millisecond, for what
 * depends on the exact time (a script's time window and its bounds).
 *
 * <p>Nothing runs by itself when the clock moves: {@link #runDue()} runs what midPoint's scheduler would have run by
 * then. From the first move on, recurring tasks run only through {@link #runDue()} or {@link Task#run()}.</p>
 *
 * <pre>{@code
 * t.clock().startAt(t.clock().today().plusDays(1).atTime(12, 0));   // tomorrow at noon, whenever the test runs
 * t.clock().advanceTo(LocalDate.of(2030, 4, 1)).runDue();   // the start date has come
 * t.clock().advance("P30D").runDue();                        // thirty days later
 * t.clock().stopAt(LocalDate.of(2030, 4, 1).atTime(22, 0));  // exactly 22:00 from now on
 * }</pre>
 */
public final class TestClock {

    private final MidPointConfigTester tester;

    TestClock(MidPointConfigTester tester) {
        this.tester = tester;
    }

    /** midPoint's current time. */
    public Instant now() {
        return tester.deployment().now();
    }

    /** midPoint's current date in the JVM's time zone. */
    public LocalDate today() {
        return now().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** Moves the clock forward by an XML (ISO 8601) duration, e.g. {@code "P30D"} or {@code "PT2H"}. */
    public TestClock advance(String duration) {
        Internals.time(tester.deployment().api()).advance(duration);
        return this;
    }

    public TestClock advance(Duration duration) {
        return advanceTo(now().plus(duration));
    }

    /** Moves the clock forward to the instant; fails if it is not in the future. */
    public TestClock advanceTo(Instant instant) {
        tester.deployment().advanceTo(instant);
        return this;
    }

    /** Moves the clock forward to the start of the day (in the JVM's time zone); fails if it is not in the future. */
    public TestClock advanceTo(LocalDate date) {
        return advanceTo(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Starts the test at the instant: stops the clock there ({@link #stopAt(Instant)}), and the scheduled tasks count
     * from it as if the test began then; a cron time between the real time and the instant is no run the test missed.
     * What the test checks then does not depend on when it runs (a task at 23:50 runs or not on the same day). Call it
     * first, before {@link #runDue()}; fails if the instant is not in the future.
     */
    public TestClock startAt(Instant instant) {
        tester.deployment().startAt(instant);
        return this;
    }

    /** {@link #startAt(Instant)} at the date and time in the JVM's time zone. */
    public TestClock startAt(LocalDateTime dateTime) {
        return startAt(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Stops the clock at the instant: midPoint's clock and the JVM's wall clock say it, to the millisecond, until the
     * test ends; {@link #advance} moves the stopped clock. Fails if the instant is not in the future.
     */
    public TestClock stopAt(Instant instant) {
        tester.deployment().stopAt(instant);
        return this;
    }

    /** Stops the clock at the date and time in the JVM's time zone (what {@code LocalTime.now()} then says). */
    public TestClock stopAt(LocalDateTime dateTime) {
        return stopAt(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Runs what has become due by the clock, as midPoint's scheduler would have: the focus validity scanner
     * (activation dates passed), the trigger scanner (time constraints of mappings, delayed deletes, escalations), then
     * every recurring task of the deployment whose next run has come (each once, by name; a held task that never ran is
     * due). Waits for all of it.
     */
    public TestClock runDue() {
        runValidityScanner();
        runTriggerScanner();
        Internals.time(tester.deployment().api()).runDueTasks();
        return this;
    }

    /** Runs the focus validity scanner once and waits for it. */
    public TestClock runValidityScanner() {
        Internals.time(tester.deployment().api()).runValidityScanner();
        return this;
    }

    /** Runs the trigger scanner once and waits for it and what it starts. */
    public TestClock runTriggerScanner() {
        Internals.time(tester.deployment().api()).runTriggerScanner();
        return this;
    }
}
