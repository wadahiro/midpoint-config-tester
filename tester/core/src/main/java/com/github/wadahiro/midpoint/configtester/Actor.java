package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OwnedObjectSelectorType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.evolveum.prism.xml.ns._public.types_3.ItemPathType;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.tester.Answer;
import com.github.wadahiro.midpoint.tester.CompiledAuthorization;
import com.github.wadahiro.midpoint.tester.GuiPages;
import com.github.wadahiro.midpoint.tester.GuiProfile;

/**
 * A user who acts in the scenario ({@link MidPointConfigTester#actor}): requests roles, approves and rejects work items, reviews in
 * certification campaigns. midPoint runs the action logged in as that user, so their authorizations apply (a request
 * needs the End user role's "assign requestable roles", an approval the Approver role's, a review the Reviewer
 * role's). What the user sees and may do in the GUI is asked the same way, by midPoint's actions: what their searches
 * find ({@link #search}, {@link #canSearch}), what they get ({@link #canGet}), both ({@link #canRead}), which pages
 * open ({@link #canOpen}), what an object's form shows and lets them change ({@link #form}). When an assertion on
 * these fails, the failure lists the user's authorizations.
 *
 * <pre>{@code
 * t.actor("jack").request().assign("Privileged Access");
 * t.workItems().forRole("Privileged Access").single().approve(t.actor("cc-owner"), "ok");
 * assertThat(t.actor("helpdesk").search(UserType.class)).containsExactlyInAnyOrder(Users.JACK, Users.HELPDESK);
 * }</pre>
 */
public final class Actor {

    private final MidPointConfigTester tester;
    private final String name;

    Actor(MidPointConfigTester tester, String name) {
        this.tester = tester;
        this.name = name;
    }

    /** The user's name. */
    public String name() {
        return name;
    }

    /** A request for the actor themself, as in the GUI's "Request access". */
    public Request request() {
        return new Request(tester, this, name);
    }

    /** A request for another user (midPoint checks that the actor may assign to them). */
    public Request request(String forUser) {
        return new Request(tester, this, forUser);
    }

    public Request request(UserRef forUser) {
        return request(forUser.on(tester.line()));
    }

    /**
     * The objects of the type a search by the user finds, as the GUI's lists show them (midPoint narrows the search
     * by the user's {@code #search} and {@code #read} authorizations), in no particular order.
     */
    public List<ObjectRef> search(Class<? extends ObjectType> type) {
        explainAuthorizations();
        List<ObjectRef> out = new ArrayList<>();
        for (ObjectType object : tester.midpoint().actor(name).search(type, null).value()) {
            out.add(ObjectRef.of(typeName(object), object.getName().getOrig(), object.getOid()));
        }
        return out;
    }

    /**
     * The objects of the object collection (by name or OID) as the user sees it in the GUI's list of it: its filter
     * evaluated for the user ({@code midpoint.principalOid}...), its base collection, the user's authorizations.
     */
    public List<ObjectRef> collection(String nameOrOid) {
        List<ObjectRef> out = new ArrayList<>();
        for (ObjectType object : tester.midpoint().actor(name).collection(nameOrOid).value()) {
            out.add(ObjectRef.of(typeName(object), object.getName().getOrig(), object.getOid()));
        }
        return out;
    }

    /** Whether a search by the user finds the object ({@code #search} or {@code #read}): the GUI's lists show it. */
    public boolean canSearch(ObjectRef object) {
        return search(typeOf(object)).contains(object);
    }

    /**
     * Whether the user may get the object by its OID ({@code #get} or {@code #read}): its details open, and the
     * GUI's selection windows and references show it.
     */
    public boolean canGet(ObjectRef object) {
        explainAuthorizations();
        return tester.midpoint().actor(name).get(typeOf(object), key(object)).value() != null;
    }

    /**
     * Whether the user is authorized for midPoint's action on the object, in any phase, as the security enforcer
     * decides it ({@code ModelAuthorizationAction.DELETE.getUrl()}, {@code Users.JACK}).
     */
    public boolean isAuthorized(String actionUrl, ObjectRef object) {
        explainAuthorizations();
        return tester.midpoint().actor(name).isAuthorized(actionUrl, typeOf(object), key(object)).value();
    }

    /**
     * Whether the user may assign the target to the focus with the relation (null: the default), as midPoint
     * authorizes the request and its execution ({@code Access.isAuthorizedToAssign}): {@code #modify} of the
     * assignments, else {@code #assign} with the target and relation.
     */
    public boolean canAssign(ObjectRef focus, ObjectRef target, QName relation) {
        explainAuthorizations();
        return tester.midpoint().actor(name).isAuthorizedToAssign(typeOf(focus), key(focus), typeOf(target),
                key(target), relation).value();
    }

