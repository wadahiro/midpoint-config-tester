package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;

/** A role of the scenario by name ({@link MidPointConfigTester#role}), read anew by every call. */
public final class Role extends AbstractRole<Role> {

    Role(MidPointConfigTester tester, String name) {
        super(tester, RoleType.class, "Role", name);
    }

    @Override
    Role self() {
        return this;
    }

    @Override
    public RoleType asObject() {
        return (RoleType) super.asObject();
    }
}
