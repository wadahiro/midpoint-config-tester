package com.github.wadahiro.midpoint.configtester.assertj;

import org.assertj.core.api.Condition;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.github.wadahiro.midpoint.configtester.Refs;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;

/**
 * AssertJ conditions on midPoint's values by the typed names:
 * {@code assertThat(user.getAssignment()).haveExactly(1, targeting(Roles.DEPT_IT))},
 * {@code assertThat(user.getRoleMembershipRef()).haveAtLeastOne(refTo(Roles.DEPT_IT))}.
 */
public final class MidPointConditions {

    private MidPointConditions() {
    }

    /** An assignment of the role (org, service, archetype, user) of the name. */
    public static Condition<AssignmentType> targeting(ObjectRef target) {
        return new Condition<>(assignment -> Refs.targets(assignment, target), "assignment of %s", target);
    }

    /** A reference to the object of the name. */
    public static Condition<ObjectReferenceType> refTo(ObjectRef target) {
        return new Condition<>(ref -> Refs.isRefTo(ref, target), "reference to %s", target);
    }
}
