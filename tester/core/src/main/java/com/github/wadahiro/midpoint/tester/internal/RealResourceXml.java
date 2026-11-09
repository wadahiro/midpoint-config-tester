package com.github.wadahiro.midpoint.tester.internal;

import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_C;
import static com.github.wadahiro.midpoint.tester.internal.Xml.NS_RI;
import static com.github.wadahiro.midpoint.tester.internal.Xml.child;
import static com.github.wadahiro.midpoint.tester.internal.Xml.children;
import static com.github.wadahiro.midpoint.tester.internal.Xml.descendants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.xml.namespace.QName;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Keeps a resource definition on its real connector (CSV or LDAP) and points the connector at what the tester
 * provides: a file under the working directory, an in-memory LDAP server. Changed in the definition:
 * <ul>
 *   <li>{@code connectorRef}: the OID of the connector of that type the line ships (the type is the one the
 *       configuration properties' namespace names; a version in a filter is not kept);</li>
 *   <li>a stored {@code <schema>} and the native capabilities are taken out, so that midPoint reads them from the
 *       connector;</li>
 *   <li>CSV: {@code filePath} and {@code tmpFolder};</li>
 *   <li>LDAP: {@code host}, {@code port}, {@code connectionSecurity} ({@code none}), {@code bindPassword};
 *       {@code servers} is taken out. {@code baseContext} and {@code bindDn} are kept (the server serves that base
 *       and accepts that bind DN; without a {@code bindDn}, {@code cn=tester} is set).</li>
 * </ul>
 * Everything else (the other configuration properties, schemaHandling, synchronization) is as deployed.
 */
final class RealResourceXml {

    static final String NS_BUNDLE = "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/bundle/";
    static final String CSV_BUNDLE = "com.evolveum.polygon.connector-csv";
    static final String LDAP_BUNDLE = "com.evolveum.polygon.connector-ldap";
    static final String LDAP_CONNECTOR = "com.evolveum.polygon.connector.ldap.LdapConnector";
    private static final String NS_T = "http://prism.evolveum.com/xml/ns/public/types-3";

    enum Kind { CSV, LDAP }

    final String oid;
    final String name;
    final Kind kind;
    final String connectorType;
    /** The configuration properties as deployed (local name to text). */
    final Map<String, String> properties;
    /** Local names of the {@code ri:} attributes the account object types of schemaHandling refer to, in order. */
    final List<String> accountAttributes;
    /** Local names of the association attributes of schemaHandling (e.g. {@code member}). */
    final Set<String> associationAttributes;
    /** Local names of the object classes of the account object types, then of the others. */
    final List<String> accountClasses;
    final List<String> otherClasses;
    /** The simulated activation status ({@code capabilities/configured}), or null. */
    final SimulatedActivation simulatedActivation;

    private final Document doc;
    private final Element resource;
    private final Element configurationProperties;
    private final String propertiesNamespace;

    private RealResourceXml(Document doc) {
        this.doc = doc;
        this.resource = doc.getDocumentElement();
        this.oid = resource.getAttribute("oid");
        if (!"resource".equals(Xml.objectElementName(resource)) || oid.isEmpty()) {
            throw new IllegalArgumentException("expected a <resource> with an oid");
        }
        Element nameElement = child(resource, "name");
        this.name = nameElement != null ? nameElement.getTextContent().trim() : oid;
        Element configuration = child(resource, "connectorConfiguration");
        this.configurationProperties = configuration != null ? child(configuration, "configurationProperties") : null;
        Element first = configurationProperties != null ? (Element) Xml.firstElement(configurationProperties) : null;
        if (first == null || first.getNamespaceURI() == null || !first.getNamespaceURI().startsWith(NS_BUNDLE)) {
            throw new IllegalArgumentException("the real connector needs connectorConfiguration/configurationProperties"
                    + " whose elements are in the connector's namespace (" + NS_BUNDLE + "<bundle>/<connector>)");
        }
        this.propertiesNamespace = first.getNamespaceURI();
        String[] parts = propertiesNamespace.substring(NS_BUNDLE.length()).split("/");
        String bundle = parts[0];
        this.connectorType = parts.length > 1 ? parts[1] : "";
        if (CSV_BUNDLE.equals(bundle)) {
            kind = Kind.CSV;
        } else if (LDAP_BUNDLE.equals(bundle) && LDAP_CONNECTOR.equals(connectorType)) {
            kind = Kind.LDAP;
        } else {
            throw new IllegalArgumentException("ResourceOptions.realConnector() supports the CSV connector ("
                    + CSV_BUNDLE + ") and the LDAP connector (" + LDAP_CONNECTOR + "), not " + bundle + "/"
                    + connectorType);
        }
        properties = new LinkedHashMap<>();
        for (Node n = first; n != null; n = Xml.nextElement(n)) {
            if (propertiesNamespace.equals(n.getNamespaceURI())) {
                properties.put(n.getLocalName(), n.getTextContent().trim());
            }
        }
        accountAttributes = new ArrayList<>();
        associationAttributes = new LinkedHashSet<>();
        accountClasses = new ArrayList<>();
        otherClasses = new ArrayList<>();
        for (Element type : descendants(resource, "objectType")) {
            Element kindElement = child(type, "kind");
            boolean account = kindElement == null || "account".equals(kindElement.getTextContent().trim());
            Element oc = child(type, "objectClass");
            Element delineation = child(type, "delineation");
            if (oc == null && delineation != null) {
                oc = child(delineation, "objectClass");
            }
            if (oc != null) {
                String objectClass = Xml.qname(oc).getLocalPart();
                List<String> list = account ? accountClasses : otherClasses;
                if (!list.contains(objectClass)) {
                    list.add(objectClass);
                }
            }
            for (Element attribute : children(type, "attribute")) {
                Element ref = child(attribute, "ref");
                if (ref != null && account) {
                    QName q = Xml.qname(ref);
                    if (NS_RI.equals(q.getNamespaceURI()) && !accountAttributes.contains(q.getLocalPart())) {
                        accountAttributes.add(q.getLocalPart());
                    }
                }
            }
            for (Element association : children(type, "association")) {
                Element attribute = child(association, "associationAttribute");
                if (attribute != null) {
                    associationAttributes.add(Xml.qname(attribute).getLocalPart());
                }
            }
        }
        otherClasses.removeAll(accountClasses);
        simulatedActivation = SimulatedActivation.of(resource);
        if (simulatedActivation != null && !accountAttributes.contains(simulatedActivation.attribute)) {
            // a column (CSV) the status is written to, which schemaHandling need not name
            accountAttributes.add(simulatedActivation.attribute);
        }
    }

    /** The definition from XML text; {@code source} names it in error messages. */
    static RealResourceXml parse(String xml, String source) {
        try {
            return new RealResourceXml(Xml.parse(xml));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    /** The configuration property as deployed, or the default. */
    String property(String localName, String defaultValue) {
        String value = properties.get(localName);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    /**
     * The definition with the connector and the overridden properties; {@code connectorOid} gives the OID of the
     * line's connector of a connector type.
     */
    String transform(Function<String, String> connectorOid, Map<String, String> overrides, Set<String> removed,
            String bindPassword) {
        String connector = connectorOid.apply(connectorType);
        Element connectorRef = child(resource, "connectorRef");
        Element newRef = doc.createElementNS(NS_C, "connectorRef");
        newRef.setAttribute("oid", connector);
        if (connectorRef != null) {
            resource.replaceChild(newRef, connectorRef);
        } else {
            Element configuration = child(resource, "connectorConfiguration");
            resource.insertBefore(newRef, configuration);
        }
        // the stored schema is read again from the connector; its generationConstraints stay (as deployed)
        Element schema = child(resource, "schema");
        if (schema != null) {
            Element constraints = child(schema, "generationConstraints");
            if (constraints == null) {
                resource.removeChild(schema);
            } else {
                for (Node n = schema.getFirstChild(); n != null; ) {
                    Node next = n.getNextSibling();
                    if (n != constraints) {
                        schema.removeChild(n);
                    }
                    n = next;
                }
            }
        }
        Element capabilities = child(resource, "capabilities");
        Element nativeCaps = capabilities != null ? child(capabilities, "native") : null;
        if (nativeCaps != null) {
            capabilities.removeChild(nativeCaps);
        }
        for (String property : removed) {
            for (Element e : propertyElements(property)) {
                configurationProperties.removeChild(e);
            }
        }
        overrides.forEach(this::setProperty);
        if (bindPassword != null) {
            Element password = setProperty("bindPassword", "");
            Element clear = doc.createElementNS(NS_T, "t:clearValue");
            clear.setTextContent(bindPassword);
            password.appendChild(clear);
        }
        return Xml.serialize(doc);
    }

    private Element setProperty(String localName, String value) {
        List<Element> existing = propertyElements(localName);
        Element element;
        if (existing.isEmpty()) {
            element = doc.createElementNS(propertiesNamespace, "gen:" + localName);
            configurationProperties.appendChild(element);
        } else {
            element = existing.get(0);
            for (int i = 1; i < existing.size(); i++) {
                configurationProperties.removeChild(existing.get(i));
            }
            while (element.getFirstChild() != null) {
                element.removeChild(element.getFirstChild());
            }
        }
        element.setTextContent(value);
        return element;
    }

    private List<Element> propertyElements(String localName) {
        List<Element> out = new ArrayList<>();
        for (Element e : children(configurationProperties, localName)) {
            if (propertiesNamespace.equals(e.getNamespaceURI())) {
                out.add(e);
            }
        }
        return out;
    }
}
