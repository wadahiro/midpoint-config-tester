package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;

/**
 * A projection a focus links on a resource ({@link Focus#accounts}, {@link Focus#entitlements},
 * {@link Focus#projections}), in its two places: its shadow in the repository ({@link #shadow()}) and the object on
 * the target system ({@link #onTarget()}). Both are read as they are, without midPoint's provisioning (which would act
 * on what it finds), so that a precondition may check a state midPoint would repair:
 *
 * <pre>{@code
 * t.user("jdoe").account("Directory").shadow().hasTag("legacy");
 * t.user("jdoe").account("Directory").onTarget().exists().hasAttribute("mail", "jdoe@example.com");
 * t.role("Sales").entitlement("Directory").onTarget().hasMember("uid=jdoe,ou=people,dc=example,dc=com");
 * }</pre>
 *
 * @param <T> what it is on the target system: an {@link Account}, a {@link Group}, or any {@link TargetObject}
 */
public final class Projection<T extends TargetObject> {

    /** How the object on the target system is read: by the shadow's object class (null without a shadow). */
    interface Target<T extends TargetObject> {
        T of(Resource resource, ShadowType shadow, String name);
    }

    static final Target<Account> ACCOUNT = (resource, shadow, name) -> resource.account(name);

    static final Target<Group> GROUP = (resource, shadow, name) -> new Group(resource.tester(), resource,
            objectClass(shadow), name);

    /** By the shadow's kind: an account, a group, or an object of its class. */
    static final Target<TargetObject> ANY = (resource, shadow, name) -> {
        if (shadow == null) {
            throw new AssertionError("No live shadow " + name + " on " + resource.name()
                    + " is linked: name its kind, accounts(...) or entitlements(...), to read it on the target system");
        }
        String kind = Projections.kindOf(shadow);
        if ("account".equals(kind)) {
            return ACCOUNT.of(resource, shadow, name);
        }
        if ("entitlement".equals(kind)) {
            return GROUP.of(resource, shadow, name);
        }
        return new TargetObject(resource.tester(), resource, objectClass(shadow), name);
    };

    private final Focus<?> owner;
    private final String resource;
    private final String name;
    private final Target<T> target;

    Projection(Focus<?> owner, String resource, String name, Target<T> target) {
        this.owner = owner;
        this.resource = resource;
        this.name = name;
        this.target = target;
    }

    /** The projection's name on the target system (its shadow's name). */
    public String name() {
        return name;
    }

    /** The projection's shadow in the repository, as the owner links it. */
    public RepositoryShadow shadow() {
        return new RepositoryShadow(owner, resource, name);
    }

    /** The object on the target system, read by its shadow's object class: it need not exist there. */
    public T onTarget() {
        return target.of(owner.tester.target(resource), shadow().live(), name);
    }

    /** The shadow's kind ({@code account}, {@code entitlement}, {@code generic}). */
    public String kind() {
        return shadow().kind();
    }

    public String intent() {
        return shadow().intent();
    }

    public String tag() {
        return shadow().tag();
    }

    @Override
    public String toString() {
        return name + " on " + resource + " of " + owner;
    }

    private static String objectClass(ShadowType shadow) {
        return shadow != null && shadow.getObjectClass() != null ? shadow.getObjectClass().getLocalPart() : null;
    }
}
