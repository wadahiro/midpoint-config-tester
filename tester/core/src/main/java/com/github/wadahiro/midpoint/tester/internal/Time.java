package com.github.wadahiro.midpoint.tester.internal;

import java.time.Instant;

import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * The test's time ({@link Internals#time}, {@code MidPointConfigTester#clock()}) and the work that becomes due with time: the clock is shifted, then the
 * trigger scanner (deadlines and timed actions of work items, escalations), the validity scanner (activation dates) or
 * the recurring tasks due by then run as tasks. The reset after each test sets the clock back to the real time.
 *
 * <p>{@link #advance} moves midPoint's {@code Clock} (its test override: the scanners, the time constraints of mappings,
 * {@code basic.currentDateTime()}) and, by the same offset, the JVM's wall clock as midPoint and scripts read it:
 * {@code new Date()}, {@code Calendar.getInstance()}, the system {@code java.time} clock ({@code Instant.now()},
 * {@code LocalDate.now()}...), Groovy's {@code System.currentTimeMillis()} and prism's {@code basic.fromNow}. Not
 * moved: {@code System.nanoTime()}, {@code System.currentTimeMillis()} called from Java code, Quartz, the database's
 * {@code now()} and other processes ({@link #shiftsTheJvmClock()}).</p>
 *
 * <p>Recurring tasks fire on the real time, as in production, until the test first calls {@link #advance}. From then
 * until the reset after the test, their regular runs are held (loosely bound ones: their Quartz trigger starts
 * nothing): they run only through {@link #runDueTasks} or {@link MidPoint#runTask}, so a run between driver calls,
 * or one mixed into another call's {@link Run} (a short interval during {@link #runValidityScanner}), cannot
 * happen.</p>
 *
 * <pre>{@code
 * mp.time().advance("P6D");                 // past the stage duration of P5D
 * Run escalation = mp.time().runTriggerScanner();
 *
 * mp.time().advance("PT1H");                // an hourly task's next run
 * Run hourly = mp.time().runDueTasks();
 * }</pre>
 */
public interface Time {

    /** midPoint's current time (the real time plus the advances). */
    Instant now();

    /**
     * Moves the clock forward (cumulative), by an XML duration such as {@code P6D} or {@code PT2H}: midPoint's clock
     * and the JVM's wall clock (see above). Holds the regular runs of recurring tasks until the reset after the test,
     * also of tasks added or resumed later: a recurring task imported after this does not run in its import (it never
     * ran, so {@link #runDueTasks} runs it).
     */
    void advance(String duration);

    /** Back to the real time. Recurring tasks stay held until the reset after the test ({@link #advance}). */
    void reset();

    /**
     * Whether the JVM's wall clock moves with {@link #advance} ({@code new Date()}, {@code Instant.now()},
     * {@code Calendar.getInstance()}, Groovy's {@code System.currentTimeMillis()}...); false when the tester could not
     * instrument the JDK (no agent): then only midPoint's {@code Clock} moves (the reason is printed at start).
     */
    boolean shiftsTheJvmClock();

    /** Runs the trigger scanner once (a single-run task) and waits for it and what it starts. */
    Run runTriggerScanner();

    /** Runs the focus validity scanner once and waits for it. */
    Run runValidityScanner();

    /**
     * What midPoint's scheduler would start by now on midPoint's clock: every recurring task (interval or cron,
     * loosely bound) whose next run after its last one has come, each run once (in the order of their names) and
     * waited for ("Run now"). Quartz itself fires on the real time, and their regular runs are held once the clock has
     * moved ({@link #advance}): only this call (or {@link MidPoint#runTask}) starts them. Suspended tasks and tightly
     * bound ones (their thread sleeps until the next run; 4.0's default binding) are not run.
     */
    Run runDueTasks();
}