    /** As {@link #canAssign}, the unassignment ({@code #unassign}). */
    public boolean canUnassign(ObjectRef focus, ObjectRef target, QName relation) {
        explainAuthorizations();
        return tester.midpoint().actor(name).isAuthorizedToUnassign(typeOf(focus), key(focus), typeOf(target),
                key(target), relation).value();
    }

    /** {@link #canGet} and {@link #canSearch}, as {@code #read} allows both. */
    public boolean canRead(ObjectRef object) {
        return canGet(object) && canSearch(object);
    }

    /**
     * Whether the user may open a GUI page, as the GUI decides it: by its URL after the GUI's context
     * ({@code canOpen("/admin/users")}, {@code canOpen("/admin/user/" + oid)}), from the line's index of the GUI's
     * pages (4.0, 4.4, 4.8); or by the actions of its {@code @PageDescriptor}
     * ({@code canOpen(AUTZ_UI_USERS_ALL_URL, AUTZ_UI_USERS_URL, AUTZ_UI_USERS_VIEW_URL)}). A single argument that
     * starts with {@code /} is a URL. Allowed when one of the required actions (with {@code #guiAll}) is allowed and
     * none denied; a page any user opens (a login page) is allowed, a URL no page matches is denied.
     */
    public boolean canOpen(String... pageOrActions) {
        explainAuthorizations();
        Set<String> actions;
        if (pageOrActions.length == 1 && pageOrActions[0].startsWith("/")) {
            GuiPages.Required required = GuiPages.of().required(pageOrActions[0], guiAll());
            tester.read("page " + pageOrActions[0], () -> "Page " + pageOrActions[0] + ": "
                    + (required.matches().isEmpty() ? "no page matches" : "matches " + required.matches())
                    + (required.permitted() ? ", any user opens it" : ", needs one of " + required.actions()));
            if (required.permitted()) {
                return true;
            }
            if (required.actions().isEmpty()) {
                return false;
            }
            actions = required.actions();
        } else {
            actions = new LinkedHashSet<>(Arrays.asList(pageOrActions));
            actions.addAll(guiAll());
        }
        return tester.midpoint().actor(name).isAuthorizedForAny(actions).value();
    }

    /** The {@code #guiAll} actions the GUI adds to a page's: 4.0 has a deprecated one too. */
    private Set<String> guiAll() {
        Set<String> out = new LinkedHashSet<>();
        out.add(AuthorizationConstants.AUTZ_GUI_ALL_URL);
        if (tester.line().equals("4.0")) {
            // AUTZ_GUI_ALL_DEPRECATED_URL, gone from 4.4's constants
            out.add("http://midpoint.evolveum.com/xml/ns/public/security/authorization-3#guiAll");
        }
        return out;
    }

    /** The form the GUI shows the user for the object: what it shows and what it lets them change. */
    public Form form(ObjectRef object) {
        explainAuthorizations();
        Answer<? extends PrismObjectDefinition<?>> definition = tester.midpoint().actor(name)
                .editDefinition(typeOf(object), key(object));
        return new Form(name, object, definition.value());
    }

    /**
     * The user's GUI profile as midPoint compiled it at login: the system configuration's
     * {@code adminGuiConfiguration} merged with those of the roles, orgs and archetypes that give one (assigned or
     * induced). Its views ({@code views(UserType.COMPLEX_TYPE)}, {@code inMenu()}), menu links, features, details
     * pages. When an assertion on it fails, the failure shows its views and features.
     */
    public GuiProfile guiProfile() {
        explainAuthorizations();
        GuiProfile profile = tester.midpoint().actor(name).guiProfile().value();
        tester.read("GUI profile of " + name, () -> describe(profile));
        return profile;
    }

