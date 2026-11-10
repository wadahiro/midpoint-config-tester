package com.github.wadahiro.midpoint.tester;

import java.util.List;

import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * Calls made as another user ({@link MidPoint#actor}): each call logs that user in, goes through {@code ModelService}
 * (so authorizations apply, and their filter expressions are evaluated and recorded), and logs the administrator back
 * in.
 *
 * <pre>{@code
 * Answer<List<UserType>> visible = mp.actor("jdoe").search(UserType.class, null);
 * assertThat(visible.value()).extracting(u -> u.getName().getOrig()).containsOnly("jdoe", "alice");
 * assertThat(visible.run().select().path("authorization")).isNotEmpty();
 * }</pre>
 *
 * A denial is an answer, not a failure: {@link #get} gives null and the run carries the
 * {@code SecurityViolationException} ({@link Run#failure()}); {@link #execute} returns the failed run. Other errors
 * throw {@link OperationFailed}.
 */
public interface Access {

    /** The user the calls are made as. */
    String username();

    /** {@code ModelService.searchObjects}; a null query searches all. */
    <O extends ObjectType> Answer<List<O>> search(Class<O> type, ObjectQuery query);

    /** {@code ModelService.getObject} of the object with this name or OID; the value is null if access is denied. */
    <O extends ObjectType> Answer<O> get(Class<O> type, String nameOrOid);

    /**
     * The objects of the object collection (by name or OID) as this user sees it, as the GUI lists them: its filter
     * with its expressions evaluated for the user (e.g. {@code midpoint.principalOid}), its base collection, the
     * user's authorizations.
     */
    Answer<List<ObjectType>> collection(String nameOrOid);

    /**
     * Whether the user is authorized for the action (an authorization action URI, e.g.
     * {@code ModelAuthorizationAction.MODIFY.getUrl()}) on the object with this name or OID (in any phase), as the
     * security enforcer decides it.
     */
    <O extends ObjectType> Answer<Boolean> isAuthorized(String actionUrl, Class<O> type, String nameOrOid);

    /**
     * Whether the user may assign the target (with this name or OID) to the focus with the relation (null: the
     * default), as the clockwork authorizes the request and its execution: {@code #modify} of the assignments or of all
     * items, else {@code #assign} with the target and relation ({@code #delegate} for a deputy); and the execution
     * phase's {@code #modify}. The changes the projector adds (e.g. {@code roleMembershipRef}) are not checked.
     */
    <F extends ObjectType, T extends ObjectType> Answer<Boolean> isAuthorizedToAssign(Class<F> type, String focus,
            Class<T> targetType, String target, javax.xml.namespace.QName relation);

    /**
     * The objects among these OIDs of the type that the selector (an authorization's {@code object} or
     * {@code target}) selects for the user, as midPoint searches by it (the selector's filter, {@code special: self},
     * {@code orgRelation}...). A selector midPoint cannot search by (e.g. {@code delegator}) selects nothing
     * here.
     */
    <O extends ObjectType> Answer<java.util.Set<String>> selectedBy(
            com.evolveum.midpoint.xml.ns._public.common.common_3.OwnedObjectSelectorType selector, Class<O> type,
            java.util.Collection<String> oids);

    /**
     * The user's authorizations as midPoint compiled them at login, as the GUI's login does: from every role, org
     * and archetype that gives them (assigned or induced, with the relations and conditions as midPoint evaluates
     * them), in midPoint's order.
     */
    Answer<java.util.List<CompiledAuthorization>> authorizations();

    /**
     * The user's GUI profile as midPoint compiled it at login, as the GUI's login does: the system configuration's
     * {@code adminGuiConfiguration}, then each of the roles, orgs and archetypes that give one, merged.
     */
    Answer<GuiProfile> guiProfile();

    /** As {@link #isAuthorizedToAssign}, the unassignment ({@code #unassign}). */
    <F extends ObjectType, T extends ObjectType> Answer<Boolean> isAuthorizedToUnassign(Class<F> type, String focus,
            Class<T> targetType, String target, javax.xml.namespace.QName relation);

    /**
     * Whether the user is allowed any of the actions with no object, as the GUI decides whether a page opens
     * ({@code SecurityEnforcer.decideAccess}: one of them allowed and none denied). A page allows the actions its
     * {@code @PageDescriptor} lists and {@code #guiAll} (and on 4.0 the deprecated {@code #guiAll}): pass them all.
     */
    Answer<Boolean> isAuthorizedForAny(java.util.Collection<String> actionUrls);

    /**
     * The definition of the object with this name or OID as the GUI's form gets it for the user
     * ({@code ModelInteractionService.getEditObjectDefinition}, request phase): each item's {@code canRead},
     * {@code canModify} and {@code canAdd} say what the form shows and lets the user change. Null if the user may
     * not read the object at all.
     */
    <O extends ObjectType> Answer<PrismObjectDefinition<O>> editDefinition(Class<O> type, String nameOrOid);

    /** Executes the delta as the user; a denial or other failure is in the returned run (it does not throw). */
    Run execute(ObjectDelta<? extends ObjectType> delta);
}
