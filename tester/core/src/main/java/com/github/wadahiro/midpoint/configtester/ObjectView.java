package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;

/**
 * An object as a user gets it ({@link Actor#read}): midPoint's {@code ModelService.getObject} logged in as the user,
 * which removes the items their {@code #read} (or {@code #get}) authorizations do not let them read, as the GUI's
 * details page and its lists have it. Read once, when {@link Actor#read} is called: after a change, read it again. The
 * assertions compare as {@link Focus#hasItem} does (as text, a polystring by its original, in any order); a failed one
 * shows the values the object has for the administrator and the user's authorizations.
 *
 * <pre>{@code
 * t.actor(Users.HELPDESK).read(Users.JACK).hasItem("telephoneNumber", "+1 555 0100").hasNoItem("costCenter")
 * assertThat(t.actor(Users.HELPDESK).read(Users.JACK).values("extension/site")).containsExactly("Prague")
 * }</pre>
 */
public final class ObjectView {

    private final MidPointConfigTester tester;
    private final Actor actor;
    private final Class<? extends ObjectType> type;
    /** As the user got it. */
    private final ObjectType object;

    ObjectView(MidPointConfigTester tester, Actor actor, Class<? extends ObjectType> type, ObjectType object) {
        this.tester = tester;
        this.actor = actor;
        this.type = type;
        this.object = object;
    }

    /** The object's name. */
    public String name() {
        return object.getName() != null ? object.getName().getOrig() : object.getOid();
    }

    public String oid() {
        return object.getOid();
    }

    /** The object as the user got it, the items they may not read removed. */
    public ObjectType asObject() {
        return object;
    }

    /**
     * The values of the item the user reads, as text ({@code telephoneNumber}, {@code extension/site}: names without
     * namespace); none when the item is empty or the user may not read it.
     */
    public List<String> values(String path) {
        return Values.texts(ItemReading.realValues(object.asPrismObject(), path));
    }

    /** The user reads exactly these values of the item, in any order; no values: they read none (see {@link #hasNoItem}). */
    public ObjectView hasItem(String path, Object... expected) {
        List<String> actual = values(path);
        List<String> wanted = Values.texts(ItemReading.expected(tester.deployment().api().prismContext(), type, path,
                expected));
        if (!Values.sameValues(wanted, actual)) {
            throw new AssertionError(subject() + ": " + path + " expected " + wanted + " but the user reads " + actual
                    + stored(path));
        }
        return this;
    }

    /** The user reads no value of the item: it is empty, or their authorizations do not let them read it. */
    public ObjectView hasNoItem(String path) {
        List<String> actual = values(path);
        if (!actual.isEmpty()) {
            throw new AssertionError(subject() + ": expected no " + path + " but the user reads " + actual);
        }
        return this;
    }

    /** {@link #hasItem(String, Object...)} by the generated item ({@code UserExtension.SITE}). */
    public ObjectView hasItem(ItemRef item, Object... expected) {
        return hasItem(item(item), expected);
    }

    public ObjectView hasNoItem(ItemRef item) {
        return hasNoItem(item(item));
    }

    public List<String> values(ItemRef item) {
        return values(item(item));
    }

    @Override
    public String toString() {
        return name() + " as " + actor.name() + " reads it";
    }

    private String item(ItemRef item) {
        if (!type.getSimpleName().equals(item.ownerType()) && !ObjectType.class.equals(type)) {
            throw new IllegalArgumentException(item + " is not an item of " + type.getSimpleName());
        }
        return item.on(tester.line());
    }

    /** {@code helpdesk reading UserType jack}. */
    private String subject() {
        return actor.name() + " reading " + object.getClass().getSimpleName() + " " + name();
    }

    /** The values stored (as the administrator reads them), when they differ from the user's. */
    private String stored(String path) {
        ObjectType all = tester.deployment().api().find(type, object.getOid());
        if (all == null) {
            return "";
        }
        List<String> values = Values.texts(new ArrayList<>(ItemReading.realValues(all.asPrismObject(), path)));
        return values.equals(values(path)) ? "" : "; the object has " + values
                + " (an item the user may not read is removed)";
    }
}
