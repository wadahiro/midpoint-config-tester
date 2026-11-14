package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * A user of the scenario by name ({@link MidPointConfigTester#user}), read anew by every call: a {@link Focus}, with
 * what only users have. {@link #asObject()} gives the user as midPoint has it now, a plain {@code UserType} to check
 * with any assertion library (with AssertJ: {@code configtester.assertj}).
 */
public final class User extends Focus<User> {

    User(MidPointConfigTester tester, String name) {
        super(tester, UserType.class, "User", name);
    }

    @Override
    User self() {
        return this;
    }

    @Override
    NewUser newFocus() {
        return new NewUser(tester, name);
    }

    /**
     * Adds the user through the model with what the spec gives it besides its name, e.g.
     * {@code t.user("jdoe").add(u -> u.fullName("John Doe").assign("Sales"))}; returns what midPoint did.
     */
    public Effects add(java.util.function.Consumer<NewUser> spec) {
        NewUser focus = newFocus();
        spec.accept(focus);
        return add(focus);
    }

    public User hasFullName(String expected) {
        return hasItem("fullName", expected);
    }

    /**
     * The user as midPoint has it now (read anew by every call: after a change, call it again), as the scripts of the
     * configuration see it: {@code assertThat(t.user("e1001").asObject().getCostCenter()).isEqualTo("Sales")}.
     */
    @Override
    public UserType asObject() {
        return (UserType) super.asObject();
    }

}
