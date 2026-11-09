// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The users midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialUsers {

    /** "administrator", 050-user-administrator.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final UserRef ADMINISTRATOR = new UserRef("administrator", "00000000-0000-0000-0000-000000000002", Lines.ALL);

    private InitialUsers() {
    }
}
