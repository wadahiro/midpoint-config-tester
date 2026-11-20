package com.github.wadahiro.midpoint.tester.internal;

/**
 * The names of the tasks the tester creates to run something (a scanner, a report, a resource's task, the imports):
 * {@code "trigger scanner (tester)"}. They are not effects of a block ({@code Effects}), as the tester's own calls
 * are not: every place that creates one names it here.
 */
public final class TesterTasks {

    private static final String SUFFIX = " (tester)";
    /**
     * The OIDs of the tasks the tester started as the GUI does (a members panel's Assign or Unassign,
     * {@link MemberOperations}): the GUI's vehicle of the user's operation, not an effect of it.
     */
    private static final java.util.Set<String> GUI_TASKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private TesterTasks() {
    }

    /** The name of a task the tester creates for this purpose. */
    public static String name(String purpose) {
        return purpose + SUFFIX;
    }

    /** Notes a task the tester started as the GUI does. */
    static void guiTask(String oid) {
        if (oid != null) {
            GUI_TASKS.add(oid);
        }
    }

    /** Whether the task with this OID is one the tester started as the GUI does ({@link #guiTask}). */
    public static boolean isGuiTask(String oid) {
        return oid != null && GUI_TASKS.contains(oid);
    }

    /** Whether a task of this name is one the tester created. */
    public static boolean isTesterTask(String name) {
        return name != null && name.endsWith(SUFFIX);
    }
}
