package com.github.wadahiro.midpoint.tester.agent;

import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The offset of the wall clock that the instrumented JDK time sources add ({@code mp.time().advance}): {@code new
 * Date()}, {@code Calendar.getInstance()}, {@code new GregorianCalendar()}, the system {@code java.time.Clock} (so
 * {@code Instant.now()}, {@code LocalDate.now()}, {@code ZonedDateTime.now()}...), and, through
 * {@link #currentTimeMillis()}, Groovy's {@code System.currentTimeMillis()} and prism's {@code XmlTypeConverter}.
 * Monotonic time ({@code System.nanoTime()}) is never shifted, and neither is {@code System.currentTimeMillis()}
 * called from Java (a native method of a class loaded before any agent: it cannot be redefined).
 *
 * Quartz keeps the real time ({@link #shift()} is 0 for its callers): its scheduler thread waits on
 * {@code System.currentTimeMillis()}, so triggers it made on a shifted {@code new Date()} ({@code startNow()}) would
 * not fire until the real time reached them. Recurring tasks go on firing on the real time, so after an advance the
 * tester holds their regular runs until the reset ({@code TaskSchedules}): {@code runDueTasks} runs what has become
 * due by the shifted clock.
 *
 * A stopped clock ({@link #stop}) says the same instant until it is set again: the instrumented sources return it
 * as it is, to the millisecond.
 *
 * Loaded by the bootstrap class loader (as {@link FileRemap}): the instrumented JDK classes call it. With no offset it
 * costs two volatile reads. Not an API.
 */
public final class TimeShift {

    private static final long RUNNING = Long.MIN_VALUE;

    private static volatile long offset;

    private static volatile long stoppedAt = RUNNING;

    private TimeShift() {
    }

    /** Sets the offset in milliseconds (0: the real time); the clock runs. */
    public static void set(long millis) {
        stoppedAt = RUNNING;
        offset = millis;
    }

    /** Stops the clock at the instant (epoch milliseconds) until the next {@link #set} or {@code stop}. */
    public static void stop(long millis) {
        offset = 0;
        stoppedAt = millis;
    }

    public static long offset() {
        return offset;
    }

    /** The shifted wall clock, for callers that replace {@code System.currentTimeMillis()}. */
    public static long currentTimeMillis() {
        long stopped = stoppedAt;
        return stopped != RUNNING ? stopped : System.currentTimeMillis() + offset;
    }

    /** The offset to add to a time just read from the JDK's wall clock: 0 when the reader is Quartz. */
    public static long shift() {
        long stopped = stoppedAt;
        long o = stopped != RUNNING ? stopped - System.currentTimeMillis() : offset;
        if (o == 0) {
            return 0;
        }
        return quartz() ? 0 : o;
    }

    /** A time just read from the JDK's wall clock (epoch milliseconds), shifted: as it is for Quartz. */
    public static long millis(long real) {
        long stopped = stoppedAt;
        long o = offset;
        if (stopped == RUNNING && o == 0 || quartz()) {
            return real;
        }
        return stopped != RUNNING ? stopped : real + o;
    }

    /** An instant just read from the JDK's wall clock, shifted: as it is for Quartz. */
    public static Instant instant(Instant real) {
        long stopped = stoppedAt;
        long o = offset;
        if (stopped == RUNNING && o == 0 || quartz()) {
            return real;
        }
        return stopped != RUNNING ? Instant.ofEpochMilli(stopped) : real.plusMillis(o);
    }

    private static boolean quartz() {
        String caller = StackWalker.getInstance().walk(FIRST_CALLER);
        return caller != null && caller.startsWith("org.quartz.");
    }

    private static final Function<Stream<StackWalker.StackFrame>, String> FIRST_CALLER = new FirstCaller();

    /** The class name of the first frame outside the JDK and this class. */
    private static final class FirstCaller implements Function<Stream<StackWalker.StackFrame>, String> {
        @Override
        public String apply(Stream<StackWalker.StackFrame> frames) {
            // no lambdas: this class is injected into the bootstrap class loader as raw bytes
            java.util.Iterator<StackWalker.StackFrame> it = frames.iterator();
            while (it.hasNext()) {
                String n = it.next().getClassName();
                if (!n.startsWith("java.") && !n.startsWith("jdk.") && !n.startsWith("sun.")
                        && !n.startsWith("com.github.wadahiro.midpoint.tester.agent.TimeShift")) {
                    return n;
                }
            }
            return null;
        }
    }
}
