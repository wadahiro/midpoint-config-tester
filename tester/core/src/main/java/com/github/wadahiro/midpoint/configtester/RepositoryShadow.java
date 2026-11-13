package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;

/**
 * The shadow of a projection in the repository ({@link Projection#shadow()}), read anew by every call and as it
 * is (no provisioning): its object type, tag and situation, as their XML values ({@code account}, {@code linked}). The
 * assertions return the shadow for chaining; the views ({@link #tag()}, {@link #kind()}...) give the values for
 * AssertJ.
 */
public final class RepositoryShadow {

    private final Focus<?> owner;
    private final String resource;
    private final String name;

    RepositoryShadow(Focus<?> owner, String resource, String name) {
        this.owner = owner;
        this.resource = resource;
        this.name = name;
    }

    /** The owner links a live shadow of this name on the resource. */
    public RepositoryShadow exists() {
        existing();
        return this;
    }

    /** The owner links no live shadow of this name on the resource (an unlinked or dead one is not it). */
    public RepositoryShadow doesNotExist() {
        if (!linked().isEmpty()) {
            throw new AssertionError(owner.kind + " " + owner.name + ": expected no live shadow " + name + " on "
                    + resource + ", but links it");
        }
        return this;
    }

    public String tag() {
        return existing().getTag();
    }

    public String kind() {
        ShadowType shadow = existing();
        return shadow.getKind() != null ? shadow.getKind().value() : null;
    }

    public String intent() {
        return existing().getIntent();
    }

    public String situation() {
        ShadowType shadow = existing();
        return shadow.getSynchronizationSituation() != null ? shadow.getSynchronizationSituation().value() : null;
    }

    /** The shadow's tag (the object type's multiplicity tag) is this one. */
    public RepositoryShadow hasTag(String expected) {
        return has("tag", expected, tag());
    }

    /** The shadow's kind is this one ({@code account}, {@code entitlement}). */
    public RepositoryShadow hasKind(String expected) {
        return has("kind", expected, kind());
    }

    public RepositoryShadow hasIntent(String expected) {
        return has("intent", expected, intent());
    }

    /** The shadow's synchronization situation ({@code linked}, {@code unmatched}...), as its XML value. */
    public RepositoryShadow hasSituation(String expected) {
        return has("situation", expected, situation());
    }

    /** The shadow as the repository has it now. */
    public ShadowType asObject() {
        return existing();
    }

    private RepositoryShadow has(String what, String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Shadow " + name + " on " + resource + " of " + owner.name + ": expected " + what
                    + " " + expected + " but was " + actual);
        }
        return this;
    }

    private List<ShadowType> linked() {
        List<ShadowType> out = new ArrayList<>();
        for (ShadowType shadow : Projections.onResource(owner, resource)) {
            if (!Boolean.TRUE.equals(shadow.isDead()) && name.equals(shadow.getName().getOrig())) {
                out.add(shadow);
            }
        }
        return out;
    }

    /** The live shadow, or null. */
    ShadowType live() {
        List<ShadowType> shadows = linked();
        return shadows.isEmpty() ? null : shadows.get(0);
    }

    private ShadowType existing() {
        List<ShadowType> shadows = linked();
        if (shadows.isEmpty()) {
            throw new AssertionError(owner.kind + " " + owner.name + ": expected a live shadow " + name + " on "
                    + resource + ", but it links none");
        }
        return shadows.get(0);
    }
}
