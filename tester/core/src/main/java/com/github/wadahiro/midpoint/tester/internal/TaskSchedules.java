package com.github.wadahiro.midpoint.tester.internal;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.listeners.TriggerListenerSupport;

/**
 * midPoint's Quartz scheduler (an in-memory job store) across a reset, and the recurring tasks due by midPoint's clock.
 *
 * <p>The reset restores the task rows of the database but not Quartz, whose jobs midPoint brings in line with
 * {@code TaskManager.synchronizeTasks}. That re-creates the standard trigger of a ready task whenever it is missing or
 * looks different, and midPoint builds a recurring task's trigger to start now: the task would run at once, inside the
 * reset, against the database just restored and the target systems of the previous test, and its run would end in the
 * next test's driver calls. 4.0 and 4.4 re-create it on every synchronization for a task without a handler URI (all
 * activity tasks of 4.4): their trigger comparison takes two missing handler URIs as different (fixed in 4.8). The
 * reset therefore holds the scheduler ({@link #hold}), lets midPoint synchronize, puts every recurring trigger back on
 * the schedule of the restored task ({@link #rearm}: the next run one interval after the last run, as the task had at
 * the snapshot) and drops the extra one-shot triggers, then releases the scheduler ({@link #release}).
 *
 * <p>Quartz fires on the real time. Once a test has moved the clock ({@link #holdRegularRuns}, by
 * {@code Time.advance}), the regular runs of loosely bound recurring tasks are held until the reset
 * ({@link #releaseRegularRuns}): a trigger listener vetoes every firing of their standard trigger, so they run only
 * through {@code runDueTasks} or {@code runTask}. Those call midPoint's "Run now", which adds a run-now trigger (a cron
 * task: it fires as before) or, for an interval task, re-creates the standard trigger to start now (MID-1410, every
 * line): that one firing is let through ({@link #permitRegularRun}). A veto rather than pausing
 * the trigger: midPoint resumes a paused standard trigger of a ready task and re-creates it when the task is added,
 * resumed or changed ({@code TaskSynchronizer}), so a pause would not last; and a paused trigger means a suspended task
 * here ({@link #due}, {@link #rearm}). The vetoed trigger stays scheduled: it goes on to its next fire time (no misfire
 * builds up), and Quartz completes the vetoed firing, releasing the other triggers of the job (midPoint's job disallows
 * concurrent execution).</p>
 */
final class TaskSchedules {

    /** The job data key midPoint sets on the standard trigger of a loosely bound recurring task. */
    private static final String LOOSELY_BOUND_RECURRENT = "looselyBoundRecurrent";
    private static final String HOLD_LISTENER = "tester regular runs hold";

    /** Whether the regular runs of loosely bound recurring tasks are held (the clock has moved in this test). */
    private static volatile boolean regularRunsHeld;
    /** The tasks (OIDs) whose next firing of the standard trigger is a run now, not held. */
    private static final Map<String, Boolean> RUN_NOW = new ConcurrentHashMap<>();

    private TaskSchedules() {
    }

    /**
     * {@link Harness#awaitBackgroundWork}, then until Quartz has no job executing: the task listener reports a task
     * thread finished before midPoint's job executor has written the task's final execution state, so a thread count
     * of zero still leaves those writes (and their transaction) running. Returns what is pending after the timeout.
     */
    static List<String> awaitBackgroundWork(Harness harness, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        List<String> pending = harness.awaitBackgroundWork(timeoutMillis);
        if (!pending.isEmpty()) {
            return pending;
        }
        Scheduler scheduler = harness.quartzScheduler();
        while (true) {
            int jobs;
            try {
                jobs = scheduler.getCurrentlyExecutingJobs().size();
            } catch (SchedulerException e) {
                throw new IllegalStateException("Cannot list the executing Quartz jobs", e);
            }
            if (jobs == 0) {
                // a job that just ended may have scheduled more work (a next task, a waiting parent)
                return harness.awaitBackgroundWork(Math.max(0, (deadline - System.nanoTime()) / 1_000_000L));
            }
            if (System.nanoTime() - deadline >= 0) {
                return List.of(jobs + " Quartz job(s) executing");
            }
            try {
                long t = Timing.start();
                Thread.sleep(20);
                Timing.end("await.quartzJobSleep20", t);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of(jobs + " Quartz job(s) executing");
            }
        }
    }

