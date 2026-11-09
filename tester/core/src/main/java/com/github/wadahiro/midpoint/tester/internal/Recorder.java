package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.wadahiro.midpoint.tester.Evaluation;

/**
 * Attributes evaluations to what encloses them: the line's hooks call {@link #begin} when a mapping or an expression
 * evaluator starts and {@link #end} when it returns, so a thread-local stack says which mapping (and which expression
 * in it) a nested evaluation runs in, e.g. the filter of an {@code assignmentTargetSearch}. No description is parsed.
 *
 * Only evaluations during a driver call are recorded ({@link #start} / {@link #stop}); they are recorded on whatever
 * thread they run (the stack is per thread, the recording is not). A driver call ends only when the background tasks it
 * started have finished (see {@code Harness.awaitBackgroundWork}), so what midPoint runs on task threads (approved
 * changes, certification and report tasks) is part of the call's run; each such record names its task
 * ({@link #taskThreadStarted}).
 */
public final class Recorder {

    private static final ThreadLocal<Deque<Record>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    /** The task whose handler this thread runs (name, OID); null on other threads. */
    private static final ThreadLocal<String[]> TASK = new ThreadLocal<>();
    /** The configuration element a driver evaluates on this thread ({@link #scope}); null when none. */
    private static final ThreadLocal<String[]> SCOPE = new ThreadLocal<>();
    private static final AtomicInteger TASK_THREADS = new AtomicInteger();
    /** Task threads and runs started since the JVM started ({@link #backgroundStarts}). */
    private static final java.util.concurrent.atomic.AtomicLong BACKGROUND_STARTS = new java.util.concurrent.atomic.AtomicLong();
    /** Regular runs held (vetoed by {@code TaskSchedules}) per task OID since the JVM started. */
    private static final Map<String, AtomicInteger> HELD_RUNS = new ConcurrentHashMap<>();
    /** Runs (handler cycles) started per task OID since the JVM started. */
    private static final Map<String, AtomicInteger> TASK_RUNS = new ConcurrentHashMap<>();
    /** Per task OID: when its runs started, real time to midPoint's clock (which tests may have moved). */
    private static final Map<String, NavigableMap<Long, Long>> TASK_RUN_CLOCK = new ConcurrentHashMap<>();
    private static volatile Recording active;

    private Recorder() {
    }

    static Recording start(Recording.Locator locator) {
        Recording recording = new Recording(locator);
        active = recording;
        return recording;
    }

    static void stop(Recording recording) {
        if (active == recording) {
            active = null;
        }
    }

    /**
     * Starts the record of an evaluation, nested in the one this thread is in; null when nothing is being recorded.
     * Must be followed by {@link #end} (in a {@code finally}).
     */
    public static Record begin(Evaluation.Kind kind) {
        Recording recording = active;
        if (recording == null) {
            return null;
        }
        Deque<Record> stack = STACK.get();
        Record parent = stack.peek();
        Record record = recording.add(kind, parent != null && parent.recording == recording ? parent : null);
        String[] task = TASK.get();
        if (task != null) {
            record.taskName = task[0];
            record.taskOid = task[1];
        }
        record.channel = channel(task);
        record.scope = SCOPE.get();
        stack.push(record);
        return record;
    }

    /** The channel of the evaluation: the operation's (lens context, current task), else its task thread's. */
    private static String channel(String[] task) {
        try {
            String channel = LineSupport.currentChannel();
            if (channel != null) {
                return channel;
            }
        } catch (RuntimeException | LinkageError e) {
            // the recorder must never change what midPoint does
        }
        return task != null && task.length > 2 ? task[2] : null;
    }

    /**
     * Runs {@code action} with its evaluations on this thread marked as being in the element {@code <element>} with
     * {@code <keyChild>value</keyChild>} of the object with this OID (e.g. the widget a dashboard driver evaluates):
     * identical expressions elsewhere are then not taken for them. For drivers whose evaluations midPoint does not
     * place itself. A null OID or element stands for any.
     */
    public static <T> T inScope(String oid, String element, String keyChild, String value,
            java.util.concurrent.Callable<T> action) throws Exception {
        String[] previous = SCOPE.get();
        SCOPE.set(new String[] { oid, element, keyChild, value });
        try {
            return action.call();
        } finally {
            if (previous != null) {
                SCOPE.set(previous);
            } else {
                SCOPE.remove();
            }
        }
    }

