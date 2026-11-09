package com.github.wadahiro.midpoint.configtester.names;

/** A resource of the deployment (generated constant). */
public final class ResourceRef extends ObjectRef {

    public ResourceRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "resource";
    }

    @Override
    public String type() {
        return "ResourceType";
    }
}
