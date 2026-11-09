package com.github.wadahiro.midpoint.tester.mirror;

/** One attribute of a mirrored object class, as the real connector declares it. */
public final class MirrorAttribute {

    public final String name;
    public final Class<?> type;
    public final boolean multiValued;
    public final boolean readable;
    public final boolean creatable;
    public final boolean updatable;
    /**
     * For values {@code key<separator>value} the target system keeps one per key (a map, like Guacamole's connection
     * parameters): a value added replaces the value of its key, a value removed removes its key. Null: plain values.
     */
    public final String keySeparator;
    /**
     * The Groovy expression that makes the values the target system keeps when they are written (see
     * {@code ResourceOptions.store}), or null.
     */
    public final String storeExpression;

    public MirrorAttribute(String name, Class<?> type, boolean multiValued, boolean readable, boolean creatable,
            boolean updatable) {
        this(name, type, multiValued, readable, creatable, updatable, null, null);
    }

    private MirrorAttribute(String name, Class<?> type, boolean multiValued, boolean readable, boolean creatable,
            boolean updatable, String keySeparator, String storeExpression) {
        this.keySeparator = keySeparator;
        this.storeExpression = storeExpression;
        this.name = name;
        this.type = type;
        this.multiValued = multiValued;
        this.readable = readable;
        this.creatable = creatable;
        this.updatable = updatable;
    }

    /** This attribute, multi-valued, with one value per key (see {@link #keySeparator}). */
    public MirrorAttribute withKeySeparator(String separator) {
        return new MirrorAttribute(name, type, true, readable, creatable, updatable, separator, null);
    }

    /** This attribute, multi-valued, whose kept values the expression makes (see {@link #storeExpression}). */
    public MirrorAttribute withStoreExpression(String expression) {
        return new MirrorAttribute(name, type, true, readable, creatable, updatable, null, expression);
    }

    /** The key of the value, or null if the attribute has no keys or the value no separator. */
    public String keyOf(Object value) {
        if (keySeparator == null || !(value instanceof String)) {
            return null;
        }
        int at = ((String) value).indexOf(keySeparator);
        return at < 0 ? null : ((String) value).substring(0, at);
    }
}
