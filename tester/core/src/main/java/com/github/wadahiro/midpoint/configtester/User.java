package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.configtester.names.ResourceRef;
import com.github.wadahiro.midpoint.tester.Shadow;

/**
 * A user of the scenario by name ({@link MidPointConfigTester#user}), read anew by every call. {@link #object()} gives the user
 * as midPoint has it now, a plain {@code UserType} to check with any assertion library (with AssertJ:
 * {@code configtester.assertj}); a failed test then shows the mappings of the test that set an item whose value
 * appears in the failure. The assertions ({@code hasRole}, {@code isEnabled}...) return the user for chaining and fail
 * with what is there and the mappings behind it.
 */
public final class User {

    private final MidPointConfigTester tester;
    private final String name;

    User(MidPointConfigTester tester, String name) {
        this.tester = tester;
        this.name = name;
    }

    public String name() {
        return name;
    }

    // ---- existence --------------------------------------------------------------------------------------------

    public User exists() {
        existing();
        return this;
    }

    public User doesNotExist() {
        UserType user = tester.deployment().api().user(name);
        if (user != null) {
            throw new AssertionError("Expected no user " + name + ", but it exists (" + user.getOid() + ")"
                    + accounts(user));
        }
        return this;
    }

    // ---- items ------------------------------------------------------------------------------------------------

    public User hasFullName(String expected) {
        return hasItem("fullName", expected);
    }

    /**
     * The item ({@code costCenter}, {@code activation/validFrom}; names without namespace, so an extension item is
     * found by its local name, e.g. {@code extension/hrId}, not covered by a test yet)
     * has exactly these values, in any order; no values: the item is empty. Values compare as text: a polystring by
     * its original, an enumeration by its XML value ({@code "enabled"}), a date as an {@link java.time.Instant}.
     */
    public User hasItem(String path, Object... expected) {
        List<String> actual = itemValues(path);
        List<String> wanted = Values.texts(expected);
        if (!Values.sameValues(wanted, actual)) {
            throw new AssertionError("User " + name + ": " + path + " expected " + wanted + " but was " + actual
                    + Why.mappings(tester, path, name, path + " of " + name));
        }
        return this;
    }

    /** The values of the item as text (see {@link #hasItem}). */
    public List<String> values(String path) {
        tester.readWhy(name + "/" + path, () -> why(path));
        return itemValues(path);
    }

    /**
     * The user as midPoint has it now (read anew by every call: after a change, call it again), as the scripts of the
     * configuration see it: {@code assertThat(t.user("e1001").object().getCostCenter()).isEqualTo("Sales")}.
     */
    public UserType object() {
        UserType user = existing();
        tester.readObject(name);
        return user;
    }

    /** The item's values as the failure messages show them ({@link Describe}). */
    List<String> describedValues(String path) {
        List<String> out = new ArrayList<>();
        for (Object value : realValues(path)) {
            out.add(Describe.of(value));
        }
        return out;
    }

    private List<String> itemValues(String path) {
        return Values.texts(realValues(path));
    }

