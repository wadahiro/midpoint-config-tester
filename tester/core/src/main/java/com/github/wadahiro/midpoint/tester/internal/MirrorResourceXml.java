package com.github.wadahiro.midpoint.tester.internal;

import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_C;
import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_ICFC;
import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_ICFS;
import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_RI;
import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_XSD;
import static com.github.wadahiro.midpoint.tester.internal.Xml.child;
import static com.github.wadahiro.midpoint.tester.internal.Xml.children;
import static com.github.wadahiro.midpoint.tester.internal.Xml.descendants;
import static com.github.wadahiro.midpoint.tester.internal.Xml.firstDescendant;
import static com.github.wadahiro.midpoint.tester.internal.Xml.text;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.github.wadahiro.midpoint.tester.ResourceOptions;
import com.github.wadahiro.midpoint.tester.mirror.MirrorAttribute;
import com.github.wadahiro.midpoint.tester.mirror.MirrorClass;
import com.github.wadahiro.midpoint.tester.mirror.MirrorLink;

/**
 * Moves a resource definition to the mirror connector. Only {@code connectorRef} and {@code connectorConfiguration}
 * change; a stored {@code <schema>} and the native capabilities are taken out so that midPoint fetches them again, from
 * the mirror, which reproduces them. Nothing else in the definition (or in other objects) is rewritten.
 *
 * What the mirror reproduces:
 * <ul>
 *   <li>With a stored schema (definitions exported from midPoint): its object classes, attribute types, multiplicity
 *       and access, and which attributes are ConnId's {@code __NAME__} / {@code __UID__}
 *       ({@code ra:frameworkAttributeName}, or the class's {@code ra:namingAttribute} / {@code ra:identifier}).</li>
 *   <li>Without one: the object classes of the object types, with the attributes {@code schemaHandling} uses
 *       (including association attributes, always multi-valued) as strings, single-valued unless
 *       {@code limitations/maxOccurs} says otherwise. Objects are named by {@code icfs:name} unless
 *       {@link ResourceOptions} name another attribute; an account class with neither is an error, nothing is
 *       guessed.</li>
 *   <li>Activation (with its {@code validFrom} / {@code validTo} dates) and password: the native capabilities if the
 *       definition lists them, otherwise whether {@code schemaHandling} has {@code activation} (its
 *       {@code validFrom} / {@code validTo}) / {@code credentials} mappings. Whether searches return the password
 *       (readable, incomplete, not at all) follows the real connector ({@link #passwordReading}).</li>
 *   <li>A simulated activation status ({@code capabilities/configured/activation/status/attribute}): the attribute
 *       is in the account classes (as a string), and without listed native capabilities the mirror has no native
 *       activation (as the CSV connector), so midPoint writes the status as the attribute
 *       ({@link SimulatedActivation}).</li>
 * </ul>
 * Computed attributes ({@link ResourceOptions#computed}), referential integrity
 * ({@link ResourceOptions#referentialIntegrity()}) and case insensitive references
 * ({@link ResourceOptions#caseInsensitiveNames()}) follow the {@code schemaHandling} associations: which class holds
 * the references and whether they are names or identifiers.
 *
 * Object classes named {@code AccountObjectClass} / {@code GroupObjectClass} / {@code Custom<X>ObjectClass} are
 * ConnId's legacy names ({@code __ACCOUNT__}, {@code __GROUP__}, {@code X}), as midPoint shows them.
 */
final class MirrorResourceXml {

    static final String NS_MIRROR = "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/"
            + MirrorBundle.BUNDLE_NAME + "/" + MirrorBundle.CONNECTOR_TYPE;

    private static final QName ICFS_NAME = new QName(NS_ICFS, "name");

