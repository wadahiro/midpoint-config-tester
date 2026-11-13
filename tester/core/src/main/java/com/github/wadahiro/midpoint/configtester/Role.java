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
    NewRole newFocus() {
        return new NewRole(tester, name);
    }

    /**
     * Adds the role through the model with what the spec gives it besides its name, e.g.
     * {@code t.role("Auditor").add(r -> r.set("description", "audits"))}; returns what midPoint did.
     */
    public Effects add(java.util.function.Consumer<NewRole> spec) {
        NewRole focus = newFocus();
        spec.accept(focus);
        return add(focus);
    }

    @Override
    public RoleType asObject() {
        return (RoleType) super.asObject();
    }
}
