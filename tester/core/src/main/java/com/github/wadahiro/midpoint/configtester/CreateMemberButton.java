package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.names.ArchetypeRef;
import com.github.wadahiro.midpoint.tester.CompiledAuthorization;

/**
 * The "+" of a role's, org's or service's members panel as a user sees it ({@link Actor#createMemberButton}): whether
 * the GUI shows it to them, the buttons it offers (the archetype's configuration,
 * {@link AbstractRole#newMemberOffers}), and a click on one: the form of the new member as the GUI opens it, filled
 * in and saved as the user ({@link NewObjectForm}).
 *
 * <pre>{@code
 * t.actor("site-admin").createMemberButton(t.org("site-1")).isShown()
 *     .click(OrgType::class.java, "org-category").set("name", "cat-1").submit().saves()
 * t.actor("helpdesk").createMemberButton(t.org("site-1")).isHidden()
 * }</pre>
 *
 * Whether the GUI shows it is decided as the GUI decides it on 4.4 and later, by Wicket's roles of the user (not by
 * the security enforcer): an authorization of the user that names {@code #adminAddOrgMember} (an org's panel) or
 * {@code #adminAddMember} (a role's or a service's), {@code #all} or {@code #guiAll}, whatever its decision (a deny
 * shows it too) and its object. 4.0 shows the panel's "+" to every user who opens the page (only the header menu's
 * "Create" needs the action). Whether a click saves is the form's: the page opens as the user's GUI authorizations
 * allow, then the model authorizes the new object with its assignments (the membership's {@code #assign}, the
 * object's {@code #add}). A failed claim lists the user's authorizations.
 */
public final class CreateMemberButton {

    private final MidPointConfigTester tester;
    private final Actor actor;
    private final AbstractRole<?> role;

    CreateMemberButton(MidPointConfigTester tester, Actor actor, AbstractRole<?> role) {
        this.tester = tester;
        this.actor = actor;
        this.role = role;
    }

    /** Whether the GUI shows the user the "+" of the members panel. */
    public boolean shown() {
        return tester.line().equals("4.0") || !naming().isEmpty();
    }

    /** The GUI shows the user the "+". */
    public CreateMemberButton isShown() {
        if (!shown()) {
            throw new AssertionError(actor + " does not see the + of " + panel() + ": no authorization names "
                    + fragment(action()) + ", #all or #guiAll");
        }
        return this;
    }

    /** The GUI does not show the user the "+" (never on 4.0). */
    public CreateMemberButton isHidden() {
        if (tester.line().equals("4.0")) {
            actor.explainAuthorizations();
            throw new AssertionError(actor + " sees the + of " + panel() + ": 4.0 shows the + to every user who"
                    + " opens the page; the header menu's Create needs " + fragment(action()));
        }
        List<String> naming = new ArrayList<>();
        for (AuthorizationType authorization : naming()) {
            naming.add(Actor.describe(authorization));
        }
        if (!naming.isEmpty()) {
            throw new AssertionError(actor + " sees the + of " + panel() + " (" + String.join("; ", naming) + ")");
        }
        return this;
    }

    /** The buttons the configuration offers, shown to the user or not: {@link AbstractRole#newMemberOffers}. */
    public List<NewMemberOffer> newMemberOffers() {
        return role.newMemberOffers();
    }

    /** The user sees the "+" and it offers a new member of the type without an archetype, with the default relation. */
    public CreateMemberButton offersNewMember(Class<? extends AssignmentHolderType> type) {
        return offersNewMember(type, (String) null, null);
    }

    /** The user sees the "+" and it offers a new member of the type with the archetype (name or OID; null: none). */
    public CreateMemberButton offersNewMember(Class<? extends AssignmentHolderType> type, String archetype) {
        return offersNewMember(type, archetype, null);
    }

    public CreateMemberButton offersNewMember(Class<? extends AssignmentHolderType> type, String archetype,
            QName relation) {
        isShown();
        AbstractRole.Offers offers = role.offers();
        if (offers.find(type, role.archetypeOid(archetype), relation) == null) {
            throw new AssertionError(actor + ": the + of " + role.kind + " " + role.name + " does not offer "
                    + NewMemberOffer.describe(type, archetype, relation) + "; " + offers.text());
        }
        return this;
    }