    final String oid;
    final String xml;
    final List<MirrorClass> classes;
    /** midPoint names of the object classes used by account object types, then by the others. */
    final List<String> accountClasses;
    final List<String> otherClasses;
    /** The associations that refer by name or identifier, as links between the classes. */
    final List<MirrorLink> references;
    final boolean referentialIntegrity;
    final boolean caseInsensitiveNames;
    /** The simulated activation status of the account objects ({@code capabilities/configured}), or null. */
    final SimulatedActivation simulatedActivation;

    private MirrorResourceXml(String oid, String xml, List<MirrorClass> classes, List<String> accountClasses,
            List<String> otherClasses, List<MirrorLink> references, ResourceOptions options,
            SimulatedActivation simulatedActivation) {
        this.oid = oid;
        this.simulatedActivation = simulatedActivation;
        this.xml = xml;
        this.classes = classes;
        this.accountClasses = accountClasses;
        this.otherClasses = otherClasses;
        this.references = references;
        this.referentialIntegrity = options.hasReferentialIntegrity();
        this.caseInsensitiveNames = options.hasCaseInsensitiveNames();
    }

    /**
     * An association of schemaHandling, by direction: objects of the holder class have an attribute whose values are
     * the value attribute of objects of the value class (e.g. groupOfNames.member = inetOrgPerson.dn).
     */
    private static final class Association {
        final String holderClass;
        final QName holderAttribute;
        final String valueClass;
        final QName valueAttribute;

        Association(String holderClass, QName holderAttribute, String valueClass, QName valueAttribute) {
            this.holderClass = holderClass;
            this.holderAttribute = holderAttribute;
            this.valueClass = valueClass;
            this.valueAttribute = valueAttribute;
        }
    }

    /** An object type of the resource: its kind, intents, object class (local name) and the element. */
    private static final class ObjectTypeInfo {
        final Element element;
        final String kind;
        final List<String> intents = new ArrayList<>();
        final String objectClass;

        ObjectTypeInfo(Element element) {
            this.element = element;
            Element kindElement = child(element, "kind");
            this.kind = kindElement != null ? kindElement.getTextContent().trim() : "account";
            for (Element intent : children(element, "intent")) {
                intents.add(intent.getTextContent().trim());
            }
            Element oc = child(element, "objectClass");
            Element delineation = child(element, "delineation");
            if (oc == null && delineation != null) {
                oc = child(delineation, "objectClass");
            }
            this.objectClass = oc != null ? Xml.qname(oc).getLocalPart() : null;
        }
    }

