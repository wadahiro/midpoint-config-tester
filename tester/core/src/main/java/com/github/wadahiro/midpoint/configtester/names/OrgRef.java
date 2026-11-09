package com.github.wadahiro.midpoint.configtester.names;

/** An org of the deployment (generated constant). */
public final class OrgRef extends AssignableRef {

    public OrgRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "org";
    }

    @Override
    public String type() {
        return "OrgType";
    }
}
