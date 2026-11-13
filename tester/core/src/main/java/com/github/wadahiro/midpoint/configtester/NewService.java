package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;

/** A service to add through the model ({@link Service#add(java.util.function.Consumer)}). */
public final class NewService extends NewFocus<NewService, ServiceType> {

    NewService(MidPointConfigTester tester, String name) {
        super(tester, ServiceType.class, name);
    }

    @Override
    NewService self() {
        return this;
    }
}
