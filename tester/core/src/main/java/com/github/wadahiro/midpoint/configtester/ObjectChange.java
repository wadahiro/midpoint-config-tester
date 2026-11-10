package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a block did to one object of the repository ({@link Changes}): added, modified or deleted it and, for a
 * modification, the items it changed ({@link #items()}), e.g. {@code activation/validTo}: the paths are the
 * names' local parts without container IDs. midPoint's housekeeping ({@code metadata}, {@code operationExecution}) is
 * not among them; several writes to the object in the block are one change.
 */
public final class ObjectChange {

    /** What the block did to the object. */
    public enum Kind { ADDED, MODIFIED, DELETED }

    private final String type;
    private final String oid;
    private final String name;
    private final Kind kind;
    private final List<Item> items;

    ObjectChange(String type, String oid, String name, Kind kind, List<Item> items) {
        this.type = type;
        this.oid = oid;
        this.name = name;
        this.kind = kind;
        this.items = Collections.unmodifiableList(items);
    }

    /** The object type's simple name, e.g. {@code UserType}, {@code ShadowType}. */
    public String type() {
        return type;
    }

    public String oid() {
        return oid;
    }

    /** The object's name (its last name, for a renamed one), or null if the tester could not read it. */
    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** The changed items, in the order written. */
    public List<Item> items() {
        return items;
    }

    /** The paths of the changed items, each once, in the order first written. */
    public List<String> paths() {
        Set<String> out = new LinkedHashSet<>();
        items.forEach(item -> out.add(item.path()));
        return new ArrayList<>(out);
    }

    /**
     * Checks that the block changed exactly these items of the object: each of them, and nothing else; fails listing
     * the difference.
     */
    public ObjectChange only(String... paths) {
        List<String> expected = Arrays.asList(paths);
        List<String> changed = paths();
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(changed);
        List<String> extra = new ArrayList<>();
        for (Item item : items) {
            if (!expected.contains(item.path())) {
                extra.add(item.toString());
            }
        }
        if (!missing.isEmpty() || !extra.isEmpty()) {
            StringBuilder message = new StringBuilder(describe() + ": expected only " + expected + " to change");
            if (!missing.isEmpty()) {
                message.append(", but ").append(missing).append(" did not");
            }
            if (!extra.isEmpty()) {
                message.append(", but also: ").append(String.join("; ", extra));
            }
            throw new AssertionError(message.toString());
        }
        return this;
    }

    String describe() {
        return type + " " + (name != null ? name : oid);
    }

    @Override
    public String toString() {
        String what = kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + describe();
        if (kind != Kind.MODIFIED || items.isEmpty()) {
            return what;
        }
        List<String> out = new ArrayList<>();
        items.forEach(item -> out.add(item.toString()));
        return what + " (" + String.join("; ", out) + ")";
    }

    /** One item delta: the values added, deleted and replaced, as text. */
    public static final class Item {

        private final String path;
        private final List<String> added;
        private final List<String> deleted;
        private final List<String> replaced;
        private final boolean replace;

        Item(String path, List<String> added, List<String> deleted, List<String> replaced, boolean replace) {
            this.path = path;
            this.added = Collections.unmodifiableList(added);
            this.deleted = Collections.unmodifiableList(deleted);
            this.replaced = Collections.unmodifiableList(replaced);
            this.replace = replace;
        }

        public String path() {
            return path;
        }

        public List<String> added() {
            return added;
        }

        public List<String> deleted() {
            return deleted;
        }

        /** The values the item was set to ({@code replace}; empty for a replace that removes every value). */
        public List<String> replaced() {
            return replaced;
        }

        @Override
        public String toString() {
            List<String> out = new ArrayList<>();
            if (replace) {
                out.add("= " + replaced);
            }
            if (!added.isEmpty()) {
                out.add("+ " + added);
            }
            if (!deleted.isEmpty()) {
                out.add("- " + deleted);
            }
            return path + " " + String.join(" ", out);
        }
    }
}
