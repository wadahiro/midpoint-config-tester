package com.github.wadahiro.midpoint.configtester;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An object on a resource's system, read anew by every call: an {@link Account}, a {@link Group}, or an object of
 * another class ({@link Resource#object}). The views ({@link #value}, {@link #values}, {@link #attributes()}) give
 * the values for AssertJ; a failed test then shows the outbound mappings behind the values it read. The assertions
 * return the object for chaining and fail with what is there and the mappings behind it.
 */
public class TargetObject {

    final MidPointConfigTester tester;
    final Resource resource;
    /** The object class by the resource's name for it; null: the class of the kind ({@link Account}, {@link Group}). */
    final String objectClass;
    final String name;

    TargetObject(MidPointConfigTester tester, Resource resource, String objectClass, String name) {
        this.tester = tester;
        this.resource = resource;
        this.objectClass = objectClass;
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * The mappings that set the attribute of this object in this test (outbounds of the resource, constructions of
     * roles), for the focus that owns it (any focus when it has none), oldest first.
     */
    public Why why(String attribute) {
        String owner = owner();
        return Why.of(tester, attribute, owner, attribute + " of " + name + " on " + resource.name());
    }

    /** The name of the user the object's shadow is linked to; null when none. */
    String owner() {
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

    public TargetObject exists() {
        existing();
        return this;
    }

    public TargetObject doesNotExist() {
        Map<String, List<Object>> object = read();
        if (object != null) {
            throw new AssertionError("Expected no " + what().toLowerCase(java.util.Locale.ROOT) + " " + name + " on "
                    + resource.name() + ", but it exists: " + new TreeMap<>(object));
        }
        return this;
    }

    /** The attribute has exactly these values, in any order (compared as text); no values: it has none. */
    public TargetObject hasAttribute(String attribute, Object... expected) {
        List<String> actual = attributeValues(attribute);
        List<String> wanted = Values.texts(expected);
        if (!Values.sameValues(wanted, actual)) {
            throw new AssertionError(what() + " " + name + " on " + resource.name() + ": " + attribute + " expected "
                    + wanted + " but was " + actual + "; attributes: " + new TreeMap<>(existing())
                    + Why.mappings(tester, attribute, owner(), attribute + " on " + resource.name()));
        }
        return this;
    }

    public TargetObject hasNoAttribute(String attribute) {
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
            throw new AssertionError(what() + " " + name + " on " + resource.name() + ": " + attribute
                    + " has several values " + values);
        }
        return values.isEmpty() ? null : values.get(0);
    }

    /** All attributes, by name, as text, sorted by name. */
    public Map<String, List<String>> attributes() {
        Map<String, List<String>> out = new TreeMap<>();
        existing().forEach((attribute, values) -> out.put(attribute, Values.texts(values)));
        return out;
    }

    @Override
    public String toString() {
        return name + " on " + resource.name();
    }

    // ---- what the kinds change --------------------------------------------------------------------------------

    /** The word the messages name the object with. */
    String what() {
        return "Object " + objectClass;
    }

    /** All attributes of the object, or null if there is none. */
    Map<String, List<Object>> read() {
        return resource.system().find(objectClass, name);
    }

    /** The names of the objects of its class, for a failure. */
    List<String> others() {
        return resource.system().objectNames(objectClass);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    Map<String, List<Object>> existing() {
        Map<String, List<Object>> object = read();
        if (object == null) {
            List<String> names = others();
            String word = what().toLowerCase(java.util.Locale.ROOT);
            throw new AssertionError("Expected " + (word.matches("^[aeiou].*") ? "an " : "a ") + word + " " + name
                    + " on " + resource.name() + ", but there is none; " + plural(word) + ": "
                    + (names.size() > 20 ? names.subList(0, 20) + "..." : names));
        }
        return object;
    }

    private static String plural(String word) {
        return word.startsWith("object") ? "objects" : word + "s";
    }

    private List<String> attributeValues(String attribute) {
        return Values.texts(existing().get(attribute));
    }
}
