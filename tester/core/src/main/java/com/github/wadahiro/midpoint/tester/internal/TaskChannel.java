package com.github.wadahiro.midpoint.tester.internal;

/**
 * The channel the tester's driver calls run in ({@code MidPointConfigTester.inChannel}): the tasks they create carry it, and
 * midPoint's model context takes its channel from the task. Null: the tasks' default (midPoint's user channel).
 */
public final class TaskChannel {

    private static volatile String current;

    private TaskChannel() {
    }

    public static String current() {
        return current;
    }

    /** Sets the channel; returns the one before (to set it back). */
    public static String use(String channel) {
        String before = current;
        current = channel;
        return before;
    }
}
