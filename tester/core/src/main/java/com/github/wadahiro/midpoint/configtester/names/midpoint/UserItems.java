package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.configtester.names.Lines;

/**
 * Items of midPoint's own user schema ({@code UserType}), for {@code s.user(name).hasItem/values/why}: the path
 * without namespaces, as midPoint's schema of the line has it ({@code InitialNamesTest} checks each on every line).
 * Items of a deployment's extension schema are generated from it ({@code UserExtension}).
 */
public final class UserItems {

    public static final ItemRef NAME = item("name");
    public static final ItemRef DESCRIPTION = item("description");
    public static final ItemRef LIFECYCLE_STATE = item("lifecycleState");
    public static final ItemRef SUBTYPE = item("subtype");

    public static final ItemRef FULL_NAME = item("fullName");
    public static final ItemRef GIVEN_NAME = item("givenName");
    public static final ItemRef FAMILY_NAME = item("familyName");
    public static final ItemRef ADDITIONAL_NAME = item("additionalName");
    public static final ItemRef NICK_NAME = item("nickName");
    public static final ItemRef HONORIFIC_PREFIX = item("honorificPrefix");
    public static final ItemRef HONORIFIC_SUFFIX = item("honorificSuffix");
    public static final ItemRef TITLE = item("title");
    public static final ItemRef PREFERRED_LANGUAGE = item("preferredLanguage");
    public static final ItemRef LOCALE = item("locale");
    public static final ItemRef TIMEZONE = item("timezone");
    public static final ItemRef EMAIL_ADDRESS = item("emailAddress");
    public static final ItemRef TELEPHONE_NUMBER = item("telephoneNumber");
    public static final ItemRef EMPLOYEE_NUMBER = item("employeeNumber");
    public static final ItemRef COST_CENTER = item("costCenter");
    public static final ItemRef LOCALITY = item("locality");
    public static final ItemRef ORGANIZATION = item("organization");
    public static final ItemRef ORGANIZATIONAL_UNIT = item("organizationalUnit");
    /** 4.0 only: replaced by {@code subtype} and archetypes. */
    public static final ItemRef EMPLOYEE_TYPE = item("employeeType", Lines.only("4.0"));
    /** 4.8+. */
    public static final ItemRef PERSONAL_NUMBER = item("personalNumber", Lines.only("4.8", "4.9", "4.10"));

    public static final ItemRef ADMINISTRATIVE_STATUS = item("activation/administrativeStatus");
    public static final ItemRef EFFECTIVE_STATUS = item("activation/effectiveStatus");
    public static final ItemRef VALID_FROM = item("activation/validFrom");
    public static final ItemRef VALID_TO = item("activation/validTo");
    public static final ItemRef VALIDITY_STATUS = item("activation/validityStatus");
    public static final ItemRef DISABLE_REASON = item("activation/disableReason");
    public static final ItemRef DISABLE_TIMESTAMP = item("activation/disableTimestamp");
    public static final ItemRef ENABLE_TIMESTAMP = item("activation/enableTimestamp");
    public static final ItemRef LOCKOUT_STATUS = item("activation/lockoutStatus");

    private UserItems() {
    }

    private static ItemRef item(String path) {
        return item(path, Lines.ALL);
    }

    private static ItemRef item(String path, Lines lines) {
        return new ItemRef("UserType", null, path, lines);
    }
}
