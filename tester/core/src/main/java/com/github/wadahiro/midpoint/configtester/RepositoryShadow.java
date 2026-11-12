package com.github.wadahiro.midpoint.configtester;

import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * The shadow of a linked account in the repository ({@link Projection#shadow()}), read anew by every call and as it
 * is (no provisioning): its object type, tag and situation. The assertions return the shadow for chaining.
 */
public final class RepositoryShadow {

    private final MidPointConfigTester tester;
    private final String owner;
    private final String resource;
    private final String name;

    RepositoryShadow(MidPointConfigTester tester, String owner, String resource, String name) {
        this.tester = tester;
        this.owner = owner;
        this.resource = resource;
        this.name = name;
    }

    /** The owner links a live shadow of this name on the resource. */
    public RepositoryShadow exists() {
        existing();
        return this;
    }

    /** The shadow's tag (the object type's multiplicity tag) is this one. */
    public RepositoryShadow hasTag(String expected) {
        return has("tag", expected, existing().getTag());
    }

    /** The shadow's kind is this one ({@code account}, {@code entitlement}). */
    public RepositoryShadow hasKind(String expected) {
        ShadowType shadow = existing();
        return has("kind", expected, shadow.getKind() != null ? shadow.getKind().value() : null);
    }

    public RepositoryShadow hasIntent(String expected) {
        return has("intent", expected, existing().getIntent());
    }

    /** The shadow's synchronization situation ({@code linked}, {@code unmatched}...), as its XML value. */
    public RepositoryShadow hasSituation(String expected) {
        ShadowType shadow = existing();
        return has("situation", expected,
                shadow.getSynchronizationSituation() != null ? shadow.getSynchronizationSituation().value() : null);
    }

    /** The shadow as the repository has it now. */
    public ShadowType asObject() {
        return existing();
    }

    private RepositoryShadow has(String what, String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Shadow " + name + " on " + resource + " of " + owner + ": expected " + what
                    + " " + expected + " but was " + actual);
        }
        return this;
    }

    private ShadowType existing() {
        UserType user = tester.deployment().api().user(owner);
        if (user == null) {
            throw new AssertionError("Expected a user " + owner + ", but there is none");
        }
        List<ShadowType> shadows = Projection.linked(tester, user, resource, name);
        if (shadows.isEmpty()) {
            throw new AssertionError("User " + owner + ": expected a live shadow " + name + " on " + resource
                    + ", but it links none");
        }
        return shadows.get(0);
    }
}