    public CreateMemberButton offersNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype) {
        return offersNewMember(type, archetype.on(tester.line()), null);
    }

    public CreateMemberButton offersNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype,
            QName relation) {
        return offersNewMember(type, archetype.on(tester.line()), relation);
    }

    /** The user does not see a button of a new member of the type without an archetype: hidden, or not offered. */
    public CreateMemberButton offersNoNewMember(Class<? extends AssignmentHolderType> type) {
        return offersNoNewMember(type, (String) null, null);
    }

    public CreateMemberButton offersNoNewMember(Class<? extends AssignmentHolderType> type, String archetype) {
        return offersNoNewMember(type, archetype, null);
    }

    public CreateMemberButton offersNoNewMember(Class<? extends AssignmentHolderType> type, String archetype,
            QName relation) {
        if (shown() && role.offers().find(type, role.archetypeOid(archetype), relation) != null) {
            throw new AssertionError(actor + ": the + of " + role.kind + " " + role.name + " offers "
                    + NewMemberOffer.describe(type, archetype, relation));
        }
        return this;
    }

    public CreateMemberButton offersNoNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype) {
        return offersNoNewMember(type, archetype.on(tester.line()), null);
    }

    public CreateMemberButton offersNoNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype,
            QName relation) {
        return offersNoNewMember(type, archetype.on(tester.line()), relation);
    }

    /** {@link #click(Class, String, QName)} of the button without an archetype, with the default relation. */
    public NewObjectForm click(Class<? extends AssignmentHolderType> type) {
        return click(type, (String) null, null);
    }

    /** {@link #click(Class, String, QName)} with the default relation. */
    public NewObjectForm click(Class<? extends AssignmentHolderType> type, String archetype) {
        return click(type, archetype, null);
    }

    /**
     * Clicks the button of a new member of the type with the archetype (name or OID; null: none) and the relation
     * (null: the default): the user's form of the new object as the GUI opens it
     * ({@code t.actor(user).newObjectForm(type).archetype(archetype).memberOf(role, relation)}), to fill in and
     * submit. Fails when the user does not see the "+" or it has no such button. A button that names no type
     * ({@link NewMemberOffer#type()} null) is clicked with the type given here: the GUI would open the first type of
     * the panel's search. Opening the page as the user is the form's own check.
     */
    public NewObjectForm click(Class<? extends AssignmentHolderType> type, String archetype, QName relation) {
        isShown();
        String archetypeOid = role.archetypeOid(archetype);
        AbstractRole.Offers offers = role.offers();
        NewMemberOffer offer = offers.find(type, archetypeOid, relation);
        if (offer == null) {
            offer = offers.find(null, archetypeOid, relation);
        }
        if (offer == null) {
            throw new AssertionError(actor + ": the + of " + role.kind + " " + role.name + " has no button for "
                    + NewMemberOffer.describe(type, archetype, relation) + "; " + offers.text());
        }
        NewObjectForm form = actor.newObjectForm(type);
        return (archetypeOid != null ? form.archetype(archetypeOid) : form).memberOf(role, offer.relation());
    }

    public NewObjectForm click(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype) {
        return click(type, archetype.on(tester.line()), null);
    }

    public NewObjectForm click(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype, QName relation) {
        return click(type, archetype.on(tester.line()), relation);
    }

    @Override
    public String toString() {
        return "the + of " + panel() + " as " + actor;
    }

    /** {@code Org site-1's members}. */
    private String panel() {
        return role.kind + " " + role.name + "'s members";
    }

    /** The GUI's action of the panel's "+" (GuiAuthorizationConstants, MEMBER_OPERATION_CREATE). */
    private String action() {
        return OrgType.class.equals(role.type) ? AuthorizationConstants.AUTZ_UI_ADMIN_ADD_ORG_MEMBER_ACTION_URI
                : AuthorizationConstants.AUTZ_UI_ADMIN_ADD_MEMBER_ACTION_URI;
    }

    /**
     * The user's authorizations that name the action, {@code #all} or {@code #guiAll}: Wicket's roles of the session
     * are every action of the user's authorizations (MidPointAuthWebSession.getRoles), and the GUI asks for any of
     * the three (WebComponentUtil.isAuthorized: hasAnyRole).
     */
    private List<AuthorizationType> naming() {
        actor.explainAuthorizations();
        Set<String> roles = Set.of(action(), AuthorizationConstants.AUTZ_ALL_URL,
                AuthorizationConstants.AUTZ_GUI_ALL_URL);
        List<AuthorizationType> out = new ArrayList<>();
        for (CompiledAuthorization compiled : actor.compiledAuthorizations()) {
            if (compiled.authorization().getAction().stream().anyMatch(roles::contains)) {
                out.add(compiled.authorization());
            }
        }
        return out;
    }

    /** {@code #adminAddOrgMember}. */
    private static String fragment(String action) {
        return action.substring(action.lastIndexOf('#'));
    }
}
