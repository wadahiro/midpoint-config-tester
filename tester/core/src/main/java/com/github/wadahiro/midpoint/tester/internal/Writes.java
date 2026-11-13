package com.github.wadahiro.midpoint.tester.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the writes that bypass midPoint's model to what a checkpoint restores ({@link Checkpoint}): the repository
 * written raw, the target systems, the mapped files, the stand-in databases, the clock, the HTTP stand-in's answers and
 * the mails the SMTP server refuses. With the driver calls and the task starts, they say whether a test changed midPoint
 * ({@link TesterRuntime#activityCount()}); reads are not counted.
 */
public final class Writes {

    private static final AtomicLong COUNT = new AtomicLong();

    private Writes() {
    }

    /** Called by every such write, also one that then fails. */
    public static void count() {
        COUNT.incrementAndGet();
    }

    /** How many such writes there were in this JVM. */
    static long total() {
        return COUNT.get();
    }
}
