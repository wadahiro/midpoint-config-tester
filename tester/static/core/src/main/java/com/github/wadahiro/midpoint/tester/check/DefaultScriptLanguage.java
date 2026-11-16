package com.github.wadahiro.midpoint.tester.check;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The language of a script without {@code <language>}, as midPoint's {@code ScriptFactory} takes it from the system
 * configuration's {@code expressions/defaultScriptLanguage}, Groovy without one. A deployment's own system
 * configuration (the post-initial object with the initial one's OID) replaces the initial one as a whole; without one
 * the line's initial system configuration decides ({@link StaticLine#INITIAL_DEFAULT_SCRIPT_LANGUAGE}: MEL from 4.11).
 * The files are read as plain XML, as {@code ConfigScanner} reads them.
 */
final class DefaultScriptLanguage {

    static final String SYSTEM_CONFIGURATION_OID = "00000000-0000-0000-0000-000000000001";

    /** The language URI; null: Groovy. */
    final String uri;
    /** Where it comes from, for the report. */
    final String source;

    private DefaultScriptLanguage(String uri, String source) {
        this.uri = uri;
        this.source = source;
    }

    /** The default of the deployment of these files ({@code name} gives a file's name in the report). */
    static DefaultScriptLanguage of(List<Path> xmlFiles, Function<Path, String> name) {
        for (Path file : xmlFiles) {
            Element configuration = systemConfiguration(file);
            if (configuration != null) {
                String language = text(child(child(configuration, "expressions"), "defaultScriptLanguage"));
                return new DefaultScriptLanguage(language, "the system configuration of " + name.apply(file));
            }
        }
        return new DefaultScriptLanguage(StaticLine.INITIAL_DEFAULT_SCRIPT_LANGUAGE,
                "midPoint " + StaticLine.LINE + "'s initial system configuration");
    }

    /** The language's name as the report says it: the URI's fragment ({@code Groovy}, {@code mel}). */
    String name() {
        String uri = this.uri != null ? this.uri : "#Groovy";
        return uri.substring(uri.indexOf('#') + 1);
    }

    /** The file's system configuration with the initial one's OID, or null (also for a file that is no XML). */
    private static Element systemConfiguration(Path file) {
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        } catch (Exception e) {
            return null;
        }
        if (isSystemConfiguration(root)) {
            return root;
        }
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && isSystemConfiguration((Element) n)) {
                return (Element) n;
            }
        }
        return null;
    }

    /** {@code <systemConfiguration>}, or an exported {@code <object xsi:type="c:SystemConfigurationType">}. */
    private static boolean isSystemConfiguration(Element element) {
        String type = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
        return SYSTEM_CONFIGURATION_OID.equals(element.getAttribute("oid"))
                && ("systemConfiguration".equals(element.getLocalName()) || type.endsWith("SystemConfigurationType"));
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String text(Element element) {
        String text = element != null ? element.getTextContent().trim() : "";
        return text.isEmpty() ? null : text;
    }
}
