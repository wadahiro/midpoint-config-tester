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
    /** A Groovy expression that makes the identifier of a created object, or null (see {@link #withUidValue}). */
    public final String uidValue;
    /**
     * Whether the target system issues the identifier, though it has no native name ({@link #uidAttribute} null:
     * midPoint shows ConnId's {@code __UID__} as {@code icfs:uid}, apart from the name); see {@link #withGeneratedUid}.
     */
    public final boolean generatedConnIdUid;

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
        this(midPointName, connIdName, namingAttribute, uidAttribute, activation, password, validFrom, validTo,
                passwordReading, passwordReturnedByDefault, attributes, computed, null, false);
    }

    private MirrorClass(String midPointName, String connIdName, String namingAttribute, String uidAttribute,
            boolean activation, boolean password, boolean validFrom, boolean validTo,
            PasswordReading passwordReading, boolean passwordReturnedByDefault,
            Map<String, MirrorAttribute> attributes, Map<String, MirrorLink> computed, String uidValue,
            boolean generatedConnIdUid) {
        this.uidValue = uidValue;
        this.generatedConnIdUid = generatedConnIdUid;
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
                validFrom, validTo, passwordReading, passwordReturnedByDefault, newAttributes, newComputed, uidValue,
                generatedConnIdUid);
    }

    /** This class with one more attribute, multi-valued, read only (the target system's, not computed here). */
    public MirrorClass withReadOnlyAttribute(String attribute) {
        Map<String, MirrorAttribute> newAttributes = new LinkedHashMap<>(attributes);
        newAttributes.putIfAbsent(attribute, new MirrorAttribute(attribute, String.class, true, true, false, false));
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, newAttributes, computed, uidValue,
                generatedConnIdUid);
    }

    /**
     * This class with the attribute (which it must have) keeping one value per key (see
     * {@link MirrorAttribute#keySeparator}).
     */
    public MirrorClass withKeyedValues(String attribute, String separator) {
        return withAttribute(attribute, a -> a.withKeySeparator(separator));
    }

    /**
     * This class with the attribute (which it must have) kept as the expression says
     * ({@link MirrorAttribute#storeExpression}).
     */
    public MirrorClass withStoreExpression(String attribute, String expression) {
        return withAttribute(attribute, a -> a.withStoreExpression(expression));
    }

    private MirrorClass withAttribute(String attribute,
            java.util.function.UnaryOperator<MirrorAttribute> change) {
        MirrorAttribute a = attributes.get(attribute);
        if (a == null) {
            throw new IllegalArgumentException("no attribute " + attribute + " in " + midPointName + " ("
                    + String.join(", ", attributes.keySet()) + ")");
        }
        Map<String, MirrorAttribute> newAttributes = new LinkedHashMap<>(attributes);
        newAttributes.put(attribute, change.apply(a));
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, newAttributes, computed, uidValue,
                generatedConnIdUid);
    }

    /** This class with the validity dates of the activation capability (or without them). */
    public MirrorClass withValidity(boolean validFrom, boolean validTo) {
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, attributes, computed, uidValue,
                generatedConnIdUid);
    }

    /** This class with the password read back as the real connector does it (see {@link PasswordReading}). */
    public MirrorClass withPasswordReading(PasswordReading reading, boolean returnedByDefault) {
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, reading, returnedByDefault, attributes, computed, uidValue, generatedConnIdUid);
    }

    /**
     * This class with its identifier ({@link #uidAttribute}, which it must have) made on create by a Groovy
     * expression of {@code name}, {@code attributes} and {@code now}, as the target system would issue it.
     */
    public MirrorClass withUidValue(String expression) {
        if (!generatedUid()) {
            throw new IllegalArgumentException("A generated identifier needs an identifier attribute: " + midPointName);
        }
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, attributes, computed, expression,
                generatedConnIdUid);
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

    /**
     * This class with an identifier the target system issues on create (a random UUID, or {@link #withUidValue}),
     * apart from the name, though the identifier has no native name: ConnId's {@code __UID__} as midPoint shows it,
     * {@code icfs:uid}, when the schema does not say whether it is the name.
     */
    public MirrorClass withGeneratedUid() {
        return new MirrorClass(midPointName, connIdName, namingAttribute, uidAttribute, activation, password,
                validFrom, validTo, passwordReading, passwordReturnedByDefault, attributes, computed, uidValue, true);
    }

    /**
     * Whether the identifier is generated (like entryUUID), rather than being the name. An identifier whose native
     * name is the naming attribute's (a connector that gives {@code __UID__} and {@code __NAME__} the same native
     * attribute, e.g. a user's {@code username}) is the name.
     */
    public boolean generatedUid() {
        return uidAttribute != null && !uidAttribute.equals(namingAttribute) || generatedConnIdUid;
    }

    /** Whether the class declares ConnId's {@code __UID__} in its schema (an identifier of its own, or the name's). */
    public boolean declaresUid() {
        return uidAttribute != null || generatedConnIdUid;
    }
}
