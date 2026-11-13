package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;

/** A role to add through the model ({@link Role#add(java.util.function.Consumer)}). */
public final class NewRole extends NewFocus<NewRole, RoleType> {

    NewRole(MidPointConfigTester tester, String name) {
        super(tester, RoleType.class, name);
    }

    @Override
    NewRole self() {
        return this;
    }
}
