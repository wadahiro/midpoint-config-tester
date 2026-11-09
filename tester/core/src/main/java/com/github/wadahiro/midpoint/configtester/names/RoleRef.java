package com.github.wadahiro.midpoint.configtester.names;

/** A role of the deployment (generated constant). */
public final class RoleRef extends AssignableRef {

    public RoleRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "role";
    }

    @Override
    public String type() {
        return "RoleType";
    }
}
