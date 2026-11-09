// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The roles midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialRoles {

    /** "Approver", 041-role-approver.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final RoleRef APPROVER = new RoleRef("Approver", "00000000-0000-0000-0000-00000000000a", Lines.ALL);

    /** "Delegator", 043-role-delegator.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final RoleRef DELEGATOR = new RoleRef("Delegator", "00000000-0000-0000-0000-00000000000c", Lines.ALL);

    /** "End user", 040-role-enduser.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final RoleRef END_USER = new RoleRef("End user", "00000000-0000-0000-0000-000000000008", Lines.ALL);

    /** "Reviewer", 042-role-reviewer.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final RoleRef REVIEWER = new RoleRef("Reviewer", "00000000-0000-0000-0000-00000000000b", Lines.ALL);

    /** "Superuser", 030-role-superuser.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final RoleRef SUPERUSER = new RoleRef("Superuser", "00000000-0000-0000-0000-000000000004", Lines.ALL);

    private InitialRoles() {
    }
}
