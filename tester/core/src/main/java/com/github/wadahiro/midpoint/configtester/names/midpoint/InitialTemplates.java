// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The object templates midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialTemplates {

    /** "Person Object Template", 380-object-template-person.xml (4.8, 4.9, 4.10). */
    public static final MidPointObjectRef PERSON_OBJECT_TEMPLATE = new MidPointObjectRef("ObjectTemplateType", "object template", "Person Object Template", "00000000-0000-0000-0000-000000000380", Lines.only("4.8", "4.9", "4.10"));

    private InitialTemplates() {
    }
}
