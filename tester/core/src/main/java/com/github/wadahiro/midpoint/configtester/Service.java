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
    NewService newFocus() {
        return new NewService(tester, name);
    }

    /**
     * Adds the service through the model with what the spec gives it besides its name, e.g.
     * {@code t.service("Directory").add(s -> s.set("description", "the LDAP"))}; returns what midPoint did.
     */
    public Effects add(java.util.function.Consumer<NewService> spec) {
        NewService focus = newFocus();
        spec.accept(focus);
        return add(focus);
    }

    @Override
    public ServiceType asObject() {
        return (ServiceType) super.asObject();
    }
}
