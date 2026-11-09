package com.github.wadahiro.midpoint.tester.internal;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.task.api.Task;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Stops a task's run after N items ({@code s.task(name).stopAfter(n)}), as an administrator's Suspend stops it in the
 * middle: midPoint's running task answers {@code canRun()} false once N items are done, so a handler or a script that
 * checks it between items ends there. Done items are the task's progress (4.0's handlers, a script that sets it) or,
 * from 4.4 on, the items an activity has processed ({@code ItemProcessingGatekeeper}, which keeps them in the
 * activity's statistics). The advices are on {@code RunningTaskQuartzImpl.canRun()} and the gatekeeper, installed
 * with the tester's agent before midPoint loads them. A multi-threaded activity may do a few more items.
 */
public final class TaskStop {

    private static volatile String taskOid;
    private static volatile long after;
    private static volatile boolean stopped;
    /** The items activities have processed since {@link #stopAfter}. */
    private static final java.util.concurrent.atomic.AtomicLong items = new java.util.concurrent.atomic.AtomicLong();

    private TaskStop() {
    }

    /** From now on, the task's run stops once its progress reaches {@code items}. */
    public static void stopAfter(String oid, long items) {
        after = items;
        stopped = false;
        TaskStop.items.set(0);
        taskOid = oid;
    }

    /** No task is stopped any more; whether the last one was. */
    public static boolean clear() {
        taskOid = null;
        return stopped;
    }

    /** What {@code canRun()} answers: false for the task once it has done its items. Called by the advice. */
    public static boolean canRun(Object task, boolean canRun) {
        String oid = taskOid;
        if (!canRun || oid == null || !(task instanceof Task) || !oid.equals(((Task) task).getOid())) {
            return canRun;
        }
        if (Math.max(progress(task), items.get()) >= after) {
            stopped = true;
            return false;
        }
        return true;
    }

    /** An activity has processed an item. Called by the advice. */
    public static void itemDone() {
        if (taskOid != null) {
            items.incrementAndGet();
        }
    }

    /** The task's progress: {@code getProgress()} on 4.0 and 4.4, {@code getLegacyProgress()} from 4.8 on. */
    private static long progress(Object task) {
        for (String name : new String[] { "getLegacyProgress", "getProgress" }) {
            try {
                Object value = task.getClass().getMethod(name).invoke(task);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (ReflectiveOperationException e) {
                // not on this line
            }
        }
        throw new IllegalStateException("Cannot read the progress of " + task);
    }

    /** Instruments midPoint's running task; null on success, else why not. */
    static String install(Instrumentation instrumentation) {
        List<Throwable> errors = new ArrayList<>();
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader loader, net.bytebuddy.utility.JavaModule module,
                            boolean loaded, Throwable throwable) {
                        errors.add(throwable);
                    }
                })
                .ignore(none())
                .type(named("com.evolveum.midpoint.task.quartzimpl.RunningTaskQuartzImpl"))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(CanRunAdvice.class)
                        .on(named("canRun").and(takesArguments(0)))))
                // 4.4+: the activities' items (not in the task's progress)
                .type(named("com.evolveum.midpoint.repo.common.activity.run.processing.ItemProcessingGatekeeper"))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(ItemDoneAdvice.class)
                        .on(named("updateStatisticsInTasks"))))
                .installOn(instrumentation);
        return errors.isEmpty() ? null : "Cannot instrument midPoint's running task: " + errors.get(0);
    }

    static final class CanRunAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.This Object task, @Advice.Return(readOnly = false) boolean canRun) {
            canRun = TaskStop.canRun(task, canRun);
        }
    }

    static final class ItemDoneAdvice {
        @Advice.OnMethodExit
        static void exit() {
            TaskStop.itemDone();
        }
    }
}
