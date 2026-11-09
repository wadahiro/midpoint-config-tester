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
public final class Account {

    private final MidPointConfigTester tester;
    private final Resource resource;
    private final String name;

    Account(MidPointConfigTester tester, Resource resource, String name) {
        this.tester = tester;
        this.resource = resource;
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * The mappings that set the attribute of this account in this test (outbounds of the resource, constructions of
     * roles), for the user who owns the account (any user when it has none), oldest first.
     */
    public Why why(String attribute) {
        String owner = owner();
        return Why.of(tester, attribute, owner, attribute + " of " + name + " on " + resource.name());
    }

    /** The name of the user the account's shadow is linked to; null when none. */
    private String owner() {
        String resourceOid = tester.deployment().resourceOid(resource.name());
        if (resourceOid == null) {
            return null;
        }
        try {
            return tester.deployment().owner(resourceOid, name);
        } catch (RuntimeException e) {
            // only to narrow the mappings shown
            return null;
        }
    }

    public Account exists() {
        existing();
        return this;
    }

    public Account doesNotExist() {
        Map<String, List<Object>> account = resource.system().account(name);
        if (account != null) {
            throw new AssertionError("Expected no account " + name + " on " + resource.name() + ", but it exists: "
                    + new TreeMap<>(account));
        }
        return this;
    }

    /** The attribute has exactly these values, in any order (compared as text); no values: it has none. */
    public Account hasAttribute(String attribute, Object... expected) {
        List<String> actual = attributeValues(attribute);
        List<String> wanted = Values.texts(expected);
        if (!Values.sameValues(wanted, actual)) {
            throw new AssertionError("Account " + name + " on " + resource.name() + ": " + attribute + " expected "
                    + wanted + " but was " + actual + "; attributes: " + new TreeMap<>(existing())
                    + Why.mappings(tester, attribute, owner(), attribute + " on " + resource.name()));
        }
        return this;
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

    public Account hasNoAttribute(String attribute) {
        return hasAttribute(attribute);
    }

    /** The values of the attribute as text. */
    public List<String> values(String attribute) {
        tester.readWhy(resource.name() + "/" + name + "/" + attribute, () -> why(attribute));
        return attributeValues(attribute);
    }

    /** The value of a single-valued attribute as text; null when it has none. */
    public String value(String attribute) {
        List<String> values = values(attribute);
        if (values.size() > 1) {
            throw new AssertionError("Account " + name + " on " + resource.name() + ": " + attribute
                    + " has several values " + values);
        }
        return values.isEmpty() ? null : values.get(0);
    }

    public String value(AttributeRef attribute) {
        return value(resource.attribute(attribute));
    }

    /** All attributes, by name, as text, sorted by name. */
    public Map<String, List<String>> attributes() {
        Map<String, List<String>> out = new TreeMap<>();
        existing().forEach((attribute, values) -> out.put(attribute, Values.texts(values)));
        return out;
    }

    /**
     * The groups the account is a member of (by the groups' names, e.g. their DNs, sorted ignoring case), as the
     * resource's associations store membership (see {@link #memberOf}):
     * {@code assertThat(account.groups()).containsExactly(IT)}.
     */
    public Set<String> groups() {
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
        return out;
    }

    /** Whether the account is enabled on the system (its native status, or the attribute of a simulated status). */
    public Status status() {
        existing();
        Status status = resource.system().enabled(name) ? Status.ENABLED : Status.DISABLED;
        String owner = owner();
        tester.readWhy(resource.name() + "/" + name + "/status", () -> Why.of(tester,
                "activation/administrativeStatus", owner, "the status of " + name + " on " + resource.name()));
        return status;
    }

    private List<String> attributeValues(String attribute) {
        return Values.texts(existing().get(attribute));
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

    private Map<String, List<Object>> existing() {
        Map<String, List<Object>> account = resource.system().account(name);
        if (account == null) {
            List<String> names = resource.accounts();
            throw new AssertionError("Expected an account " + name + " on " + resource.name() + ", but there is none;"
                    + " accounts: " + (names.size() > 20 ? names.subList(0, 20) + "..." : names));
        }
        return account;
    }

    private boolean isMemberOf(String group) {
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
                if (containsIgnoreCase(members, valuesOrName(account, association.valueAttribute, name))) {
                    return true;
                }
            } else {
                List<String> memberships = Values.texts(account.get(association.associationAttribute));
                if (containsIgnoreCase(memberships, valuesOrName(groupAttributes, association.valueAttribute, group))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The attribute's values, or the object's name when the attribute is its naming attribute (not an attribute). */
    private static List<String> valuesOrName(Map<String, List<Object>> attributes, String attribute, String name) {
        List<Object> values = attributes.get(attribute);
        return values != null && !values.isEmpty() ? Values.texts(values) : List.of(name);
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