    /** Stops Quartz from firing triggers (running jobs go on); the jobs and triggers stay. */
    static void hold(Scheduler scheduler) {
        try {
            scheduler.standby();
        } catch (SchedulerException e) {
            throw new IllegalStateException("Cannot put the Quartz scheduler in standby", e);
        }
    }

    static void release(Scheduler scheduler) {
        try {
            scheduler.start();
        } catch (SchedulerException e) {
            throw new IllegalStateException("Cannot restart the Quartz scheduler", e);
        }
    }

    /** Adds the listener that vetoes the held regular runs ({@link #holdRegularRuns}) to midPoint's scheduler. */
    static void installHold(Scheduler scheduler) {
        try {
            scheduler.getListenerManager().addTriggerListener(new TriggerListenerSupport() {
                @Override
                public String getName() {
                    return HOLD_LISTENER;
                }

                @Override
                public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
                    boolean veto = regularRunsHeld && isRegular(trigger)
                            && RUN_NOW.remove(trigger.getJobKey().getName()) == null;
                    if (veto) {
                        Recorder.regularRunHeld(trigger.getJobKey().getName());
                    }
                    return veto;
                }
            });
        } catch (SchedulerException e) {
            throw new IllegalStateException("Cannot add a trigger listener to Quartz", e);
        }
    }

    /**
     * From now until {@link #releaseRegularRuns}, the standard triggers of loosely bound recurring tasks start nothing
     * (except a run now, {@link #permitRegularRun}), also those of tasks added or resumed later; run-now triggers and
     * other tasks are not affected.
     */
    static void holdRegularRuns() {
        regularRunsHeld = true;
    }

    /** Back to Quartz's own schedule (the reset, with the scheduler held and the triggers re-armed). */
    static void releaseRegularRuns() {
        regularRunsHeld = false;
        RUN_NOW.clear();
    }

    /**
     * Lets the next firing of the task's standard trigger through while held: called before "Run now", which may
     * re-create that trigger to start now. Whichever trigger starts the task first is the run asked for (midPoint's job
     * disallows concurrent execution, so a second one waits and is held); {@link #endRegularRunPermit} once it has
     * started.
     */
    static void permitRegularRun(String oid) {
        RUN_NOW.put(oid, Boolean.TRUE);
    }

    static void endRegularRunPermit(String oid) {
        RUN_NOW.remove(oid);
    }

    /**
     * Whether the task's regular runs are held: null if not (regular runs not held, or the task has no standard trigger
     * of a loosely bound recurring task); otherwise whether another trigger of the task (run now, rescheduled) fires by
     * {@code until}.
     */
    static Boolean heldTaskStarts(Scheduler scheduler, String oid, long until) {
        if (!regularRunsHeld || RUN_NOW.containsKey(oid)) {
            return null;
        }
        try {
            Trigger standard = scheduler.getTrigger(new TriggerKey(oid));
            if (standard == null || !isRegular(standard)) {
                return null;
            }
            for (Trigger trigger : scheduler.getTriggersOfJob(new JobKey(oid))) {
                Date next = trigger.getNextFireTime();
                if (!isRegular(trigger) && next != null && next.getTime() <= until) {
                    return true;
                }
            }
            return false;
        } catch (SchedulerException e) {
            throw new IllegalStateException("Cannot read the Quartz triggers of task " + oid, e);
        }
    }

    /** The standard trigger of a loosely bound recurring task (its key is the job's name, the task OID). */
    private static boolean isRegular(Trigger trigger) {
        return trigger.getKey().equals(new TriggerKey(trigger.getJobKey().getName()))
                && trigger.getJobDataMap().getBoolean(LOOSELY_BOUND_RECURRENT);
    }

    /**
     * After the synchronization: every task's extra triggers (run now, rescheduled later) are dropped; the standard
     * trigger of a recurring task that has run starts again at its next regular time after its last run (an interval
     * after it, or the next cron time). A recurring task that never ran keeps the trigger midPoint built (due now).
     * Paused triggers (suspended tasks) are left alone. Held regular runs ({@link #holdRegularRuns}) do not pause the
     * trigger: it is re-armed too, and the reset releases the hold afterwards.
     *
     * @param lastRunStart the task's last run start (repository, real time) by OID; null if it never ran or is gone
     */
    static void rearm(Scheduler scheduler, Function<String, Long> lastRunStart) {
        try {
            for (JobKey job : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JobKey.DEFAULT_GROUP))) {
                TriggerKey standard = new TriggerKey(job.getName());
                for (Trigger trigger : scheduler.getTriggersOfJob(job)) {
                    if (!trigger.getKey().equals(standard)) {
                        scheduler.unscheduleJob(trigger.getKey());
                    }
                }
                Trigger trigger = scheduler.getTrigger(standard);
                if (trigger == null || !trigger.getJobDataMap().getBoolean(LOOSELY_BOUND_RECURRENT)
                        || scheduler.getTriggerState(standard) != Trigger.TriggerState.NORMAL) {
                    continue;
                }
                Long last = lastRunStart.apply(job.getName());
                if (last == null) {
                    continue;
                }
                Long next = nextRun(trigger, last);
                if (next == null || trigger.getEndTime() != null && trigger.getEndTime().getTime() <= next) {
                    continue;
                }
                scheduler.rescheduleJob(standard, trigger.getTriggerBuilder()
                        .executionGroup(trigger.getExecutionGroup())   // not copied by the builder (midPoint's Quartz)
                        .startAt(new Date(next))
                        .build());
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("Cannot re-arm the Quartz triggers after the reset", e);
        }
    }

    /**
     * The OIDs of the loosely bound recurring tasks due at {@code now} by their schedule (interval or cron): never
     * run, or their next regular time after {@code lastRunAt} (both in midPoint's clock) has come. Suspended tasks
     * (paused triggers) and tightly bound ones (no repeating trigger) are not due; held regular runs
     * ({@link #holdRegularRuns}) do not pause the trigger, so those tasks are due as usual.
     */
    static List<String> due(Scheduler scheduler, Function<String, Long> lastRunAt, long now) {
        List<String> due = new ArrayList<>();
        try {
            for (JobKey job : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(JobKey.DEFAULT_GROUP))) {
                TriggerKey standard = new TriggerKey(job.getName());
                Trigger trigger = scheduler.getTrigger(standard);
                if (trigger == null || !trigger.getJobDataMap().getBoolean(LOOSELY_BOUND_RECURRENT)
                        || scheduler.getTriggerState(standard) == Trigger.TriggerState.PAUSED) {
                    continue;
                }
                Long last = lastRunAt.apply(job.getName());
                Long next = last != null ? nextRun(trigger, last) : Long.valueOf(Long.MIN_VALUE);
                if (next != null && next <= now) {
                    due.add(job.getName());
                }
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("Cannot read the Quartz triggers", e);
        }
        return due;
    }

    /** The regular run after {@code last}: an interval later, or the next cron time; null for other triggers. */
    private static Long nextRun(Trigger trigger, long last) {
        if (trigger instanceof SimpleTrigger && ((SimpleTrigger) trigger).getRepeatInterval() > 0) {
            return last + ((SimpleTrigger) trigger).getRepeatInterval();
        }
        if (trigger instanceof CronTrigger) {
            try {
                CronExpression cron = new CronExpression(((CronTrigger) trigger).getCronExpression());
                cron.setTimeZone(((CronTrigger) trigger).getTimeZone());
                Date next = cron.getNextValidTimeAfter(new Date(last));
                return next != null ? next.getTime() : null;
            } catch (ParseException e) {
                return null;
            }
        }
        return null;
    }
}