    private String describe(GuiProfile profile) {
        StringBuilder sb = new StringBuilder("GUI profile of " + name + " (as midPoint compiled it at login):");
        sb.append("\n  views:");
        for (GuiProfile.CollectionView view : profile.views()) {
            sb.append("\n    ").append(view).append(view.inMenu() ? ", in the menu" : ", not in the menu");
            if (view.visibility() != null) {
                sb.append(", ").append(view.visibility().value());
            }
        }
        sb.append("\n  features:");
        for (com.evolveum.midpoint.xml.ns._public.common.common_3.UserInterfaceFeatureType feature
                : profile.features()) {
            sb.append("\n    ").append(feature.getIdentifier()).append(": ")
                    .append(feature.getVisibility() == null ? "-" : feature.getVisibility().value());
        }
        sb.append("\n  additional menu links:");
        for (com.evolveum.midpoint.xml.ns._public.common.common_3.RichHyperlinkType link
                : profile.additionalMenuLinks()) {
            sb.append("\n    ").append(link.getLabel()).append(" -> ").append(link.getTargetUrl());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return name;
    }

    /** Once per user and test, however many of their checks the test makes. */
    private void explainAuthorizations() {
        tester.read("authorizations of " + name, this::authorizations);
    }

    /** The user's authorizations, by the role (or archetype) they come from, as a failure shows them. */
    String authorizations() {
        if (tester.midpoint().find(UserType.class, name) == null) {
            return "No user " + name;
        }
        StringBuilder sb = new StringBuilder("Authorizations of " + name + " (as midPoint compiled them at login):");
        String source = null;
        boolean first = true;
        for (CompiledAuthorization compiled : compiledAuthorizations()) {
            if (first || !java.util.Objects.equals(compiled.source(), source)) {
                source = compiled.source();
                first = false;
                sb.append("\n  ").append(source(source)).append(':');
            }
            sb.append("\n    ").append(describe(compiled.authorization()));
        }
        return sb.toString();
    }

    /** The user's authorizations as midPoint compiled them at login ({@code Access.authorizations()}). */
    List<CompiledAuthorization> compiledAuthorizations() {
        return tester.midpoint().actor(name).authorizations().value();
    }

    /** {@code role:<oid>(End user)} as {@code role End user}. */
    static String source(String source) {
        if (source == null) {
            return "unknown source";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+):[0-9a-fA-F-]+\\((.*)\\)")
                .matcher(source);
        return m.matches() ? m.group(1) + " " + m.group(2) : source;
    }


    /** {@code 'read-it-users': allow #read, #search (request) of UserType with a filter; items givenName...}. */
    static String describe(AuthorizationType authorization) {
        StringBuilder sb = new StringBuilder();
        if (authorization.getName() != null) {
            sb.append('\'').append(authorization.getName()).append("': ");
        }
        sb.append(authorization.getDecision() != null ? authorization.getDecision().value() : "allow").append(' ');
        List<String> actions = new ArrayList<>();
        for (String action : authorization.getAction()) {
            actions.add(action.substring(action.lastIndexOf('/') + 1));
        }
        sb.append(String.join(", ", actions));
        if (authorization.getPhase() != null) {
            sb.append(" (").append(authorization.getPhase().value()).append(')');
        }
        List<String> objects = new ArrayList<>();
        for (OwnedObjectSelectorType selector : authorization.getObject()) {
            objects.add(selector(selector));
        }
        if (!objects.isEmpty()) {
            sb.append(" of ").append(String.join(" or ", objects));
        }
        if (!authorization.getItem().isEmpty()) {
            sb.append("; items ").append(paths(authorization.getItem()));
        }
        if (!authorization.getExceptItem().isEmpty()) {
            sb.append("; except items ").append(paths(authorization.getExceptItem()));
        }
        return sb.toString();
    }

    static String selector(OwnedObjectSelectorType selector) {
        List<String> parts = new ArrayList<>();
        parts.add(selector.getType() != null ? selector.getType().getLocalPart() : "any object");
        if (!selector.getSpecial().isEmpty()) {
            parts.add(selector.getSpecial().toString().toLowerCase(java.util.Locale.ROOT));
        }
        if (selector.getFilter() != null) {
            parts.add("with a filter");
        }
        if (selector.getOrgRef() != null || selector.getOrgRelation() != null) {
            parts.add("in an org");
        }
        if (selector.getOwner() != null) {
            parts.add("by owner");
        }
        return String.join(" ", parts);
    }

    private static String paths(List<ItemPathType> paths) {
        List<String> out = new ArrayList<>();
        for (ItemPathType path : paths) {
            out.add(path.getItemPath().toString());
        }
        return String.join(", ", out);
    }

    private static String typeName(ObjectType object) {
        return object.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private Class<ObjectType> typeOf(ObjectRef object) {
        try {
            return (Class<ObjectType>) Class.forName(
                    "com.evolveum.midpoint.xml.ns._public.common.common_3." + object.type());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("No midPoint type " + object.type() + " for " + object, e);
        }
    }

    private String key(ObjectRef object) {
        return object.oid() != null ? object.oid() : object.on(tester.line());
    }
}
