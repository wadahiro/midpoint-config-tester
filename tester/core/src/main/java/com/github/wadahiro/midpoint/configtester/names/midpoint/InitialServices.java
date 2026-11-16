// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10, 4.11.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The services midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialServices {

    /** "Artificial intelligence", 602-origin-artificial-intelligence.xml (4.11). */
    public static final ServiceRef ARTIFICIAL_INTELLIGENCE = new ServiceRef("Artificial intelligence", "00000000-0000-0000-0000-000000000602", Lines.only("4.11"));

    /** "Identity recovery", 610-service-identity-recovery.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ServiceRef IDENTITY_RECOVERY = new ServiceRef("Identity recovery", "00000000-0000-0000-0000-000000000610", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Internal", 600-origin-internal.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ServiceRef INTERNAL = new ServiceRef("Internal", "00000000-0000-0000-0000-000000000600", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "System intelligence", 603-origin-system-intelligence.xml (4.11). */
    public static final ServiceRef SYSTEM_INTELLIGENCE = new ServiceRef("System intelligence", "77ab5de4-bdb3-4eb2-9fc3-200739d1128a", Lines.only("4.11"));

    /** "User entry", 601-origin-user-entry.xml (4.9, 4.10, 4.11). */
    public static final ServiceRef USER_ENTRY = new ServiceRef("User entry", "00000000-0000-0000-0000-000000000601", Lines.only("4.9", "4.10", "4.11"));

    private InitialServices() {
    }
}
