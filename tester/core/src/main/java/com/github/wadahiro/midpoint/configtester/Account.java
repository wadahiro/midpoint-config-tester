package com.github.wadahiro.midpoint.configtester;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.github.wadahiro.midpoint.configtester.names.AttributeRef;
import com.github.wadahiro.midpoint.tester.TargetSystem;
import com.github.wadahiro.midpoint.tester.internal.Deployment;

/**
 * An account on a resource's system ({@link Resource#account}), read anew by every call. The views ({@link #value},
 * {@link #groups()}, {@link #status()}) give the values for AssertJ,
 * {@code assertThat(ldap.account(JOHN).value(LdapAttrs.OU)).isEqualTo("IT")}; a failed test then shows the outbound
 * mappings behind the values it read. The assertions return the account for chaining and fail with what is there and
 * the mappings behind it.
 */
public final class Account extends TargetObject {

    Account(MidPointConfigTester tester, Resource resource, String name) {
        super(tester, resource, null, name);
    }

    @Override
    public Account exists() {
        return (Account) super.exists();
    }

    @Override
    public Account doesNotExist() {
        return (Account) super.doesNotExist();
    }

    @Override
    public Account hasAttribute(String attribute, Object... expected) {
        return (Account) super.hasAttribute(attribute, expected);
    }

    @Override
    public Account hasNoAttribute(String attribute) {
        return (Account) super.hasNoAttribute(attribute);
    }

    /** {@link #hasAttribute(String, Object...)} by the generated attribute ({@code LdapAttrs.OU}). */
    public Account hasAttribute(AttributeRef attribute, Object... expected) {
        return hasAttribute(resource.attribute(attribute), expected);
    }

    public Account hasNoAttribute(AttributeRef attribute) {
        return hasNoAttribute(resource.attribute(attribute));
    }

    public List<String> values(AttributeRef attribute) {
        return values(resource.attribute(attribute));
    }

    public Why why(AttributeRef attribute) {
        return why(resource.attribute(attribute));
    }

    public String value(AttributeRef attribute) {
        return value(resource.attribute(attribute));
    }

    /**
     * The groups the account is a member of (by the groups' names, e.g. their DNs, sorted ignoring case), as the
     * resource's associations store membership (see {@link #memberOf}):
     * {@code assertThat(account.groups()).containsExactly(IT)}.
     */
    public List<String> groups() {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String group : resource.system().groupNames()) {
            if (isMemberOf(group)) {
                out.add(group);
            }
        }
        String owner = owner();
        if (owner != null) {
            // memberships follow the owner's roles
            tester.readWhy(owner + "/assignment", () -> Why.of(tester, "assignment", owner, "assignments of " + owner));
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * The account is a member of the group (by the group's name, e.g. its DN), as the associations of the resource's
     * {@code schemaHandling} say membership is stored: the group's member attribute holds the account
     * ({@code objectToSubject}) or the account's attribute holds the group ({@code subjectToObject}). Names compare
     * ignoring case.
     */
    public Account memberOf(String group) {
        if (!isMemberOf(group)) {
            throw new AssertionError("Account " + name + " on " + resource.name() + ": expected a member of " + group
                    + ", but it is not; " + membership(group));
        }
        return this;
    }

    public Account notMemberOf(String group) {
        if (isMemberOf(group)) {
            throw new AssertionError("Account " + name + " on " + resource.name() + ": expected no member of " + group
                    + ", but it is; " + membership(group));
        }
        return this;
    }

    /** The account is enabled on the system (its native status, or the attribute of a simulated status). */
    public Account isEnabled() {
        existing();
        if (!resource.system().enabled(name)) {
            throw new AssertionError("Account " + name + " on " + resource.name() + ": expected enabled, but it is"
                    + " disabled; attributes: " + new TreeMap<>(existing())
                    + Why.mappings(tester, "activation/administrativeStatus", owner(), "the status on " + resource.name()));
        }
        return this;
    }

    public Account isDisabled() {
        existing();
        if (resource.system().enabled(name)) {
            throw new AssertionError("Account " + name + " on " + resource.name() + ": expected disabled, but it is"
                    + " enabled; attributes: " + new TreeMap<>(existing())
                    + Why.mappings(tester, "activation/administrativeStatus", owner(), "the status on " + resource.name()));
        }
        return this;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    @Override
    String what() {
        return "Account";
    }

    @Override
    Map<String, List<Object>> read() {
        return resource.system().account(name);
    }

    @Override
    List<String> others() {
        return resource.accounts();
    }

    /** Whether the account is a member of the group, as {@link #memberOf} says. */
    boolean isMemberOf(String group) {
        Map<String, List<Object>> account = existing();
        List<Deployment.Association> associations = tester.deployment().associations(resource.name());
        if (associations.isEmpty()) {
            throw new AssertionError("Resource " + resource.name() + " has no association in its account object types:"
                    + " memberships cannot be read");
        }
        TargetSystem system = resource.system();
        Map<String, List<Object>> groupAttributes = system.group(group);
        if (groupAttributes == null) {
            throw new AssertionError("No group " + group + " on " + resource.name() + "; groups: " + system.groupNames());
        }
        for (Deployment.Association association : associations) {
            if ("objectToSubject".equals(association.direction)) {
                List<String> members = Values.texts(groupAttributes.get(association.associationAttribute));
                if (containsIgnoreCase(members, valuesOrName(account, association.valueAttribute, name,
                        () -> system.accountIdentifier(name)))) {
                    return true;
                }
            } else {
                List<String> memberships = Values.texts(account.get(association.associationAttribute));
                if (containsIgnoreCase(memberships, valuesOrName(groupAttributes, association.valueAttribute, group,
                        () -> system.groupIdentifier(group)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The attribute's values; when the object has none (the attribute is its naming attribute or its primary
     * identifier, e.g. SCIM's {@code userId}, which the target system issues apart from the attributes): the object's
     * name and its identifier.
     */
    private static List<String> valuesOrName(Map<String, List<Object>> attributes, String attribute, String name,
            java.util.function.Supplier<String> identifier) {
        List<Object> values = attributes.get(attribute);
        if (values != null && !values.isEmpty()) {
            return Values.texts(values);
        }
        String uid = identifier.get();
        return uid != null && !uid.equals(name) ? List.of(name, uid) : List.of(name);
    }

    private static boolean containsIgnoreCase(List<String> values, List<String> wanted) {
        for (String value : values) {
            for (String w : wanted) {
                if (value != null && value.equalsIgnoreCase(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String membership(String group) {
        TargetSystem system = resource.system();
        StringBuilder sb = new StringBuilder();
        for (Deployment.Association association : tester.deployment().associations(resource.name())) {
            if ("objectToSubject".equals(association.direction)) {
                Map<String, List<Object>> attributes = system.group(group);
                sb.append(group).append(' ').append(association.associationAttribute).append(": ")
                        .append(attributes != null ? Values.texts(attributes.get(association.associationAttribute))
                                : "(no such group)");
            } else {
                sb.append(name).append(' ').append(association.associationAttribute).append(": ")
                        .append(Values.texts(existing().get(association.associationAttribute)));
            }
        }
        return sb.toString();
    }
}
