package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/** A user to add through the model ({@link User#add(java.util.function.Consumer)}). */
public final class NewUser extends NewFocus<NewUser, UserType> {

    NewUser(MidPointConfigTester tester, String name) {
        super(tester, UserType.class, name);
    }

    @Override
    NewUser self() {
        return this;
    }

    public NewUser fullName(String fullName) {
        return set("fullName", fullName);
    }

    public NewUser givenName(String givenName) {
        return set("givenName", givenName);
    }

    public NewUser familyName(String familyName) {
        return set("familyName", familyName);
    }

    public NewUser emailAddress(String emailAddress) {
        return set("emailAddress", emailAddress);
    }

    /**
     * The password, as clear text; midPoint checks it against the password policy when the user is added (a value
     * that violates it fails: {@link MidPointConfigTester#expectFailure}).
     */
    public NewUser password(String password) {
        com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType value =
                new com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType();
        value.setClearValue(password);
        return set("credentials/password/value", value);
    }
}
