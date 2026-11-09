package com.github.wadahiro.midpoint.configtester.names;

/** An object a user can be assigned: a role, an org, a service, an archetype. */
public abstract class AssignableRef extends ObjectRef {

    AssignableRef(String name, String oid, Lines lines) {
        super(name, oid, lines);
    }
}
