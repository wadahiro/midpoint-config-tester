package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The GUI's saves executed in the background (a form's progress panel) that a form stopped waiting for: still
 * running, they would go on into the database a reset restores (objects gone under them, their connections closed).
 * A reset or a restore waits for them first ({@link #await}); those that still run then are interrupted.
 */
public final class GuiSaves {

    private static final Set<Future<?>> RUNNING = ConcurrentHashMap.newKeySet();

    private GuiSaves() {
    }

    /** A save the form stopped waiting for while it still ran. */
    public static void running(Future<?> save) {
        RUNNING.add(save);
    }

    /**
     * Waits until the saves ended or the timeout passed, then interrupts those still running: how many it
     * interrupted.
     */
    static int await(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<Future<?>> saves = new ArrayList<>(RUNNING);
        int interrupted = 0;
        for (Future<?> save : saves) {
            try {
                save.get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // ended with a failure (its result was the page's), or still running
            }
            if (!save.isDone()) {
                save.cancel(true);
                interrupted++;
            }
            RUNNING.remove(save);
        }
        return interrupted;
    }
}