    static MirrorResourceXml transform(File file, String connectorOid, ResourceOptions options) {
        try {
            return transform(Files.readString(file.toPath()), connectorOid, options);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    static MirrorResourceXml transform(String xml, String connectorOid, ResourceOptions options) {
        Document doc = Xml.parse(xml);
        Element resource = doc.getDocumentElement();
        String oid = resource.getAttribute("oid");
        if (!"resource".equals(Xml.objectElementName(resource)) || oid.isEmpty()) {
            throw new IllegalArgumentException("expected a <resource> with an oid");
        }
        if (options.schema() != null) {
            useSchemaOf(options.schema(), resource);
        }
        List<ObjectTypeInfo> objectTypes = new ArrayList<>();
        for (Element element : descendants(resource, "objectType")) {
            objectTypes.add(new ObjectTypeInfo(element));
        }

        // which classes the object types use (for the account/group shortcuts of the target)
        List<String> accountClasses = new ArrayList<>();
        List<String> otherClasses = new ArrayList<>();
        for (ObjectTypeInfo type : objectTypes) {
            if (type.objectClass != null && "account".equals(type.kind) && !accountClasses.contains(type.objectClass)) {
                accountClasses.add(type.objectClass);
            }
        }
        for (ObjectTypeInfo type : objectTypes) {
            if (type.objectClass != null && !accountClasses.contains(type.objectClass)
                    && !otherClasses.contains(type.objectClass)) {
                otherClasses.add(type.objectClass);
            }
        }

        // capabilities of the real connector
        Element capabilities = child(resource, "capabilities");
        Element nativeCaps = capabilities != null ? child(capabilities, "native") : null;
        SimulatedActivation simulated = SimulatedActivation.of(resource);
        boolean activation;
        boolean password;
        boolean validFrom;
        boolean validTo;
        if (nativeCaps != null) {
            activation = !descendants(nativeCaps, "activation").isEmpty();
            password = !descendants(nativeCaps, "password").isEmpty();
            validFrom = activation && !descendants(nativeCaps, "validFrom").isEmpty();
            validTo = activation && !descendants(nativeCaps, "validTo").isEmpty();
        } else {
            // a simulated status (an attribute the real connector has) stands for a connector without one
            activation = simulated == null && objectTypes.stream().anyMatch(t -> child(t.element, "activation") != null);
            password = objectTypes.stream().anyMatch(t -> child(t.element, "credentials") != null);
            validFrom = activation && objectTypes.stream().anyMatch(t -> child(t.element, "activation") != null
                    && child(child(t.element, "activation"), "validFrom") != null);
            validTo = activation && objectTypes.stream().anyMatch(t -> child(t.element, "activation") != null
                    && child(child(t.element, "activation"), "validTo") != null);
        }

        List<Association> associations = new ArrayList<>();
        /** {shortcut attribute, its association}. */
        List<Object[]> shortcuts = new ArrayList<>();
        for (ObjectTypeInfo type : objectTypes) {
            for (Element association : children(type.element, "association")) {
                Association a = association(association, type, objectTypes);
                if (a != null) {
                    associations.add(a);
                    // midPoint reads the association from the members' shortcut attribute (LDAP's memberOf), which
                    // the target system computes from the holder's attribute
                    Element shortcut = child(association, "shortcutAssociationAttribute");
                    if (shortcut != null) {
                        shortcuts.add(new Object[] { Xml.qname(shortcut).getLocalPart(), a });
                    }
                }
            }
        }

        // a stored schema is its definition: a <schema> with only generationConstraints has none
        Element schema = child(resource, "schema");
        if (schema != null && child(schema, "definition") == null) {
            schema = null;
        }
        List<MirrorClass> classes = schema != null
                ? fromStoredSchema(schema, options, activation, password)
                : fromSchemaHandling(objectTypes, accountClasses, otherClasses, associations, options, activation,
                        password, simulated);
        classes = withComputed(classes, associations, options);
        classes = withShortcuts(classes, shortcuts, options);
        if (validFrom || validTo) {
            List<MirrorClass> withValidity = new ArrayList<>();
            for (MirrorClass c : classes) {
                withValidity.add(c.withValidity(validFrom, validTo));
            }
            classes = withValidity;
        }
        if (password) {
            MirrorClass.PasswordReading reading = passwordReading(nativeCaps, resource);
            boolean returnedByDefault = passwordReturnedByDefault(nativeCaps, resource);
            List<MirrorClass> withReading = new ArrayList<>();
            for (MirrorClass c : classes) {
                withReading.add(c.withPasswordReading(reading, returnedByDefault));
            }
            classes = withReading;
        }
        // referential integrity needs every association to be a reference; otherwise those that are not are left out
        List<MirrorLink> references = new ArrayList<>();
        for (Association a : associations) {
            try {
                references.add(link(classes, a, "referential integrity"));
            } catch (IllegalArgumentException e) {
                if (options.hasReferentialIntegrity()) {
                    throw e;
                }
            }
        }

        // swap the connector; the ConnId framework settings of the configuration (results handlers, timeouts, pool)
        // stay, they are not the connector's
        List<Element> frameworkSettings = new ArrayList<>();
        Element oldConfiguration = child(resource, "connectorConfiguration");
        if (oldConfiguration != null) {
            for (Node n = Xml.firstElement(oldConfiguration); n != null; n = Xml.nextElement(n)) {
                if (NS_ICFC.equals(n.getNamespaceURI()) && !"configurationProperties".equals(n.getLocalName())) {
                    frameworkSettings.add((Element) n);
                }
            }
        }
        for (String name : List.of("connectorRef", "connectorConfiguration", "schema")) {
            Element old = child(resource, name);
            if (old != null) {
                resource.removeChild(old);
            }
        }
        if (nativeCaps != null) {
            capabilities.removeChild(nativeCaps);
        }
        Element connectorRef = doc.createElementNS(NS_C, "connectorRef");
        connectorRef.setAttribute("oid", connectorOid);
        Element configuration = doc.createElementNS(NS_C, "connectorConfiguration");
        Element properties = doc.createElementNS(NS_ICFC, "icfc:configurationProperties");
        Element instanceId = doc.createElementNS(NS_MIRROR, "mirror:instanceId");
        instanceId.setTextContent(oid);
        properties.appendChild(instanceId);
        configuration.appendChild(properties);
        frameworkSettings.forEach(configuration::appendChild);
        Node anchor = child(resource, "name");
        Node before = anchor != null ? Xml.nextElement(anchor) : Xml.firstElement(resource);
        resource.insertBefore(connectorRef, before);
        resource.insertBefore(configuration, before);
        return new MirrorResourceXml(oid, Xml.serialize(doc), classes, accountClasses, otherClasses, references,
                options, simulated);
    }

    // ---- password reading -------------------------------------------------------------------------------------

    /**
     * Whether the real connector returns passwords: the native capability {@code password/readable} if the definition
     * lists the native capabilities; otherwise the connector's configuration: the CSV connector's
     * {@code passwordAttribute} (a column, returned as stored) and the LDAP connector's {@code passwordReadStrategy}
     * ({@code readable}, {@code incompleteRead}; {@code unreadable} by default); not readable otherwise (ConnId's
     * default).
     */
    static MirrorClass.PasswordReading passwordReading(Element nativeCaps, Element resource) {
        if (nativeCaps != null) {
            for (Element password : descendants(nativeCaps, "password")) {
                Element readable = child(password, "readable");
                if (readable != null && "true".equals(readable.getTextContent().trim())) {
                    return MirrorClass.PasswordReading.READABLE;
                }
            }
            return MirrorClass.PasswordReading.UNREADABLE;
        }
        String passwordAttribute = configurationProperty(resource, "passwordAttribute");
        if (passwordAttribute != null && !passwordAttribute.isEmpty()) {
            return MirrorClass.PasswordReading.READABLE;
        }
        String strategy = configurationProperty(resource, "passwordReadStrategy");
        if ("readable".equals(strategy)) {
            return MirrorClass.PasswordReading.READABLE;
        } else if ("incompleteRead".equals(strategy)) {
            return MirrorClass.PasswordReading.INCOMPLETE;
        }
        return MirrorClass.PasswordReading.UNREADABLE;
    }

    /**
     * Whether a readable password comes without being asked for: the native capability's {@code returnedByDefault}
     * (true unless it says false, as midPoint reads it); the CSV connector returns its password column by default
     * (ConnId's default flags), the LDAP connector's readable password is returned only when asked for (midPoint asks
     * when the object type's {@code credentials/password/fetchStrategy} is {@code explicit}).
     */
    static boolean passwordReturnedByDefault(Element nativeCaps, Element resource) {
        if (nativeCaps != null) {
            for (Element password : descendants(nativeCaps, "password")) {
                Element returned = child(password, "returnedByDefault");
                return returned == null || !"false".equals(returned.getTextContent().trim());
            }
            return false;
        }
        String passwordAttribute = configurationProperty(resource, "passwordAttribute");
        return passwordAttribute != null && !passwordAttribute.isEmpty();
    }

    /** The text of a connector configuration property (by local name, any namespace), or null. */
    private static String configurationProperty(Element resource, String name) {
        Element configuration = child(resource, "connectorConfiguration");
        Element properties = configuration != null ? child(configuration, "configurationProperties") : null;
        Element property = properties != null ? child(properties, name) : null;
        return property != null ? property.getTextContent().trim() : null;
    }

    // ---- with a stored schema ---------------------------------------------------------------------------------

    private static List<MirrorClass> fromStoredSchema(Element schema, ResourceOptions options, boolean activation,
            boolean password) {
        List<Element> types = new ArrayList<>();
        for (Element complexType : descendants(schema, "complexType")) {
            if (NS_XSD.equals(complexType.getNamespaceURI()) && !complexType.getAttribute("name").isEmpty()) {
                types.add(complexType);
            }
        }
        boolean legacy = types.stream().map(t -> t.getAttribute("name")).anyMatch(MirrorResourceXml::isLegacyName);
        List<MirrorClass> classes = new ArrayList<>();
        for (Element type : types) {
            classes.add(storedClass(type, legacy, options, activation, password));
        }
        return classes;
    }

    private static MirrorClass storedClass(Element type, boolean legacy, ResourceOptions options, boolean activation,
            boolean password) {
        String midPointName = type.getAttribute("name");
        String naming = null;
        String uid = null;
        Map<String, MirrorAttribute> attributes = new LinkedHashMap<>();
        for (Element element : descendants(type, "element")) {
            String name = element.getAttribute("name");
            if (!NS_XSD.equals(element.getNamespaceURI()) || name.isEmpty()) {
                continue;   // e.g. <xsd:element ref="icfs:name"/>: ConnId's own attribute, shown as icfs:name
            }
            String framework = text(element, "frameworkAttributeName");
            if ("__NAME__".equals(framework)) {
                naming = name;
            } else if ("__UID__".equals(framework)) {
                uid = name;
            } else {
                List<String> access = new ArrayList<>();
                for (Element a : descendants(element, "access")) {
                    access.add(a.getTextContent().trim());
                }
                attributes.put(name, new MirrorAttribute(name, javaType(element.getAttribute("type")),
                        isMulti(element.getAttribute("maxOccurs")),
                        access.isEmpty() || access.contains("read"),
                        access.isEmpty() || access.contains("create"),
                        access.isEmpty() || access.contains("update")));
            }
        }
        if (naming == null) {
            naming = riLocalName(type, "namingAttribute");
        }
        if (uid == null) {
            uid = riLocalName(type, "identifier");
        }
        if (options.namingAttributeOf(midPointName) != null) {
            naming = options.namingAttributeOf(midPointName);
        }
        if (options.identifier() != null) {
            uid = options.identifier();
        }
        attributes.remove(naming);
        attributes.remove(uid);
        return new MirrorClass(midPointName, connIdName(midPointName, legacy), naming, uid, activation, password,
                attributes);
    }

    /** Local name of a class annotation that refers to a {@code ri:} attribute, or null. */
    private static String riLocalName(Element type, String annotation) {
        String value = text(type, annotation);
        if (value == null) {
            return null;
        }
        QName name = Xml.qname(firstDescendant(type, annotation), value);
        return NS_RI.equals(name.getNamespaceURI()) ? name.getLocalPart() : null;
    }

    // ---- without a stored schema ------------------------------------------------------------------------------

    /** What schemaHandling says about one object class: the attributes it refers to and their multiplicity. */
    private static final class Derived {
        final Map<String, Boolean> multiValued = new LinkedHashMap<>();
        final Set<QName> refs = new LinkedHashSet<>();

        void add(QName name, boolean multi) {
            refs.add(name);
            if (NS_RI.equals(name.getNamespaceURI())) {
                multiValued.merge(name.getLocalPart(), multi, Boolean::logicalOr);
            }
        }
    }

    private static List<MirrorClass> fromSchemaHandling(List<ObjectTypeInfo> objectTypes, List<String> accountClasses,
            List<String> otherClasses, List<Association> associations, ResourceOptions options, boolean activation,
            boolean password, SimulatedActivation simulated) {
        Map<String, Derived> derived = new LinkedHashMap<>();
        accountClasses.forEach(c -> derived.put(c, new Derived()));
        otherClasses.forEach(c -> derived.put(c, new Derived()));
        for (ObjectTypeInfo type : objectTypes) {
            if (type.objectClass == null) {
                continue;
            }
            Derived d = derived.get(type.objectClass);
            for (Element attribute : children(type.element, "attribute")) {
                Element ref = child(attribute, "ref");
                if (ref != null) {
                    Element limitations = child(attribute, "limitations");
                    Element maxOccurs = limitations != null ? child(limitations, "maxOccurs") : null;
                    d.add(Xml.qname(ref), maxOccurs != null && isMulti(maxOccurs.getTextContent()));
                }
            }
        }
        if (simulated != null) {
            // the attribute of the simulated status, which schemaHandling need not name
            accountClasses.forEach(c -> derived.get(c).add(new QName(NS_RI, simulated.attribute), false));
        }
        for (Association a : associations) {
            // the association attribute is always multi-valued
            derived.get(a.holderClass).add(a.holderAttribute, true);
            derived.get(a.valueClass).add(a.valueAttribute, false);
        }

        for (String[] extra : options.attributes()) {
            Derived d = derived.get(extra[0]);
            if (d == null) {
                throw new IllegalArgumentException("targets attribute " + extra[1] + ": no object class " + extra[0]
                        + " in schemaHandling (" + String.join(", ", derived.keySet()) + ")");
            }
            d.add(new QName(NS_RI, extra[1]), false);
        }
        boolean legacy = derived.keySet().stream().anyMatch(MirrorResourceXml::isLegacyName);
        String uid = options.identifier();
        List<MirrorClass> classes = new ArrayList<>();
        for (Map.Entry<String, Derived> e : derived.entrySet()) {
            String objectClass = e.getKey();
            Derived d = e.getValue();
            String naming = options.namingAttributeOf(objectClass);
            if (naming == null && accountClasses.contains(objectClass) && !d.refs.contains(ICFS_NAME)) {
                throw new IllegalArgumentException("cannot tell which attribute names accounts of ri:" + objectClass
                        + " (ConnId's __NAME__); used attributes: " + d.refs + ". Keep the stored <schema> in the file"
                        + " (as exported from midPoint), or give ResourceOptions.defaults().namingAttribute(...), or"
                        + " namingAttribute(\"" + objectClass + "\", ...) for this class");
            }
            Map<String, MirrorAttribute> attributes = new LinkedHashMap<>();
            d.multiValued.forEach((name, multi) -> {
                if (!name.equals(naming) && !name.equals(uid)) {
                    attributes.put(name, new MirrorAttribute(name, String.class, multi, true, true, true));
                }
            });
            classes.add(new MirrorClass(objectClass, connIdName(objectClass, legacy), naming, uid, activation,
                    password, attributes));
        }
        return classes;
    }

    /** The association, by direction (the association attribute is held by the target for objectToSubject). */
    private static Association association(Element association, ObjectTypeInfo subject, List<ObjectTypeInfo> objectTypes) {
        Element kindElement = child(association, "kind");
        String kind = kindElement != null ? kindElement.getTextContent().trim() : "entitlement";
        List<String> intents = new ArrayList<>();
        for (Element intent : children(association, "intent")) {
            intents.add(intent.getTextContent().trim());
        }
        ObjectTypeInfo target = null;
        for (ObjectTypeInfo type : objectTypes) {
            if (type.objectClass != null && type.kind.equals(kind)
                    && (intents.isEmpty() || type.intents.stream().anyMatch(intents::contains))) {
                target = type;
                break;
            }
        }
        Element associationAttribute = child(association, "associationAttribute");
        Element valueAttribute = child(association, "valueAttribute");
        if (target == null || subject.objectClass == null || associationAttribute == null || valueAttribute == null) {
            return null;
        }
        Element direction = child(association, "direction");
        boolean objectToSubject = direction == null || "objectToSubject".equals(direction.getTextContent().trim());
        ObjectTypeInfo holder = objectToSubject ? target : subject;
        ObjectTypeInfo valueSide = objectToSubject ? subject : target;
        return new Association(holder.objectClass, Xml.qname(associationAttribute), valueSide.objectClass,
                Xml.qname(valueAttribute));
    }

    /** The namespaces a stored schema copied out of an export uses, with their usual prefixes. */
    private static final String SCHEMA_PREFIXES = "<prefixes xmlns='" + NS_C + "' xmlns:c='" + NS_C + "'"
            + " xmlns:t='http://prism.evolveum.com/xml/ns/public/types-3'"
            + " xmlns:q='http://prism.evolveum.com/xml/ns/public/query-3'"
            + " xmlns:xsd='" + NS_XSD + "' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
            + " xmlns:ra='http://midpoint.evolveum.com/xml/ns/public/resource/annotation-3'"
            + " xmlns:a='http://prism.evolveum.com/xml/ns/public/annotation-3'"
            + " xmlns:ri='" + NS_RI + "' xmlns:icfs='" + NS_ICFS + "' xmlns:icfc='" + NS_ICFC + "'>";

    /**
     * The resource's {@code <schema>} replaced by the stored schema of the file: the first {@code <schema>} with a
     * {@code definition} (the {@code <schema>} copied out of an export, or an exported resource).
     */
    private static void useSchemaOf(java.nio.file.Path file, Element resource) {
        String text;
        try {
            text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("schema: cannot read " + file, e);
        }
        // a <schema> copied out of an export uses the prefixes the export declares on its root: declared here (the
        // file's own declarations win)
        Document exported = Xml.parse(SCHEMA_PREFIXES + text.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "") + "</prefixes>");
        Element root = exported.getDocumentElement();
        Element stored = null;
        List<Element> candidates = new ArrayList<>();
        if ("schema".equals(root.getLocalName())) {
            candidates.add(root);
        }
        candidates.addAll(descendants(root, "schema"));
        for (Element candidate : candidates) {
            if (child(candidate, "definition") != null) {
                stored = candidate;
                break;
            }
        }
        if (stored == null) {
            throw new IllegalArgumentException("schema: no <schema> with a definition in " + file
                    + " (export the resource from a midPoint that has connected to it)");
        }
        Element old = child(resource, "schema");
        Node imported = resource.getOwnerDocument().importNode(stored, true);
        if (old != null) {
            resource.replaceChild(imported, old);
        } else {
            Element before = child(resource, "schemaHandling");
            resource.insertBefore(imported, before);
        }
    }

    // ---- what the target system does by itself ------------------------------------------------------------------

    /** Adds the computed attributes of the options to the classes whose objects are the members. */
    private static List<MirrorClass> withComputed(List<MirrorClass> classes, List<Association> associations,
            ResourceOptions options) {
        List<MirrorClass> out = new ArrayList<>(classes);
        for (ResourceOptions.Computed computed : options.computedAttributes()) {
            // every association that keeps its references there: accounts and groups may both be members
            List<Association> sources = new ArrayList<>();
            for (Association a : associations) {
                if (a.holderClass.equals(computed.sourceObjectClass)
                        && a.holderAttribute.getLocalPart().equals(computed.sourceAttribute)
                        && sources.stream().noneMatch(o -> o.valueClass.equals(a.valueClass))) {
                    sources.add(a);
                }
            }
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("computed attribute " + computed.attribute + ": no association in"
                        + " schemaHandling keeps its references in " + computed.sourceObjectClass + "."
                        + computed.sourceAttribute);
            }
            for (Association source : sources) {
                MirrorLink link = link(out, source, "computed attribute " + computed.attribute);
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).connIdName.equals(link.valueClass)) {
                        out.set(i, out.get(i).withComputed(computed.attribute, link));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The shortcut attributes of the associations ({@code shortcutAssociationAttribute}, e.g. LDAP's {@code memberOf})
     * on the classes whose objects are the members: computed from the holder's references as by
     * {@link ResourceOptions#computed} when they are names or identifiers, else in the schema only (empty), so that
     * midPoint finds the attribute. A computed attribute of the options wins.
     */
    private static List<MirrorClass> withShortcuts(List<MirrorClass> classes, List<Object[]> shortcuts,
            ResourceOptions options) {
        List<MirrorClass> out = new ArrayList<>(classes);
        for (Object[] shortcut : shortcuts) {
            String attribute = (String) shortcut[0];
            Association a = (Association) shortcut[1];
            if (options.computedAttributes().stream().anyMatch(c -> c.attribute.equals(attribute))) {
                continue;
            }
            MirrorLink link;
            try {
                link = link(out, a, "shortcut attribute " + attribute);
            } catch (IllegalArgumentException e) {
                link = null;
            }
            for (int i = 0; i < out.size(); i++) {
                MirrorClass c = out.get(i);
                if (c.midPointName.equals(a.valueClass) && !c.computed.containsKey(attribute)) {
                    out.set(i, link != null ? c.withComputed(attribute, link) : c.withReadOnlyAttribute(attribute));
                }
            }
        }
        return out;
    }

    /** The association as a link between mirror classes; its values must be names or identifiers. */
    private static MirrorLink link(List<MirrorClass> classes, Association a, String purpose) {
        MirrorClass holder = byName(classes, a.holderClass);
        MirrorClass value = byName(classes, a.valueClass);
        String local = a.valueAttribute.getLocalPart();
        Boolean byUid = null;
        if (NS_ICFS.equals(a.valueAttribute.getNamespaceURI())) {
            byUid = "uid".equals(local) ? Boolean.TRUE : "name".equals(local) ? Boolean.FALSE : null;
        } else if (local.equals(value.namingAttribute)) {
            byUid = false;
        } else if (local.equals(value.uidAttribute)) {
            byUid = true;
        }
        if (byUid == null) {
            throw new IllegalArgumentException(purpose + ": the association " + a.holderClass + "."
                    + a.holderAttribute.getLocalPart() + " refers to " + a.valueClass + " by " + a.valueAttribute
                    + ", which is neither its naming attribute nor its identifier");
        }
        return new MirrorLink(holder.connIdName, a.holderAttribute.getLocalPart(), value.connIdName, byUid);
    }

    private static MirrorClass byName(List<MirrorClass> classes, String midPointName) {
        for (MirrorClass c : classes) {
            if (c.midPointName.equals(midPointName)) {
                return c;
            }
        }
        throw new IllegalArgumentException("object class " + midPointName + " is used by schemaHandling but is not in"
                + " the stored schema");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static boolean isLegacyName(String objectClass) {
        return objectClass.equals("AccountObjectClass") || objectClass.equals("GroupObjectClass");
    }

    private static String connIdName(String midPointName, boolean legacy) {
        if (legacy) {
            if (midPointName.equals("AccountObjectClass")) {
                return "__ACCOUNT__";
            } else if (midPointName.equals("GroupObjectClass")) {
                return "__GROUP__";
            } else if (midPointName.startsWith("Custom") && midPointName.endsWith("ObjectClass")) {
                return midPointName.substring("Custom".length(), midPointName.length() - "ObjectClass".length());
            }
        }
        return midPointName;
    }

    private static Class<?> javaType(String xsdType) {
        String local = xsdType.contains(":") ? xsdType.substring(xsdType.indexOf(':') + 1) : xsdType;
        switch (local) {
            case "int":
            case "integer":
                return Integer.class;
            case "long":
                return Long.class;
            case "boolean":
                return Boolean.class;
            case "base64Binary":
                return byte[].class;
            case "dateTime":
                return ZonedDateTime.class;
            default:
                return String.class;
        }
    }

    private static boolean isMulti(String maxOccurs) {
        String value = maxOccurs.trim();
        return !value.isEmpty() && ("unbounded".equals(value) || Integer.parseInt(value) > 1);
    }
}
