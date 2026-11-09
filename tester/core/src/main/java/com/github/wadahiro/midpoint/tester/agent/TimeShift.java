package com.github.wadahiro.midpoint.tester.agent;

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
 * Loaded by the bootstrap class loader (as {@link FileRemap}): the instrumented JDK classes call it. With no offset it
 * costs one volatile read. Not an API.
 */
public final class TimeShift {

    private static volatile long offset;

    private TimeShift() {
    }

    /** Sets the offset in milliseconds (0: the real time). */
    public static void set(long millis) {
        offset = millis;
    }

    public static long offset() {
        return offset;
    }

    /** The shifted wall clock, for callers that replace {@code System.currentTimeMillis()}. */
    public static long currentTimeMillis() {
        return System.currentTimeMillis() + offset;
    }

    /** The offset to add to a time just read from the JDK's wall clock: 0 when the reader is Quartz. */
    public static long shift() {
        long o = offset;
        if (o == 0) {
            return 0;
        }
        String caller = StackWalker.getInstance().walk(FIRST_CALLER);
        return caller != null && caller.startsWith("org.quartz.") ? 0 : o;
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
