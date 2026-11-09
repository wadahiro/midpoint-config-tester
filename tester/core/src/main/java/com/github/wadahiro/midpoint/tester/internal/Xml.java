package com.github.wadahiro.midpoint.tester.internal;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** DOM helpers for midPoint object XML, the same on every line. */
final class Xml {

    static final String NS_C = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    static final String NS_RI = "http://midpoint.evolveum.com/xml/ns/public/resource/instance-3";
    static final String NS_ICFS = "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/resource-schema-3";
    static final String NS_ICFC = "http://midpoint.evolveum.com/xml/ns/public/connector/icf-1/connector-schema-3";
    static final String NS_XSD = "http://www.w3.org/2001/XMLSchema";

    private Xml() {
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse XML: " + e.getMessage(), e);
        }
    }

    static String serialize(Document doc) {
        try {
            StringWriter out = new StringWriter();
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize XML", e);
        }
    }

    /** Resolves {@code prefix:local} against the element's namespace declarations; unprefixed means {@code ri:}. */
    static QName qname(Element context, String text) {
        int colon = text.indexOf(':');
        if (colon < 0) {
            return new QName(NS_RI, text);
        }
        String prefix = text.substring(0, colon);
        String ns = context.lookupNamespaceURI(prefix);
        return new QName(ns != null ? ns : prefix, text.substring(colon + 1));
    }

    /** The element's text as a QName (see {@link #qname(Element, String)}). */
    static QName qname(Element element) {
        return qname(element, element.getTextContent().trim());
    }

    /** Trimmed text of the first descendant with this local name, or null if none or empty. */
    static String text(Element parent, String localName) {
        Element e = firstDescendant(parent, localName);
        return e == null || e.getTextContent().trim().isEmpty() ? null : e.getTextContent().trim();
    }

    static Element firstDescendant(Element parent, String localName) {
        List<Element> list = descendants(parent, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * The element name of the object's type: the local name, or for an {@code <object xsi:type="c:ResourceType">} (as
     * midPoint exports objects) the element name its type has ({@code resource}).
     */
    static String objectElementName(Element object) {
        if (!"object".equals(object.getLocalName())) {
            return object.getLocalName();
        }
        String typed = com.github.wadahiro.midpoint.scan.ConfigScanner.elementNameOfType(
                object.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
        return typed != null ? typed : object.getLocalName();
    }

    static Element child(Element parent, String localName) {
        List<Element> list = children(parent, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static List<Element> descendants(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    static Node nextElement(Node node) {
        Node n = node.getNextSibling();
        while (n != null && !(n instanceof Element)) {
            n = n.getNextSibling();
        }
        return n;
    }

    static Node firstElement(Element parent) {
        Node n = parent.getFirstChild();
        while (n != null && !(n instanceof Element)) {
            n = n.getNextSibling();
        }
        return n;
    }
}
