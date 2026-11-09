package com.github.wadahiro.midpoint.tester.mirror;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One object on a mirrored resource. Attribute values are stored as the connector receives them. */
public final class MirrorObject implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    public String uid;
    public String name;
    public Boolean enabled;
    public String password;
    public final Map<String, List<Object>> attributes = new LinkedHashMap<>();

    MirrorObject copy() {
        MirrorObject copy = new MirrorObject();
        copy.uid = uid;
        copy.name = name;
        copy.enabled = enabled;
        copy.password = password;
        attributes.forEach((k, v) -> copy.attributes.put(k, new ArrayList<>(v)));
        return copy;
    }

    /** Whether the other object has the same name, status, password and attributes (live synchronization's diff). */
    boolean sameContent(MirrorObject other) {
        return java.util.Objects.equals(name, other.name) && java.util.Objects.equals(enabled, other.enabled)
                && java.util.Objects.equals(password, other.password) && attributes.equals(other.attributes);
    }
}
