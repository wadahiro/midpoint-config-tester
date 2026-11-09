// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The orgs midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialOrgs {

    /** "Projects", 390-org-projects.xml (4.10). */
    public static final OrgRef PROJECTS = new OrgRef("Projects", "90cc2089-6f00-4fd9-9394-10debdd019eb", Lines.only("4.10"));

    /** "Teams", 390-org-teams.xml (4.10). */
    public static final OrgRef TEAMS = new OrgRef("Teams", "2328b6ae-f30b-4701-a1d7-92fb617c9e59", Lines.only("4.10"));

    /** "World", 390-org-world.xml (4.10). */
    public static final OrgRef WORLD = new OrgRef("World", "1d0ce5fc-cf45-4c0f-b1d6-781476242a6e", Lines.only("4.10"));

    private InitialOrgs() {
    }
}
