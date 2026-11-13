package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.prism.path.ItemPath;
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

    /**
     * Sets the user's password, as an administrator in the GUI would; midPoint checks it against the password policy
     * of the user's security policy (a value that violates it fails: {@link MidPointConfigTester#expectFailure}).
     */
    public Effects setPassword(String password) {
        com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType value =
                new com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType();
        value.setClearValue(password);
        return tester.effectsOf(() -> tester.deployment().api().modify(UserType.class, name,
                ItemPath.create(UserType.F_CREDENTIALS,
                        com.evolveum.midpoint.xml.ns._public.common.common_3.CredentialsType.F_PASSWORD,
                        com.evolveum.midpoint.xml.ns._public.common.common_3.PasswordType.F_VALUE), value));
    }
}
