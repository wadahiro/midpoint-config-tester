package com.github.wadahiro.midpoint.configtester.names;

/** A user the configuration holds, of the deployment (generated constant). */
public final class UserRef extends ObjectRef {

    public UserRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }

    @Override
    public String kind() {
        return "user";
    }

    @Override
    public String type() {
        return "UserType";
    }
}
