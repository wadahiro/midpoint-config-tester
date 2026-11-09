package com.github.wadahiro.midpoint.tester.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Points the mail transports of a system configuration at the tester's in-memory SMTP server, on import. Everything
 * else of the configuration stays as written: midPoint builds and sends each mail with its own transport.
 *
 * In every {@code mail} of {@code systemConfiguration/notificationConfiguration} (4.0, 4.4; on 4.8+ the legacy
 * {@code mail} transport) and {@code systemConfiguration/messageTransportConfiguration} (4.8+), each {@code server}
 * gets {@code host} 127.0.0.1 and {@code port} the server's port, and loses {@code transportSecurity},
 * {@code username}, {@code password}, {@code basicAuthentication} and {@code oauth2Authentication} (plain SMTP
 * without authentication; a password encrypted with the production key could not be decrypted here). The files of
 * {@code redirectToFile} and {@code logToFile} are moved into the working directory (same file name); a transport
 * that redirects to a file still does, and its mails do not reach the SMTP server.
 */
final class MailRedirect {

    private static final List<String> REMOVED = List.of("transportSecurity", "username", "password",
            "basicAuthentication", "oauth2Authentication");

    /** The XML to import and what was changed (empty: the XML is the one given). */
    static final class Result {
        final String xml;
        final List<String> notes;

        Result(String xml, List<String> notes) {
            this.xml = xml;
            this.notes = notes;
        }

        boolean changed() {
            return !notes.isEmpty();
        }
    }

    private MailRedirect() {
    }

    static Result apply(String xml, String host, int port, Path fileDir) {
        if (!xml.contains("ystemConfiguration") || !xml.contains("mail")) {
            return new Result(xml, List.of());
        }
        Document doc = Xml.parse(xml);
        List<String> notes = new ArrayList<>();
        Element root = doc.getDocumentElement();
        for (Element config : Xml.descendants(root, "systemConfiguration")) {
            redirect(config, host, port, fileDir, notes);
        }
        // the root, or an <objects> child, also as <object xsi:type="c:SystemConfigurationType">
        List<Element> objects = new ArrayList<>(List.of(root));
        if ("objects".equals(root.getLocalName())) {
            objects.addAll(Xml.children(root, "object"));
        }
        for (Element object : objects) {
            if ("systemConfiguration".equals(Xml.objectElementName(object))) {
                redirect(object, host, port, fileDir, notes);
            }
        }
        return notes.isEmpty() ? new Result(xml, notes) : new Result(Xml.serialize(doc), notes);
    }

    /**
     * The mail servers of a system configuration that are not the given host (each as {@code item: host}): after the
     * redirection there must be none.
     */
    static List<String> otherServers(String xml, String host) {
        List<String> out = new ArrayList<>();
        if (!xml.contains("mail")) {
            return out;
        }
        Element root = Xml.parse(xml).getDocumentElement();
        for (String parentName : List.of("notificationConfiguration", "messageTransportConfiguration")) {
            for (Element parent : Xml.children(root, parentName)) {
                for (Element mail : Xml.children(parent, "mail")) {
                    for (Element server : Xml.children(mail, "server")) {
                        Element h = Xml.child(server, "host");
                        String value = h != null ? h.getTextContent().trim() : "";
                        if (!value.equals(host)) {
                            out.add(parentName + "/mail: " + (value.isEmpty() ? "(no host)" : value));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void redirect(Element config, String host, int port, Path fileDir, List<String> notes) {
        for (Element parent : Xml.children(config, "notificationConfiguration")) {
            redirectTransports(parent, host, port, fileDir, notes);
        }
        for (Element parent : Xml.children(config, "messageTransportConfiguration")) {
            redirectTransports(parent, host, port, fileDir, notes);
        }
    }

    private static void redirectTransports(Element parent, String host, int port, Path fileDir, List<String> notes) {
        for (Element mail : Xml.children(parent, "mail")) {
            Element nameElement = Xml.child(mail, "name");
            String name = parent.getLocalName() + "/mail"
                    + (nameElement != null ? "[" + nameElement.getTextContent().trim() + "]" : "");
            List<Element> servers = Xml.children(mail, "server");
            for (Element server : servers) {
                for (String removed : REMOVED) {
                    for (Element e : Xml.children(server, removed)) {
                        server.removeChild(e);
                    }
                }
                Element hostElement = Xml.child(server, "host");
                if (hostElement == null) {
                    hostElement = element(server, "host");
                    server.insertBefore(hostElement, server.getFirstChild());
                }
                hostElement.setTextContent(host);
                Element portElement = Xml.child(server, "port");
                if (portElement == null) {
                    portElement = element(server, "port");
                    server.insertBefore(portElement, hostElement.getNextSibling());
                }
                portElement.setTextContent(String.valueOf(port));
            }
            notes.add(name + ": " + servers.size() + " server(s) redirected to " + host + ":" + port
                    + (servers.isEmpty() ? " (none configured: midPoint sends no mail)" : ""));
            for (String file : List.of("redirectToFile", "logToFile")) {
                Element e = Xml.child(mail, file);
                if (e != null && !e.getTextContent().trim().isEmpty()) {
                    Path moved = fileDir.resolve(Path.of(e.getTextContent().trim()).getFileName().toString());
                    e.setTextContent(moved.toString());
                    notes.add(name + ": " + file + " moved to " + moved
                            + (file.equals("redirectToFile") ? " (its mails go to the file, not to mails())" : ""));
                }
            }
        }
    }

    private static Element element(Element sibling, String localName) {
        String prefix = sibling.getPrefix();
        return sibling.getOwnerDocument().createElementNS(sibling.getNamespaceURI(),
                prefix != null ? prefix + ":" + localName : localName);
    }
}
