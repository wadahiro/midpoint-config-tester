package com.github.wadahiro.midpoint.tester.internal;

import java.util.List;
import java.util.function.Supplier;

/**
 * Waits for midPoint's background work without polling it on a fixed tick: each change of the work (a task thread or
 * a run started or ended, a Quartz job executed: {@link #changed()}, called by the {@link Recorder} and Quartz's
 * listener) wakes the waits, which then look again at what is pending. A wait that sees no change looks again after
 * a while all the same: the work midPoint keeps in its repository (a task due now, a task's final state written after
 * its thread ended) has no event of its own.
 */
public final class BackgroundWork {

    /** How long a wait waits for a change while task threads run: their end is a change. */
    private static final long RUNNING_RECHECK_MILLIS = 200;
    /** How long it waits otherwise (a task waiting for Quartz to start it, a state still being written). */
    private static final long IDLE_RECHECK_MILLIS = 20;

    private static final Object LOCK = new Object();
    private static long changes;

    private BackgroundWork() {
    }

    /** The background work changed: wakes the waits. */
    public static void changed() {
        synchronized (LOCK) {
            changes++;
            LOCK.notifyAll();
        }
    }

    /** The number of changes so far, to wait for the next one ({@link #awaitChange}). */
    public static long changes() {
        synchronized (LOCK) {
            return changes;
        }
    }

    /** Waits until the work changed after {@code seen} or {@code millis} passed; whether it changed. */
    public static boolean awaitChange(long seen, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        synchronized (LOCK) {
            while (changes == seen) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return false;
                }
                LOCK.wait(left);
            }
            return true;
        }
    }

    /**
     * Until nothing is pending ({@code pendingWork} empty) or the timeout passed: what is pending then. The count of
     * changes is read before each look, so a change between the look and the wait is not missed.
     */
    public static List<String> await(Supplier<List<String>> pendingWork, long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        long waitStart = System.nanoTime();
        boolean waited = false;
        while (true) {
            long seen = changes();
            long t = Timing.start();
            List<String> pending = pendingWork.get();
            Timing.end("await.pendingWork (task search)", t);
            if (pending.isEmpty() || System.nanoTime() - deadline >= 0) {
                if (waited) {
                    Timing.end("await.total", waitStart);
                    long end = Recorder.lastTaskEnd;
                    if (end - waitStart > 0) {
                        // the latency left: from the last task's end until the wait saw nothing pending
                        Timing.end("await.afterLastTaskEnd", end);
                    }
                }
                return pending;
            }
            waited = true;
            try {
                long s = Timing.start();
                boolean changed = awaitChange(seen,
                        Recorder.busyTaskThreads() > 0 ? RUNNING_RECHECK_MILLIS : IDLE_RECHECK_MILLIS);
                Timing.end(changed ? "await.woken" : "await.recheck", s);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pending;
            }
        }
    }
}
