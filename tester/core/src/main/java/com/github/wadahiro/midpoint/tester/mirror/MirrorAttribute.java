package com.github.wadahiro.midpoint.tester.mirror;

/** One attribute of a mirrored object class, as the real connector declares it. */
public final class MirrorAttribute {

    public final String name;
    public final Class<?> type;
    public final boolean multiValued;
    public final boolean readable;
    public final boolean creatable;
    public final boolean updatable;

    public MirrorAttribute(String name, Class<?> type, boolean multiValued, boolean readable, boolean creatable,
            boolean updatable) {
        this.name = name;
        this.type = type;
        this.multiValued = multiValued;
        this.readable = readable;
        this.creatable = creatable;
        this.updatable = updatable;
    }
}
