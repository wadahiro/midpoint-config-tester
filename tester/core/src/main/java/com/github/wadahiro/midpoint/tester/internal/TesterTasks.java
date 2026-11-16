package com.github.wadahiro.midpoint.tester.internal;

/**
 * The names of the tasks the tester creates to run something (a scanner, a report, a resource's task, the imports):
 * {@code "trigger scanner (tester)"}. They are not effects of a block ({@code Effects}), as the tester's own calls
 * are not: every place that creates one names it here.
 */
public final class TesterTasks {

    private static final String SUFFIX = " (tester)";

    private TesterTasks() {
    }

    /** The name of a task the tester creates for this purpose. */
    public static String name(String purpose) {
        return purpose + SUFFIX;
    }

    /** Whether a task of this name is one the tester created. */
    public static boolean isTesterTask(String name) {
        return name != null && name.endsWith(SUFFIX);
    }
}
