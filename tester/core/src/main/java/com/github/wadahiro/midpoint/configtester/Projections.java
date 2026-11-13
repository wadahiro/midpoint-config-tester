package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;

/**
 * The projections a focus links on a resource, to narrow and check ({@link Focus#accounts}, {@link Focus#entitlements},
 * {@link Focus#projections}): the live shadows of the kind, narrowed by their intent, tag and name, as midPoint keys
 * a projection. A selection claims nothing until it ends in {@link #single()}, {@link #exactly(int)} or
 * {@link #none()}, which check the repository (the links and their live shadows); the object on the target system is
 * {@code single().onTarget()}, and {@link Focus#hasAccountOn} claims both. A failure lists every shadow the focus
 * links on the resource, with why it was left out:
 *
 * <pre>{@code
 * t.user("jdoe").accounts("Directory").intent("admin").single().onTarget().hasAttribute("description", "admin");
 * t.user("jdoe").accounts("Directory").intent("default").tag("tenant-b").none();
 * t.role("Sales").projections("Directory").kind("entitlement").exactly(1);
 * }</pre>
 *
 * @param <T> what the projections are on the target system ({@link Projection#onTarget()})
 */
public final class Projections<T extends TargetObject> {

    private final Focus<?> owner;
    private final String resource;
    /** null: any kind. */
    private final String kind;
    private final String intent;
    private final String tag;
    private final String named;
    private final Projection.Target<T> target;

    Projections(Focus<?> owner, String resource, String kind, String intent, String tag, String named,
            Projection.Target<T> target) {
        this.owner = owner;
        this.resource = resource;
        this.kind = kind;
        this.intent = intent;
        this.tag = tag;
        this.named = named;
        this.target = target;
    }

    // ---- narrowing --------------------------------------------------------------------------------------------

    /** The projections of the kind ({@code account}, {@code entitlement}, {@code generic}). */
    public Projections<TargetObject> kind(String kind) {
        if (this.kind != null) {
            throw new IllegalStateException("These are the " + this.kind + "s already: kind(" + kind + ") is for "
                    + "projections(resource)");
        }
        return new Projections<>(owner, resource, kind, intent, tag, named, Projection.ANY);
    }

    public Projections<T> intent(String intent) {
        return new Projections<>(owner, resource, kind, intent, tag, named, target);
    }

    /** The projections of this tag (an object type's multiplicity tag, which tells apart accounts of one intent). */
    public Projections<T> tag(String tag) {
        return new Projections<>(owner, resource, kind, intent, tag, named, target);
    }

    /** The projection of this name on the target system (its shadow's name). */
    public Projections<T> named(String name) {
        return new Projections<>(owner, resource, kind, intent, tag, name, target);
    }

    // ---- checks -----------------------------------------------------------------------------------------------

    /** There is exactly one: it. */
    public Projection<T> single() {
        List<Projection<T>> found = list();
        if (found.size() != 1) {
            throw new AssertionError(owner.kind + " " + owner.name + ": expected one " + word() + where() + ", but links "
                    + (found.isEmpty() ? "none" : found.size())
                    + (found.size() > 1 ? " (narrow them: intent, tag or named)" : "") + "; " + listing());
        }
        return found.get(0);
    }

    public Projections<T> exactly(int expected) {
        int found = count();
        if (found != expected) {
            throw new AssertionError(owner.kind + " " + owner.name + ": expected " + expected + " " + plural() + ", but"
                    + " links " + found + "; " + listing());
        }
        return this;
    }

    public Projections<T> none() {
        if (count() != 0) {
            throw new AssertionError(owner.kind + " " + owner.name + ": expected no " + word() + where() + ", but links "
                    + String.join(", ", names()) + "; " + listing());
        }
        return this;
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    public List<Projection<T>> list() {
        List<Projection<T>> out = new ArrayList<>();
        for (ShadowType shadow : onResource()) {
            if (reason(shadow) == null) {
                out.add(new Projection<>(owner, resource, shadow.getName().getOrig(), target));
            }
        }
        return out;
    }

    /** The names on the target system (the shadows' names). */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Projection<T> projection : list()) {
            out.add(projection.name());
        }
        return out;
    }

    public int count() {
        return list().size();
    }

    @Override
    public String toString() {
        return plural() + " of " + owner;
    }

    // ---- for the focus ----------------------------------------------------------------------------------------

    /** {@code an account on Directory with intent admin}: what the selection is, for a message. */
    String what() {
        String word = word();
        return (word.matches("^[aeiou].*") ? "an " : "a ") + word + where();
    }

    String plural() {
        return word() + "s" + where();
    }

    /** Every shadow the focus links on the resource, with why the selection leaves it out. */
    String listing() {
        List<ShadowType> all = onResource();
        if (all.isEmpty()) {
            return "it links nothing on " + resource;
        }
        List<String> out = new ArrayList<>();
        for (ShadowType shadow : all) {
            out.add(describe(shadow, reason(shadow)));
        }
        return "linked on " + resource + ": " + String.join(", ", out);
    }

    /** {@code jdoe-admin (account/admin/tenant-a)}, and why the selection leaves it out: {@code (...; dead)}. */
    private static String describe(ShadowType shadow, String reason) {
        String key = kindOf(shadow) + "/" + shadow.getIntent() + (shadow.getTag() != null ? "/" + shadow.getTag() : "");
        return shadow.getName().getOrig() + " (" + key + (reason != null ? "; " + reason : "") + ")";
    }

    static String kindOf(ShadowType shadow) {
        return shadow.getKind() != null ? shadow.getKind().value() : null;
    }

    /** The shadows the focus links on the resource, as the repository has them: dead ones too. */
    static List<ShadowType> onResource(Focus<?> owner, String resource) {
        String resourceOid = owner.tester.target(resource).system().resourceOid();
        List<ShadowType> out = new ArrayList<>();
        for (ObjectReferenceType link : owner.existing().getLinkRef()) {
            ShadowType shadow = owner.tester.deployment().api().find(ShadowType.class, link.getOid());
            if (shadow != null && shadow.getResourceRef() != null
                    && resourceOid.equals(shadow.getResourceRef().getOid())) {
                out.add(shadow);
            }
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private List<ShadowType> onResource() {
        return onResource(owner, resource);
    }

    /** Why the selection leaves the shadow out; null: it is in. */
    private String reason(ShadowType shadow) {
        if (Boolean.TRUE.equals(shadow.isDead())) {
            return "dead";
        }
        if (kind != null && !kind.equalsIgnoreCase(String.valueOf(kindOf(shadow)))) {
            return "not " + (kind.matches("^[aeiou].*") ? "an " : "a ") + kind;
        }
        if (intent != null && !intent.equals(shadow.getIntent())) {
            return "other intent";
        }
        if (tag != null && !tag.equals(shadow.getTag())) {
            return "other tag";
        }
        if (named != null && !named.equals(shadow.getName().getOrig())) {
            return "other name";
        }
        return null;
    }

    private String word() {
        return kind == null ? "projection" : kind;
    }

    private String where() {
        return " on " + resource + (intent != null ? " with intent " + intent : "")
                + (tag != null ? " with tag " + tag : "") + (named != null ? " named " + named : "");
    }
}
