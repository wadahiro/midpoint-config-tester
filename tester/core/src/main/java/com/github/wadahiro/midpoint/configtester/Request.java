package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.tester.Change;
import com.github.wadahiro.midpoint.tester.internal.AccessRequest;
import com.github.wadahiro.midpoint.tester.internal.AccessRequestOperations;

/**
 * A request of an {@link Actor} ({@link Actor#request()}, {@link Actor#request(String...)}): the request-access
 * page's Submit as the actor, for one target or several. The cart ({@link Cart}: one role, or several in one Submit)
 * goes as the line's page submits it: one change of the target with all the additions, with the page's options (the
 * system configuration's {@code roleManagement/defaultExecuteAfterAllApprovals} as {@code
 * executeImmediatelyAfterApproval}, the {@link #comment} as the request's business context, inbound and projections
 * skipped in the first pass); a role with an approval policy then waits in a case ({@link MidPointConfigTester#cases()})
 * until its approvers decide, one without is assigned at once, or waits for the other roles' approvals when
 * {@code defaultExecuteAfterAllApprovals} is true (the default). For several targets the page starts a task owned by
 * the actor that makes the change for each of them (the line's: 4.0 an execute-changes task added through the model
 * as the actor, 4.4 an iterative change execution activity, 4.8+ an explicit change execution with one request per
 * target); the call waits for it. midPoint refusing it (no authorization, a policy violation; the task refused at its
 * start or failed on a target) fails the call: {@link MidPointConfigTester#expectFailure}.
 */
public final class Request {

    private final MidPointConfigTester tester;
    private final Actor actor;
    private final List<String> forUsers;
    private String comment;

    Request(MidPointConfigTester tester, Actor actor, List<String> forUsers) {
        if (forUsers.isEmpty()) {
            throw new IllegalArgumentException("A request needs a target user");
        }
        this.tester = tester;
        this.actor = actor;
        this.forUsers = List.copyOf(forUsers);
    }

    /** The target users of the request, in order. */
    public List<String> forUsers() {
        return forUsers;
    }

    /**
     * The comment of the request (the page's comment field), the request's business context: midPoint keeps it as the
     * {@code requestorComment} of the assignments' metadata, and the approvers see it on the case.
     */
    public Request comment(String comment) {
        this.comment = comment;
        return this;
    }

    /**
     * Requests the role (or org, service) of this name for the target; returns what midPoint did ({@link Effects}): the
     * case it opened for the approvers, or the assignment and what follows from it.
     */
    public Effects assign(String role) {
        return submit(c -> c.assign(role));
    }

    /** Requests the role (or org, service) for the target with the relation ({@code SchemaConstants.ORG_OWNER}). */
    public Effects assign(String role, QName relation) {
        return submit(c -> c.assign(role, relation));
    }

    /**
     * Requests the role (or org, service) for the target with a relation or a validity ({@link NewAssignment}), as the
     * GUI's request lets the user choose them: {@code request().assign("VPN Access", a -> a.validTo(end))}.
     */
    public Effects assign(String role, Consumer<NewAssignment> spec) {
        return submit(c -> c.assign(role, spec));
    }

    /** Requests these roles in one Submit, each with the default relation: the page's cart with several items. */
    public Effects assign(String... roles) {
        return submit(c -> c.assign(roles));
    }

    public Effects assign(AssignableRef role) {
        return assign(role.on(tester.line()));
    }

    public Effects assign(AssignableRef role, QName relation) {
        return assign(role.on(tester.line()), relation);
    }

    public Effects assign(AssignableRef role, Consumer<NewAssignment> spec) {
        return assign(role.on(tester.line()), spec);
    }

    public Effects assign(AssignableRef... roles) {
        return submit(c -> c.assign(roles));
    }

    /**
     * Submits the cart ({@link Cart}: the roles to add, each with its relation and validity) as the page's Submit:
     * {@code request().submit(c -> c.assign("VPN Access").assign("Wiki", a -> a.validTo(end)))}.
     */
    public Effects submit(Consumer<Cart> spec) {
        Cart cart = new Cart(tester);
        spec.accept(cart);
        if (cart.items.isEmpty()) {
            throw new IllegalArgumentException("The cart of " + this + " is empty");
        }
        List<ObjectDelta<? extends ObjectType>> deltas = new ArrayList<>();
        for (String forUser : forUsers) {
            Change change = tester.deployment().api().change(UserType.class, forUser);
            for (Cart.Item item : cart.items) {
                change.assign(item.role, item.relation, item.validFrom, item.validTo);
            }
            deltas.add(change.delta());
        }
        if (forUsers.size() == 1) {
            ObjectDelta<? extends ObjectType> delta = deltas.get(0);
            ModelExecuteOptions options = AccessRequestOperations.options(tester.deployment().api(), comment);
            return tester.effectsInGui(() -> tester.deployment().api().approvals().request(actor.name(), delta,
                    options));
        }
        AccessRequest request = new AccessRequest(forUsers, deltas, comment);
        String label = this + ": assign " + cart.roles();
        return tester.effectsInGui(() -> AccessRequestOperations.submit(tester.deployment().api(), actor.name(),
                label, request));
    }

    /**
     * Requests that the target's assignment of the role of this name be removed: a change of the target as the actor
     * through the model, without the page's options (the GUI's cart only adds; a user removes an assignment from the
     * details page). One target only.
     */
    public Effects unassign(String role) {
        return submitPlain(tester.deployment().api().change(UserType.class, singleTarget()).unassign(role).delta());
    }

    /** Requests that the target's assignment of the role with the relation be removed ({@link #unassign(String)}). */
    public Effects unassign(String role, QName relation) {
        return submitPlain(tester.deployment().api().change(UserType.class, singleTarget()).unassign(role, relation)
                .delta());
    }

    public Effects unassign(AssignableRef role) {
        return unassign(role.on(tester.line()));
    }

    public Effects unassign(AssignableRef role, QName relation) {
        return unassign(role.on(tester.line()), relation);
    }

    private String singleTarget() {
        if (forUsers.size() != 1) {
            throw new UnsupportedOperationException("The request-access page removes nothing for several users: "
                    + this);
        }
        return forUsers.get(0);
    }

    private Effects submitPlain(ObjectDelta<? extends ObjectType> delta) {
        return tester.effectsInGui(() -> tester.deployment().api().approvals().request(actor.name(), delta));
    }

    @Override
    public String toString() {
        return "request of " + actor
                + (forUsers.size() == 1 && actor.name().equals(forUsers.get(0)) ? "" : " for " + String.join(", ",
                        forUsers));
    }
}
