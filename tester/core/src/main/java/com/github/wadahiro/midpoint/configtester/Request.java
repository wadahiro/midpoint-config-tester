package com.github.wadahiro.midpoint.configtester;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.names.AssignableRef;

/**
 * A request of an {@link Actor} ({@link Actor#request()}): the change is submitted as the actor, as the GUI's
 * "Request access" does; a role with an approval policy then waits in a case ({@link MidPointConfigTester#cases()}) until its
 * approvers decide, one without is assigned at once. midPoint refusing it (no authorization, a policy violation)
 * fails the call: {@link MidPointConfigTester#expectFailure}.
 */
public final class Request {

    private final MidPointConfigTester tester;
    private final Actor actor;
    private final String forUser;

    Request(MidPointConfigTester tester, Actor actor, String forUser) {
        this.tester = tester;
        this.actor = actor;
        this.forUser = forUser;
    }

    /** Requests the role (or org, service) of this name for the user. */
    public Request assign(String role) {
        submit(tester.deployment().api().change(UserType.class, forUser).assign(role).delta());
        return this;
    }

    /** Requests that the user's assignment of the role of this name be removed. */
    public Request unassign(String role) {
        submit(tester.deployment().api().change(UserType.class, forUser).unassign(role).delta());
        return this;
    }

    public Request assign(AssignableRef role) {
        return assign(role.on(tester.line()));
    }

    /** Requests the role (or org, service) for the user with the relation ({@code SchemaConstants.ORG_OWNER}). */
    public Request assign(AssignableRef role, QName relation) {
        submit(tester.deployment().api().change(UserType.class, forUser).assign(role.on(tester.line()), relation)
                .delta());
        return this;
    }

    public Request unassign(AssignableRef role) {
        return unassign(role.on(tester.line()));
    }

    private void submit(ObjectDelta<? extends ObjectType> delta) {
        tester.deployment().api().approvals().request(actor.name(), delta);
    }

    @Override
    public String toString() {
        return "request of " + actor + (actor.name().equals(forUser) ? "" : " for " + forUser);
    }
}
