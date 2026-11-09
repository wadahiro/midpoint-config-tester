package com.github.wadahiro.midpoint.configtester.names;

/** An archetype of the deployment (generated constant). */
public final class ArchetypeRef extends AssignableRef {

    public ArchetypeRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "archetype";
    }

    @Override
    public String type() {
        return "ArchetypeType";
    }
}