    /**
     * Runs {@code action} in the scope of a named notifier or event handler of midPoint's notification configuration
     * (its configuration bean, e.g. a {@code SimpleUserNotifierType}): its expression filters are then told from
     * identical filters of other notifiers, which midPoint does not place itself. In any object, by the element of the
     * bean (when it is in a prism structure) and its {@code name}; without a name, {@code action} runs as it is.
     */
    public static <T> T inNotifierScope(Object handler, java.util.concurrent.Callable<T> action) throws Exception {
        String name = null;
        String element = null;
        try {
            Object value = handler.getClass().getMethod("getName").invoke(handler);
            name = value instanceof String ? (String) value : null;
            if (handler instanceof com.evolveum.midpoint.prism.Containerable) {
                Object parent = ((com.evolveum.midpoint.prism.Containerable) handler).asPrismContainerValue()
                        .getParent();
                if (parent instanceof com.evolveum.midpoint.prism.Itemable) {
                    element = ((com.evolveum.midpoint.prism.Itemable) parent).getElementName().getLocalPart();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // the recorder must never change what midPoint does: no scope
            name = null;
        }
        return name == null ? action.call() : inScope(null, element, "name", name, action);
    }

    /** The evaluation this thread is in (the top of its stack), or null. */
    public static Record current() {
        return STACK.get().peek();
    }

    public static void end(Record record) {
        if (record == null) {
            return;
        }
        Deque<Record> stack = STACK.get();
        // tolerate an unbalanced stack (a hook that failed half-way): drop everything above the record
        while (!stack.isEmpty()) {
            if (stack.pop() == record) {
                break;
            }
        }
    }

    // ---- task threads (called by the line's task listener) -------------------------------------------------------

    /** A task's handler starts on this thread: its evaluations are marked with the task. */
    public static void taskThreadStarted(String name, String oid) {
        taskThreadStarted(name, oid, null);
    }

    /** As {@link #taskThreadStarted(String, String)}, with the task's channel URI. */
    public static void taskThreadStarted(String name, String oid, String channel) {
        TASK_THREADS.incrementAndGet();
        BACKGROUND_STARTS.incrementAndGet();
        TASK.set(new String[] { name, oid, channel });
    }

    public static void taskThreadFinished() {
        if (TASK.get() != null) {
            TASK.remove();
            TASK_THREADS.decrementAndGet();
        }
    }

    /**
     * A run (one handler cycle) of the task starts, at {@code clockMillis} by midPoint's clock: counted for
     * {@link #taskRuns}, and kept for {@link #clockAt}.
     */
    public static void taskRunStarted(String oid, long clockMillis) {
        BACKGROUND_STARTS.incrementAndGet();
        if (oid != null) {
            long real = System.currentTimeMillis();
            NavigableMap<Long, Long> starts = TASK_RUN_CLOCK.computeIfAbsent(oid, k -> new TreeMap<>());
            synchronized (starts) {
                starts.put(real, clockMillis);
            }
            TASK_RUNS.computeIfAbsent(oid, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /**
     * midPoint's clock at a run start of the task given in real time (midPoint stores a task's last run start in real
     * time): the clock offset of the run that started last before it; the real time itself without one.
     */
    static long clockAt(String oid, long realMillis) {
        NavigableMap<Long, Long> starts = TASK_RUN_CLOCK.get(oid);
        if (starts == null) {
            return realMillis;
        }
        synchronized (starts) {
            Map.Entry<Long, Long> start = starts.floorEntry(realMillis);
            return start != null ? realMillis + start.getValue() - start.getKey() : realMillis;
        }
    }

    /** How many runs of the task (by OID) midPoint has started in this JVM. */
    public static int taskRuns(String oid) {
        AtomicInteger runs = TASK_RUNS.get(oid);
        return runs != null ? runs.get() : 0;
    }

    /** A regular run of the task (by OID) was due by Quartz and held ({@code TaskSchedules.holdRegularRuns}). */
    static void regularRunHeld(String oid) {
        HELD_RUNS.computeIfAbsent(oid, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * How many regular runs of the task (by OID) Quartz has started and the tester held since the JVM started (the
     * clock had moved, see {@code Time.advance}): such a firing started nothing.
     */
    public static int heldRuns(String oid) {
        AtomicInteger runs = HELD_RUNS.get(oid);
        return runs != null ? runs.get() : 0;
    }

    /** How many task threads and task runs have started in this JVM: changes whenever midPoint runs a task. */
    static long backgroundStarts() {
        return BACKGROUND_STARTS.get();
    }

    /** How many task threads are running now (started, not finished). */
    public static int runningTaskThreads() {
        return TASK_THREADS.get();
    }
}