    /** The real values at the path, through every value of a multi-valued container on the way ({@code assignment}). */
    private java.util.Collection<?> realValues(String path) {
        PrismObject<UserType> user = existing().asPrismObject();
        List<com.evolveum.midpoint.prism.PrismContainerValue<?>> containers = new ArrayList<>();
        containers.add(user.getValue());
        String[] segments = path.split("/");
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            List<com.evolveum.midpoint.prism.PrismContainerValue<?>> next = new ArrayList<>();
            for (com.evolveum.midpoint.prism.PrismContainerValue<?> container : containers) {
                Item<?, ?> item = container.findItem(ItemPath.create(new QName(segments[i])));
                if (item == null) {
                    continue;
                }
                if (i == segments.length - 1) {
                    out.addAll(item.getRealValues());
                } else {
                    for (Object value : item.getValues()) {
                        if (value instanceof com.evolveum.midpoint.prism.PrismContainerValue) {
                            next.add((com.evolveum.midpoint.prism.PrismContainerValue<?>) value);
                        }
                    }
                }
            }
            containers = next;
        }
        return out;
    }

    /** {@link #hasItem(String, Object...)} by the generated item ({@code UserExtension.SITE}). */
    public User hasItem(ItemRef item, Object... expected) {
        return hasItem(item(item), expected);
    }

    public List<String> values(ItemRef item) {
        return values(item(item));
    }

    public Why why(ItemRef item) {
        return why(item(item));
    }

    private String item(ItemRef item) {
        if (!"UserType".equals(item.ownerType())) {
            throw new AssertionError(item + " is not an item of users");
        }
        return item.on(tester.line());
    }

    /** {@link #hasRole(String)} by the generated name ({@code Roles.DEPT_SALES}, an org, service or archetype). */
    public User hasRole(AssignableRef role) {
        return hasRole(role.on(tester.line()));
    }

    public User hasNoRole(AssignableRef role) {
        return hasNoRole(role.on(tester.line()));
    }

    public User assign(AssignableRef role) {
        return assign(role.on(tester.line()));
    }

    public User unassign(AssignableRef role) {
        return unassign(role.on(tester.line()));
    }

    public User hasAccountOn(ResourceRef resource) {
        return hasAccountOn(resource.on(tester.line()));
    }

    public User hasNoAccountOn(ResourceRef resource) {
        return hasNoAccountOn(resource.on(tester.line()));
    }

    // ---- roles ------------------------------------------------------------------------------------------------

    /** The user has an assignment of the role (or org, service, archetype) of this name. */
    public User hasRole(String role) {
        List<String> assigned = assignedNames();
        if (!assigned.contains(role)) {
            throw new AssertionError("User " + name + ": expected an assignment of " + role + ", assigned: " + assigned
                    + Why.mappings(tester, "assignment", name, "assignments of " + name));
        }
        return this;
    }

    public User hasNoRole(String role) {
        List<String> assigned = assignedNames();
        if (assigned.contains(role)) {
            throw new AssertionError("User " + name + ": expected no assignment of " + role + ", assigned: " + assigned
                    + Why.mappings(tester, "assignment", name, "assignments of " + name));
        }
        return this;
    }

    private List<String> assignedNames() {
        List<String> out = new ArrayList<>();
        for (AssignmentType assignment : existing().getAssignment()) {
            ObjectReferenceType target = assignment.getTargetRef();
            if (target != null) {
                out.add(tester.nameOf(target));
            }
        }
        return out;
    }

    // ---- accounts ---------------------------------------------------------------------------------------------

    /** The user has a live account (shadow) on the resource, linked to it. */
    public User hasAccountOn(String resource) {
        existing();
        Shadow shadow = tester.deployment().api().shadow(name, resource);
        if (shadow == null || shadow.dead()) {
            throw new AssertionError("User " + name + ": expected an account on " + resource
                    + (shadow != null ? ", but its shadow is dead" : ", but there is none") + accounts(existing()));
        }
        return this;
    }

    public User hasNoAccountOn(String resource) {
        existing();
        Shadow shadow = tester.deployment().api().shadow(name, resource);
        if (shadow != null && !shadow.dead()) {
            throw new AssertionError("User " + name + ": expected no account on " + resource + ", but has "
                    + shadow.name() + " (" + shadow.situation() + ")");
        }
        return this;
    }

    // ---- activation -------------------------------------------------------------------------------------------

    /** The user's effective status (as midPoint computed it from the administrative status and validity) is enabled. */
    public User isEnabled() {
        return hasEffectiveStatus("enabled");
    }

    public User isDisabled() {
        return hasEffectiveStatus("disabled");
    }

    /**
     * The user's effective status is archived: midPoint 4.0's and 4.4's for a user whose lifecycle state is not an
     * active one ({@code archived}; on 4.0 also {@code proposed} and {@code draft}), whatever the administrative
     * status. 4.8+ gives such a user {@code disabled} ({@link #isDisabled()}).
     */
    public User isArchived() {
        return hasEffectiveStatus("archived");
    }

    private User hasEffectiveStatus(String expected) {
        UserType user = existing();
        String actual = user.getActivation() != null ? Values.text(user.getActivation().getEffectiveStatus()) : null;
        if (!expected.equals(actual)) {
            throw new AssertionError("User " + name + ": expected effectiveStatus " + expected + " but was " + actual
                    + " (" + validity(user) + ")");
        }
        return this;
    }

    private String validity(UserType user) {
        return "lifecycleState " + user.getLifecycleState() + ", "
                + (user.getActivation() == null ? "no activation"
                : "administrativeStatus " + Values.text(user.getActivation().getAdministrativeStatus())
                        + ", validFrom " + Values.text(user.getActivation().getValidFrom())
                        + ", validTo " + Values.text(user.getActivation().getValidTo()))
                + "; midPoint's clock " + tester.clock().now();
    }

    // ---- changes ----------------------------------------------------------------------------------------------

    /** Assigns the role (or org, service) of this name, as an administrator in the GUI would. */
    public User assign(String role) {
        tester.deployment().api().assign(UserType.class, name, role);
        return this;
    }

    public User unassign(String role) {
        tester.deployment().api().unassign(UserType.class, name, role);
        return this;
    }

    /**
     * Sets the user's password, as an administrator in the GUI would; midPoint checks it against the password policy
     * of the user's security policy (a value that violates it fails: {@link MidPointConfigTester#expectFailure}).
     */
    public User setPassword(String password) {
        com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType value =
                new com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType();
        value.setClearValue(password);
        tester.deployment().api().modify(UserType.class, name, ItemPath.create(UserType.F_CREDENTIALS,
                com.evolveum.midpoint.xml.ns._public.common.common_3.CredentialsType.F_PASSWORD,
                com.evolveum.midpoint.xml.ns._public.common.common_3.PasswordType.F_VALUE), value);
        return this;
    }

    /** Recomputes the user, as the GUI's "Recompute". */
    public User recompute() {
        tester.deployment().api().recompute(UserType.class, name);
        return this;
    }

    // ---- why ------------------------------------------------------------------------------------------------

    /**
     * The mappings that set the item of this user in this test ({@code fullName}, {@code assignment},
     * {@code activation/administrativeStatus}), oldest first; {@code why("fullName").print()} shows them.
     */
    public Why why(String path) {
        return Why.of(tester, path, name, path + " of " + name);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private UserType existing() {
        UserType user = tester.deployment().api().user(name);
        if (user == null) {
            throw new AssertionError("Expected a user " + name + ", but there is none");
        }
        return user;
    }

    private String accounts(UserType user) {
        int links = user.getLinkRef().size();
        return links == 0 ? "; it has no accounts" : "; it links " + links + " account(s)";
    }
}
