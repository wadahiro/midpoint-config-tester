// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The services midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialServices {

    /** "Identity recovery", 610-service-identity-recovery.xml (4.8, 4.9, 4.10). */
    public static final ServiceRef IDENTITY_RECOVERY = new ServiceRef("Identity recovery", "00000000-0000-0000-0000-000000000610", Lines.only("4.8", "4.9", "4.10"));

    /** "Internal", 600-origin-internal.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ServiceRef INTERNAL = new ServiceRef("Internal", "00000000-0000-0000-0000-000000000600", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "User entry", 601-origin-user-entry.xml (4.9, 4.10). */
    public static final ServiceRef USER_ENTRY = new ServiceRef("User entry", "00000000-0000-0000-0000-000000000601", Lines.only("4.9", "4.10"));

    private InitialServices() {
    }
}
