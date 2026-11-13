package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;

/** A service of the scenario by name ({@link MidPointConfigTester#service}), read anew by every call. */
public final class Service extends AbstractRole<Service> {

    Service(MidPointConfigTester tester, String name) {
        super(tester, ServiceType.class, "Service", name);
    }

    @Override
    Service self() {
        return this;
    }

    @Override
    public ServiceType asObject() {
        return (ServiceType) super.asObject();
    }
}
