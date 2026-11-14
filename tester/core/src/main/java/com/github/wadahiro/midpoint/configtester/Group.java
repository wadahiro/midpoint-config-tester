package com.github.wadahiro.midpoint.configtester;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A group on a resource's system ({@link Resource#group}, or a focus's entitlement on the target system), read anew by
 * every call: its attributes as any {@link TargetObject}'s, and its members, the accounts the associations of the
 * resource's {@code schemaHandling} say are in it ({@link Account#memberOf}).
 */
public final class Group extends TargetObject {

    Group(MidPointConfigTester tester, Resource resource, String objectClass, String name) {
        super(tester, resource, objectClass, name);
    }

    @Override
    public Group exists() {
        return (Group) super.exists();
    }

    @Override
    public Group doesNotExist() {
        return (Group) super.doesNotExist();
    }

    @Override
    public Group hasAttribute(String attribute, Object... expected) {
        return (Group) super.hasAttribute(attribute, expected);
    }

    @Override
    public Group hasNoAttribute(String attribute) {
        return (Group) super.hasNoAttribute(attribute);
    }

    /** The accounts in the group (by name, sorted ignoring case). */
    public List<String> members() {
        existing();
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String account : resource.accounts()) {
            if (resource.account(account).isMemberOf(name)) {
                out.add(account);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    public Group hasMember(String account) {
        if (!resource.account(account).isMemberOf(name)) {
            throw new AssertionError("Group " + name + " on " + resource.name() + ": expected the member " + account
                    + ", members: " + members());
        }
        return this;
    }

    public Group hasNoMember(String account) {
        if (resource.account(account).isMemberOf(name)) {
            throw new AssertionError("Group " + name + " on " + resource.name() + ": expected no member " + account
                    + ", members: " + members());
        }
        return this;
    }

    @Override
    String what() {
        return "Group";
    }

    @Override
    Map<String, List<Object>> read() {
        return objectClass != null ? resource.system().find(objectClass, name) : resource.system().group(name);
    }

    @Override
    List<String> others() {
        return objectClass != null ? resource.system().objectNames(objectClass) : resource.system().groupNames();
    }
}
