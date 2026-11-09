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

    private ResourceOptions(String namingAttribute, String identifier, List<Computed> computed,
            boolean referentialIntegrity, boolean caseInsensitiveNames, boolean realConnector,
            List<String[]> attributes, java.util.Map<String, String> classNaming, java.nio.file.Path schema) {
        this.namingAttribute = namingAttribute;
        this.identifier = identifier;
        this.computed = Collections.unmodifiableList(computed);
        this.referentialIntegrity = referentialIntegrity;
        this.caseInsensitiveNames = caseInsensitiveNames;
        this.realConnector = realConnector;
        this.attributes = Collections.unmodifiableList(attributes);
        this.classNaming = Collections.unmodifiableMap(classNaming);
        this.schema = schema;
    }

    public static ResourceOptions defaults() {
        return new ResourceOptions(null, null, List.of(), false, false, false, List.of(), java.util.Map.of(), null);
    }

    /** Attribute that names objects on the resource, e.g. {@code "dn"} (a {@code ri:} attribute). */
    public ResourceOptions namingAttribute(String attribute) {
        return new ResourceOptions(attribute, identifier, computed, referentialIntegrity,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema);
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
                realConnector, attributes, map, schema);
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
                realConnector, attributes, classNaming, file);
    }

    /** The file of {@link #schema(java.nio.file.Path)}, or null. */
    public java.nio.file.Path schema() {
        return schema;
    }

    /**
     * Primary identifier of the resource's objects, e.g. {@code "entryUUID"} (a {@code ri:} attribute), generated by
     * the target system so that a rename keeps the identity. Without it the name is the identifier.
     */
    public ResourceOptions identifier(String attribute) {
        return new ResourceOptions(namingAttribute, attribute, computed, referentialIntegrity,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema);
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
                caseInsensitiveNames, realConnector, attributes, classNaming, schema);
    }

    /**
     * The target system keeps the references of the {@code schemaHandling} associations valid, like LDAP's refint
     * overlay, Active Directory or SCIM: renaming an object rewrites the values that refer to it by name, deleting it
     * removes them. Off by default (a CSV file, for one, does not do this).
     */
    public ResourceOptions referentialIntegrity() {
        return new ResourceOptions(namingAttribute, identifier, computed, true,
                caseInsensitiveNames, realConnector, attributes, classNaming, schema);
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
                realConnector, attributes, classNaming, schema);
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
                true, attributes, classNaming, schema);
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
                realConnector, list, classNaming, schema);
    }

    /** The {@link #attribute}s as {object class, attribute}. */
    public List<String[]> attributes() {
        return attributes;
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

    /** Local name of the identifier, or null. */
    public String identifier() {
        return localName(identifier);
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
