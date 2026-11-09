package com.github.wadahiro.midpoint.tester.mirror;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One object class of a mirrored resource: the name midPoint shows ({@code inetOrgPerson}, {@code AccountObjectClass}),
 * the name the connector uses on the ConnId level ({@code inetOrgPerson}, {@code __ACCOUNT__}), and the native names
 * of the ConnId {@code __NAME__} / {@code __UID__} attributes (null: midPoint shows them as icfs:name / icfs:uid).
 * Computed attributes are also in {@link #attributes} (read-only); their values come from the {@link MirrorLink}.
 */
public final class MirrorClass {

    public final String midPointName;
    public final String connIdName;
    public final String namingAttribute;
    public final String uidAttribute;
    public final boolean activation;
    public final boolean password;
    /** Whether the class has ConnId's {@code __ENABLE_DATE__} / {@code __DISABLE_DATE__} (validFrom / validTo). */
    public final boolean validFrom;
    public final boolean validTo;
    /** How the password ({@link #password}) is read back, as the real connector returns it. */
    public final PasswordReading passwordReading;
    /** Whether a readable password is returned without being asked for (ConnId's returned-by-default flag). */
    public final boolean passwordReturnedByDefault;
    public final Map<String, MirrorAttribute> attributes;
    /** Computed attribute -> the link whose holders it lists (like memberOf from member). */
    public final Map<String, MirrorLink> computed;

    public MirrorClass(String midPointName, String connIdName, String namingAttribute, String uidAttribute,
            boolean activation, boolean password, Map<String, MirrorAttribute> attributes) {
        this(midPointName, connIdName, namingAttribute, uidAttribute, activation, password, attributes, Map.of());
    }

    public MirrorClass(String midPointName, String connIdName, String namingAttribute, String uidAttribute,
            boolean activation, boolean password, Map<String, MirrorAttribute> attributes,
            Map<String, MirrorLink> computed) {
        this(midPointName, connIdName, namingAttribute, uidAttribute, activation, password, false, false, attributes,
                computed);
    }

    public MirrorClass(String midPointName, String connIdName, String namingAttribute, String uidAttribute,
            boolean activation, boolean password, boolean validFrom, boolean validTo,
            Map<String, MirrorAttribute> attributes, Map<String, MirrorLink> computed) {
        this(midPointName, connIdName, namingAttribute, uidAttribute, activation, password, validFrom, validTo,
                PasswordReading.UNREADABLE, false, attributes, computed);
    }

    public MirrorClass(String midPointName, String connIdName, String namingAttribute, String uidAttribute,
            boolean activation, boolean password, boolean validFrom, boolean validTo,
            PasswordReading passwordReading, boolean passwordReturnedByDefault,
            Map<String, MirrorAttribute> attributes, Map<String, MirrorLink> computed) {
        this.passwordReading = password ? passwordReading : PasswordReading.UNREADABLE;
        this.passwordReturnedByDefault = password && passwordReading != PasswordReading.UNREADABLE
                && passwordReturnedByDefault;
        this.midPointName = midPointName;
        this.connIdName = connIdName;
        this.namingAttribute = namingAttribute;
        this.uidAttribute = uidAttribute;
        this.activation = activation;
        this.password = password;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.computed = Collections.unmodifiableMap(new LinkedHashMap<>(computed));
    }

    /** This class with one more computed attribute (multi-valued, read-only strings). */
    public MirrorClass withComputed(String attribute, MirrorLink link) {
        Map<String, MirrorAttribute> newAttributes = new LinkedHashMap<>(attributes);
        newAttributes.put(attribute, new MirrorAttribute(attribute, String.class, true, true, false, false));
        Map<String, MirrorLink> newComputed = new LinkedHashMap<>(computed);
        newComputed.put(attribute, link);
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, newAttributes, newComputed);
    }

    /** This class with one more attribute, multi-valued, read only (the target system's, not computed here). */
    public MirrorClass withReadOnlyAttribute(String attribute) {
        Map<String, MirrorAttribute> newAttributes = new LinkedHashMap<>(attributes);
        newAttributes.putIfAbsent(attribute, new MirrorAttribute(attribute, String.class, true, true, false, false));
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, newAttributes, computed);
    }

    /** This class with the validity dates of the activation capability (or without them). */
    public MirrorClass withValidity(boolean validFrom, boolean validTo) {
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, attributes, computed);
    }

    /** This class with the password read back as the real connector does it (see {@link PasswordReading}). */
    public MirrorClass withPasswordReading(PasswordReading reading, boolean returnedByDefault) {
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, reading, returnedByDefault, attributes, computed);
    }

    /**
     * What a search returns of the password, as ConnId's {@code __PASSWORD__} of the real connector: nothing (ConnId's
     * default, not readable), the value as stored (e.g. the CSV connector's password column, the LDAP connector's
     * {@code passwordReadStrategy} {@code readable}), or that there is a value without it (LDAP's
     * {@code incompleteRead}: midPoint marks the shadow's password incomplete).
     */
    public enum PasswordReading {
        UNREADABLE, READABLE, INCOMPLETE
    }

    /** Whether the identifier is generated (like entryUUID), rather than being the name. */
    public boolean generatedUid() {
        return uidAttribute != null;
    }
}
