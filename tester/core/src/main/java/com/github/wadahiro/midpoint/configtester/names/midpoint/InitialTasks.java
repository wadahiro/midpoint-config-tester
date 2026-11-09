// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The tasks midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialTasks {

    /** "Cleanup", 060-task-cleanup.xml, 550-task-cleanup.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final TaskRef CLEANUP = new TaskRef("Cleanup", "00000000-0000-0000-0000-000000000005", Lines.ALL);

    /** "Recompute all users", 910-task-recompute-all-users.xml (4.10). */
    public static final TaskRef RECOMPUTE_ALL_USERS = new TaskRef("Recompute all users", "3e8b389e-7cc4-11ef-9295-4f3d13b4c390", Lines.only("4.10"));

    /** "Trigger Scanner", 080-task-trigger.xml, 570-task-trigger.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final TaskRef TRIGGER_SCANNER = new TaskRef("Trigger Scanner", "00000000-0000-0000-0000-000000000007", Lines.ALL);

    /** "Validity Scanner", 070-task-validity.xml, 560-task-validity.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final TaskRef VALIDITY_SCANNER = new TaskRef("Validity Scanner", "00000000-0000-0000-0000-000000000006", Lines.ALL);

    private InitialTasks() {
    }
}
