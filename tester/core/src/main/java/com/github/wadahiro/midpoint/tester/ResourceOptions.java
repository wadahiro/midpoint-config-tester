package com.github.wadahiro.midpoint.tester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What {@link MidPoint#importResource(java.io.File, ResourceOptions)} needs to know about the real resource that its
 * definition does not say.
 *
 * <ul>
 *   <li>Which attribute names the resource's objects (ConnId's {@code __NAME__}, e.g. LDAP {@code dn}) and which is
 *       their primary identifier ({@code __UID__}, e.g. {@code entryUUID}) is normally read from the stored resource
 *       {@code <schema>} that midPoint keeps in the definition, so a definition exported from a running midPoint needs
 *       no options. Set them for hand-written definitions without a stored schema (they then apply to every object
 *       class); a definition that uses {@code icfs:name} needs none.</li>
 *   <li>What the target system does by itself, beyond storing what midPoint writes: attributes it computes from
 *       memberships ({@link #computed}), keeping references valid ({@link #referentialIntegrity()}) and matching names
 *       regardless of case ({@link #caseInsensitiveNames()}).</li>
 * </ul>
 */
public final class ResourceOptions {

    /** An attribute the target system computes, see {@link #computed}. */
    public static final class Computed {
        public final String attribute;
        public final String sourceObjectClass;
        public final String sourceAttribute;

        Computed(String attribute, String sourceObjectClass, String sourceAttribute) {
            this.attribute = localName(attribute);
            this.sourceObjectClass = localName(sourceObjectClass);
            this.sourceAttribute = localName(sourceAttribute);
        }
    }

    private final String namingAttribute;
    private final String identifier;
    private final List<Computed> computed;
    private final boolean referentialIntegrity;
    private final boolean caseInsensitiveNames;
    private final boolean realConnector;
    /** {object class, attribute}: attributes of the mirror's classes that schemaHandling does not name. */
    private final List<String[]> attributes;
    /** The naming attribute of an object class, over {@link #namingAttribute}. */
    private final java.util.Map<String, String> classNaming;
    /** A file with the resource's stored schema (as exported from a midPoint connected to the real resource). */
    private final java.nio.file.Path schema;
    /** A Groovy expression that makes the identifier of a created object (see {@link #identifier(String, String)}). */
    private final String identifierValue;
    /** How the target system keeps the values of multi-valued attributes ({@link #multiValued}, {@link #store}...). */
    private final List<StoredValues> multiValued;

    private ResourceOptions(String namingAttribute, String identifier, List<Computed> computed,
            boolean referentialIntegrity, boolean caseInsensitiveNames, boolean realConnector,
            List<String[]> attributes, java.util.Map<String, String> classNaming, java.nio.file.Path schema,
            String identifierValue, List<StoredValues> multiValued) {
        this.namingAttribute = namingAttribute;
        this.identifier = identifier;
        this.computed = Collections.unmodifiableList(computed);
        this.referentialIntegrity = referentialIntegrity;
        this.caseInsensitiveNames = caseInsensitiveNames;
        this.realConnector = realConnector;
        this.attributes = Collections.unmodifiableList(attributes);
        this.classNaming = Collections.unmodifiableMap(classNaming);
        this.schema = schema;
        this.identifierValue = identifierValue;
        this.multiValued = Collections.unmodifiableList(multiValued);
    }

    public static ResourceOptions defaults() {
        return new ResourceOptions(null, null, List.of(), false, false, false, List.of(), java.util.Map.of(), null,
                null, List.of());
    }

    /** Attribute that names objects on the resource, e.g. {@code "dn"} (a {@code ri:} attribute). */
    public ResourceOptions namingAttribute(String attribute) {
        return new ResourceOptions(attribute, identifier, computed, referentialIntegrity,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema, identifierValue, multiValued);
    }

    /**
     * Attribute that names the objects of one object class (e.g. {@code "group"}, {@code "displayName"}), over
     * {@link #namingAttribute(String)} for that class: a resource without a stored schema whose classes are named by
     * different attributes.
     */
    public ResourceOptions namingAttribute(String objectClass, String attribute) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>(classNaming);
        map.put(localName(objectClass), localName(attribute));
        return new ResourceOptions(namingAttribute, identifier, computed, referentialIntegrity, caseInsensitiveNames,
                realConnector, attributes, map, schema, identifierValue, multiValued);
    }

    /**
     * The resource's stored schema, from this file: the {@code <schema>} element copied as it is out of the resource
     * exported from a midPoint that has connected to the real resource (the usual prefixes are declared for it; the
     * first {@code <schema>} with a {@code definition} in the file is used, so a whole export works too). A definition
     * deployed without it (midPoint fetches the schema from the real connector) gets the mirror's classes, attributes,
     * multiplicities, naming attributes and identifiers from it, as in production, instead of from schemaHandling.
     */
    public ResourceOptions schema(java.nio.file.Path file) {
        return new ResourceOptions(namingAttribute, identifier, computed, referentialIntegrity, caseInsensitiveNames,
                realConnector, attributes, classNaming, file, identifierValue, multiValued);
    }

    /** The file of {@link #schema(java.nio.file.Path)}, or null. */
    public java.nio.file.Path schema() {
        return schema;
    }

    /**
     * Primary identifier of the resource's objects, e.g. {@code "entryUUID"} (a {@code ri:} attribute), generated by
     * the target system so that a rename keeps the identity. Without it the name is the identifier. {@code icfs:uid}
     * is ConnId's {@code __UID__} without a native name, which a stored schema shows as {@code icfs:uid} beside
     * {@code icfs:name}: the target system issues it apart from the name (a stored schema does not say so).
     */
    public ResourceOptions identifier(String attribute) {
        checkIdentifier(attribute);
        return new ResourceOptions(namingAttribute, attribute, computed, referentialIntegrity,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema, null, multiValued);
    }

    /**
     * The primary identifier, made by the target system when an object is created as the Groovy expression says: a
     * value the target system issues, like a certificate's serial and validity in its identifier. The expression gets
     * {@code name} (the created object's name, ConnId's {@code __NAME__}), {@code attributes} (its other attributes,
     * name -> list of values) and {@code now} (the tester's clock, an {@code Instant}), and returns the identifier:
     * {@code "name + '/' + now.toEpochMilli()"}.
     */
    public ResourceOptions identifier(String attribute, String value) {
        checkIdentifier(attribute);
        return new ResourceOptions(namingAttribute, attribute, computed, referentialIntegrity,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema, value, multiValued);
    }

    private static void checkIdentifier(String attribute) {
        if (attribute != null && attribute.startsWith("icfs:") && !attribute.equals("icfs:uid")) {
            throw new IllegalArgumentException("identifier " + attribute + ": a ri: attribute, or icfs:uid");
        }
    }

    /** Whether the identifier is ConnId's {@code __UID__} without a native name ({@code icfs:uid}). */
    public boolean identifierIsConnIdUid() {
        return identifier != null && identifier.startsWith("icfs:");
    }

    /** The expression of {@link #identifier(String, String)}, or null. */
    public String identifierValue() {
        return identifierValue;
    }

    /**
     * A read-only attribute the target system computes from the members of another object class, like LDAP's
     * {@code memberOf} from {@code groupOfNames}' {@code member}, or SCIM's {@code groups} from {@code Group}'s
     * {@code members}: {@code computed("memberOf", "groupOfNames", "member")}.
     *
     * The {@code schemaHandling} association that uses the source attribute tells which object class gets the
     * attribute (the one whose objects are the members) and whether members are referred to by name or by identifier;
     * the computed values refer to the source objects the same way.
     */
    public ResourceOptions computed(String attribute, String sourceObjectClass, String sourceAttribute) {
        List<Computed> list = new ArrayList<>(computed);
        list.add(new Computed(attribute, sourceObjectClass, sourceAttribute));
        return new ResourceOptions(namingAttribute, identifier, list, referentialIntegrity,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema, identifierValue, multiValued);
    }

    /**
     * The target system keeps the references of the {@code schemaHandling} associations valid, like LDAP's refint
     * overlay, Active Directory or SCIM: renaming an object rewrites the values that refer to it by name, deleting it
     * removes them. Off by default (a CSV file, for one, does not do this).
     */
    public ResourceOptions referentialIntegrity() {
        return new ResourceOptions(namingAttribute, identifier, computed, true,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema, identifierValue, multiValued);
    }

    /**
     * The target system matches names regardless of case, like LDAP DNs ({@code uid=jdoe,ou=people,...} is
     * {@code UID=JDoe,OU=People,...}) or Active Directory: looking objects up by name, the uniqueness of names, search
     * filters on names, and the references by name of the {@code schemaHandling} associations (finding the members of
     * a group, removing a member, computed attributes, referential integrity). Values are stored and returned as they
     * were written. Off by default; references by a generated identifier stay case sensitive.
     */
    public ResourceOptions caseInsensitiveNames() {
        return new ResourceOptions(namingAttribute, identifier, computed, referentialIntegrity, true,
                realConnector, attributes, classNaming, schema, identifierValue, multiValued);
    }

    /**
     * Keeps the resource on its real connector instead of the mirror; supported for the CSV connector
     * ({@code com.evolveum.polygon.connector-csv}) and the LDAP connector ({@code com.evolveum.polygon.connector-ldap},
     * {@code LdapConnector}) the midPoint line ships. The connector reads and writes a file, or talks to an in-memory
     * LDAP server, that the tester creates; what is overridden in the definition for that is listed in the README
     * ("Real connectors"). Then {@link MidPoint#csv(String)} or {@link MidPoint#ldap(String)} give that file or
     * server, and {@link MidPoint#target(String)} the same {@link TargetSystem} view as on a mirror.
     *
     * With the LDAP connector, {@link #referentialIntegrity()} turns on the server's referential integrity for the
     * association attributes of {@code schemaHandling}; the other options are for mirrors only.
     */
    public ResourceOptions realConnector() {
        return new ResourceOptions(namingAttribute, identifier, computed, referentialIntegrity, caseInsensitiveNames,
                true, attributes, classNaming, schema, identifierValue, multiValued);
    }

    /**
     * An attribute of the object class (e.g. {@code "user"}) on the real resource that {@code schemaHandling} does not
     * name, e.g. one only a script reads ({@code basic.getAttributeValue(projection, "category")}): without a stored
     * schema the mirror has only the attributes {@code schemaHandling} names.
     */
    public ResourceOptions attribute(String objectClass, String attribute) {
        List<String[]> list = new ArrayList<>(attributes);
        list.add(new String[] { localName(objectClass), localName(attribute) });
        return new ResourceOptions(namingAttribute, identifier, computed, referentialIntegrity, caseInsensitiveNames,
                realConnector, list, classNaming, schema, identifierValue, multiValued);
    }

    /** The {@link #attribute}s as {object class, attribute}. */
    public List<String[]> attributes() {
        return attributes;
    }

    /**
     * How the target system keeps the values of one multi-valued attribute of an object class: as written (a plain
     * list), one per key ({@link #keyedValues}) or as an expression says ({@link #store}).
     */
    public static final class StoredValues {
        public final String objectClass;
        public final String attribute;
        /** The separator of {@code key<separator>value} values kept one per key, or null. */
        public final String keySeparator;
        /** The Groovy expression that makes the values kept, or null. */
        public final String expression;

        StoredValues(String objectClass, String attribute, String keySeparator, String expression) {
            this.objectClass = localName(objectClass);
            this.attribute = localName(attribute);
            this.keySeparator = keySeparator;
            this.expression = expression;
        }
    }

    /**
     * An attribute of the object class that is multi-valued on the real resource (e.g. a list of parameters): without
     * a stored schema the mirror makes the attributes {@code schemaHandling} names single-valued unless their
     * {@code limitations/maxOccurs} says otherwise. The attribute need not be named by {@code schemaHandling}.
     */
    public ResourceOptions multiValued(String objectClass, String attribute) {
        return withValues(new StoredValues(objectClass, attribute, null, null));
    }

    /**
     * An attribute whose values are {@code key<separator>value} and which the target system keeps one per key, as a
     * map: a value added replaces the value of its key, a value removed removes its key whatever its value (e.g.
     * Guacamole's connection {@code parameters}, {@code disable-paste=false} replacing {@code disable-paste=true}).
     * The attribute is multi-valued; without a stored schema it need not be named by {@code schemaHandling}. A
     * shortcut of {@link #store} for this common case.
     */
    public ResourceOptions keyedValues(String objectClass, String attribute, String separator) {
        if (separator == null || separator.isEmpty()) {
            throw new IllegalArgumentException("keyedValues " + attribute + ": a separator is needed");
        }
        return withValues(new StoredValues(objectClass, attribute, separator, null));
    }

    /**
     * The values the target system keeps of a multi-valued attribute when midPoint writes it, as a Groovy expression
     * says: what a target system does beyond storing the values (keeping one per key, normalizing case, sorting,
     * adding a default). The expression gets {@code values} (the values kept so far; empty on create), {@code add}
     * and {@code remove} (the values midPoint adds and removes; on create the values it creates the object with are
     * {@code add}), {@code replace} (the values midPoint replaces them with, or null) and {@code name} (the object's
     * name), and returns the values to keep (a collection; empty or null: none). E.g. lower case and sorted:
     * {@code ((replace ?: values) - remove + add).collect { it.toLowerCase() }.unique().sort()}.
     * The attribute is multi-valued; without a stored schema it need not be named by {@code schemaHandling}.
     */
    public ResourceOptions store(String objectClass, String attribute, String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new IllegalArgumentException("store " + attribute + ": an expression is needed");
        }
        return withValues(new StoredValues(objectClass, attribute, null, expression));
    }

    private ResourceOptions withValues(StoredValues values) {
        List<StoredValues> list = new ArrayList<>(multiValued);
        for (StoredValues v : list) {
            if (v.objectClass.equals(values.objectClass) && v.attribute.equals(values.attribute)
                    && (v.keySeparator != null || v.expression != null)
                    && (values.keySeparator != null || values.expression != null)) {
                throw new IllegalArgumentException(values.objectClass + "/" + values.attribute
                        + ": either keyedValues or store, once");
            }
        }
        list.add(values);
        return new ResourceOptions(namingAttribute, identifier, computed, referentialIntegrity, caseInsensitiveNames,
                realConnector, attributes, classNaming, schema, identifierValue, list);
    }

    /** The attributes of {@link #multiValued}, {@link #keyedValues} and {@link #store}. */
    public List<StoredValues> storedValues() {
        return multiValued;
    }

    public boolean isRealConnector() {
        return realConnector;
    }

    /** Local name of the naming attribute, or null. */
    public String namingAttribute() {
        return localName(namingAttribute);
    }

    /** Local name of the naming attribute of the object class (its own, else the resource's), or null. */
    public String namingAttributeOf(String objectClass) {
        String own = classNaming.get(localName(objectClass));
        return own != null ? own : namingAttribute();
    }

    /** Local name of the identifier (a {@code ri:} attribute), or null; null also for {@code icfs:uid}. */
    public String identifier() {
        return identifierIsConnIdUid() ? null : localName(identifier);
    }

    public List<Computed> computedAttributes() {
        return computed;
    }

    public boolean hasReferentialIntegrity() {
        return referentialIntegrity;
    }

    public boolean hasCaseInsensitiveNames() {
        return caseInsensitiveNames;
    }

    private static String localName(String name) {
        return name == null ? null : name.substring(name.indexOf(':') + 1);
    }
}
