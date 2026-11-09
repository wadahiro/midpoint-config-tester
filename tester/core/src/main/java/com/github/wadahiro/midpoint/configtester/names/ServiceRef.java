package com.github.wadahiro.midpoint.configtester.names;

/** A service of the deployment (generated constant). */
public final class ServiceRef extends AssignableRef {

    public ServiceRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "service";
    }

    @Override
    public String type() {
        return "ServiceType";
    }
}
