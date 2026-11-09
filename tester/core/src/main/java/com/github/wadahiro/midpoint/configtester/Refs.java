package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;

/**
 * midPoint's references against the typed names, for any assertion library ({@code configtester.assertj} wraps them in
 * conditions): by OID when the name has one (its file gives it), else by type and name (the name read in the running
 * test's midPoint).
 */
public final class Refs {

    private Refs() {
    }

    /** The reference points to the object of the name. */
    public static boolean isRefTo(ObjectReferenceType ref, ObjectRef name) {
        if (ref == null || ref.getOid() == null) {
            return false;
        }
        if (name.oid() != null) {
            return name.oid().equals(ref.getOid());
        }
        if (ref.getType() != null && !name.type().equals(ref.getType().getLocalPart())) {
            return false;
        }
        Scenario scenario = Scenario.current();
        String actual = scenario != null ? scenario.nameOf(ref)
                : ref.getTargetName() != null ? ref.getTargetName().getOrig() : null;
        return name.name().equals(actual);
    }

    /** The assignment's target is the object of the name. */
    public static boolean targets(AssignmentType assignment, ObjectRef name) {
        return assignment != null && isRefTo(assignment.getTargetRef(), name);
    }
}
