package com.github.wadahiro.midpoint.tester.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The names a deployment's configuration holds, on every line its manifest knows (the files every line gets, and each
 * line with an overlay), read as plain XML: no midPoint runtime, so the generator of typed names
 * ({@code NamesGenerator}, at build time) uses it. The files are those of {@link Manifest#sources(String)} with the
 * properties substituted, exactly as the load copies them.
 *
 * <ul>
 *   <li>objects: roles, orgs, services, archetypes, users, resources, tasks, reports, certification definitions (a
 *       root object or a child of {@code <objects>}), by name;</li>
 *   <li>attributes of each resource: the {@code ref} of every {@code schemaHandling} attribute, the attributes of a
 *       stored resource schema, the unique, name and password columns of a CSV connector, the header of a CSV file of
 *       initial data, the attribute names of an LDIF of initial data;</li>
 *   <li>items of the extension schema ({@code config.schema}) that extend {@code UserType}.</li>
 * </ul>
 */
public final class NamesModel {

    /** The "line" of the files every line without an overlay gets. */
    public static final String EVERY_LINE = "*";

    private static final Pattern PROPERTY = Pattern.compile("\\{\\{([A-Za-z0-9_.\\-]+)\\}\\}");
    private static final String NS_ANNOTATION = "http://prism.evolveum.com/xml/ns/public/annotation-3";

    /** Element name of an object to its group (the class of the generated constants) and the kind of reference. */
    private static final Map<String, String[]> OBJECT_TYPES = new LinkedHashMap<>();

    static {
        OBJECT_TYPES.put("resource", new String[] { "Resources", "ResourceRef" });
        OBJECT_TYPES.put("role", new String[] { "Roles", "RoleRef" });
        OBJECT_TYPES.put("org", new String[] { "Orgs", "OrgRef" });
        OBJECT_TYPES.put("service", new String[] { "Services", "ServiceRef" });
        OBJECT_TYPES.put("archetype", new String[] { "Archetypes", "ArchetypeRef" });
        OBJECT_TYPES.put("user", new String[] { "Users", "UserRef" });
        OBJECT_TYPES.put("task", new String[] { "Tasks", "TaskRef" });
        OBJECT_TYPES.put("report", new String[] { "Reports", "ReportRef" });
        OBJECT_TYPES.put("accessCertificationDefinition",
                new String[] { "CertificationDefinitions", "CertificationDefinitionRef" });
    }

    /** One name: where it belongs and on which lines ({@link #EVERY_LINE} and the lines of the overlays) it is. */
    public static final class Name {
        /** {@code Roles}, {@code Resources}...; for attributes {@code attributes:<resource>}; {@code UserExtension}. */
        public final String group;
        /** {@code RoleRef}, ..., {@code AttributeRef}, {@code ItemRef}. */
        public final String refType;
        public final String name;
        /** The resource of an attribute. */
        public final String resource;
        /** The namespace of an extension item. */
        public final String namespace;
        public final Set<String> oids = new TreeSet<>();
        public final Set<String> files = new TreeSet<>();
        public final Set<String> variants = new TreeSet<>();

        Name(String group, String refType, String name, String resource, String namespace) {
            this.group = group;
            this.refType = refType;
            this.name = name;
            this.resource = resource;
            this.namespace = namespace;
        }

        String key() {
            return group + "\u0000" + name;
        }
    }

    public final Manifest manifest;
    /** {@link #EVERY_LINE}, then the lines of the overlays. */
    public final List<String> variants = new ArrayList<>();
    /** By group, then name. */
    public final Map<String, Map<String, Name>> groups = new TreeMap<>();
    /** Files that could not be read as XML (midPoint's import would report them). */
    public final List<String> warnings = new ArrayList<>();

    private NamesModel(Manifest manifest) {
        this.manifest = manifest;
    }

    public static NamesModel read(Path manifestFile) {
        Manifest manifest = Manifest.read(manifestFile);
        NamesModel model = new NamesModel(manifest);
        model.variants.add(EVERY_LINE);
        model.variants.addAll(new TreeSet<>(manifest.overlays.keySet()));
        for (String variant : model.variants) {
            model.readVariant(variant);
        }
        return model;
    }

    /** The names of a group in name order. */
    public List<Name> group(String group) {
        return new ArrayList<>(groups.getOrDefault(group, Map.of()).values());
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    private void readVariant(String variant) {
        Map<String, Path> files = manifest.sources(EVERY_LINE.equals(variant) ? null : variant);
        for (Map.Entry<String, Path> file : files.entrySet()) {
            if (!file.getKey().endsWith(".xml")) {
                continue;
            }
            String text;
            try {
                text = substitute(Files.readString(file.getValue(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file.getValue(), e);
            }
            Document doc;
            try {
                doc = Xml.parse(text);
            } catch (IllegalArgumentException e) {
                if (EVERY_LINE.equals(variant)) {
                    warnings.add(file.getKey() + ": not read (" + e.getMessage() + ")");
                }
                continue;
            }
            Element root = doc.getDocumentElement();
            List<Element> objects = new ArrayList<>();
            if ("objects".equals(root.getLocalName())) {
                for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element) {
                        objects.add((Element) n);
                    }
                }
            } else {
                objects.add(root);
            }
            for (Element object : objects) {
                readObject(variant, file.getKey(), object);
            }
        }
        if (manifest.schema != null) {
            readSchema(variant);
        }
    }

    private void readObject(String variant, String file, Element object) {
        String[] type = OBJECT_TYPES.get(Xml.objectElementName(object));
        if (type == null) {
            return;
        }
        String name = objectName(object);
        if (name == null) {
            return;
        }
        Name n = add(variant, type[0], type[1], name, null, null, file);
        String oid = object.getAttribute("oid");
        if (!oid.isEmpty()) {
            n.oids.add(oid);
        }
        if ("resource".equals(Xml.objectElementName(object))) {
            for (String attribute : attributes(object, name)) {
                add(variant, "attributes:" + name, "AttributeRef", attribute, name, null, file);
            }
        }
    }

    /** The orig of the object's name ({@code <name>x</name>} or {@code <name><orig>x</orig></name>}). */
    private static String objectName(Element object) {
        Element name = Xml.child(object, "name");
        if (name == null) {
            return null;
        }
        Element orig = Xml.child(name, "orig");
        String text = (orig != null ? orig : name).getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    private Set<String> attributes(Element resource, String resourceName) {
        Set<String> out = new LinkedHashSet<>();
        Element schemaHandling = Xml.child(resource, "schemaHandling");
        if (schemaHandling != null) {
            for (Element type : Xml.descendants(schemaHandling, "objectType")) {
                for (Element attribute : Xml.children(type, "attribute")) {
                    Element ref = Xml.child(attribute, "ref");
                    String text = ref != null ? ref.getTextContent().trim() : attribute.getAttribute("ref").trim();
                    if (!text.isEmpty()) {
                        out.add(localPart(text));
                    }
                }
            }
        }
        // a stored schema: every element of its object classes
        Element schema = Xml.child(resource, "schema");
        Element definition = schema != null ? Xml.child(schema, "definition") : null;
        if (definition != null) {
            for (Element element : Xml.descendants(definition, "element")) {
                String name = element.getAttribute("name");
                if (!name.isEmpty() && element.getParentNode() instanceof Element
                        && "sequence".equals(((Element) element.getParentNode()).getLocalName())) {
                    out.add(name);
                }
            }
        }
        Manifest.Target target = manifest.targets.get(resourceName);
        boolean csv = target != null ? "csv".equals(target.connector) : connectorType(resource).contains(".csv.");
        String delimiter = ";";
        if (csv) {
            Element configuration = Xml.child(resource, "connectorConfiguration");
            if (configuration != null) {
                for (String property : List.of("uniqueAttribute", "nameAttribute", "passwordAttribute")) {
                    Element value = Xml.firstDescendant(configuration, property);
                    if (value != null && !value.getTextContent().trim().isEmpty()) {
                        out.add(value.getTextContent().trim());
                    }
                }
                Element fieldDelimiter = Xml.firstDescendant(configuration, "fieldDelimiter");
                if (fieldDelimiter != null && !fieldDelimiter.getTextContent().isEmpty()) {
                    delimiter = fieldDelimiter.getTextContent().substring(0, 1);
                }
            }
        }
        if (target != null && target.initialData != null) {
            out.addAll(csv ? csvHeader(target.initialData, delimiter) : ldifAttributes(target.initialData));
        }
        return out;
    }

    private static String connectorType(Element resource) {
        Element ref = Xml.child(resource, "connectorRef");
        return ref == null ? "" : ref.getTextContent().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> csvHeader(Path file, String delimiter) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            List<String> out = new ArrayList<>();
            if (header == null) {
                return out;
            }
            for (String column : header.split(Pattern.quote(delimiter))) {
                String c = column.trim();
                if (c.length() >= 2 && c.startsWith("\"") && c.endsWith("\"")) {
                    c = c.substring(1, c.length() - 1);
                }
                if (!c.isEmpty()) {
                    out.add(c);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** The attribute names of an LDIF file (without options; not {@code dn}, {@code objectClass}, change records). */
    private static Set<String> ldifAttributes(Path file) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : lines.collect(Collectors.toList())) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(" ") || line.startsWith("-")) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String attribute = line.substring(0, colon);
                int option = attribute.indexOf(';');
                if (option > 0) {
                    attribute = attribute.substring(0, option);
                }
                String lower = attribute.toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("dn", "objectclass", "changetype", "control", "version", "add", "replace", "delete",
                        "newrdn", "deleteoldrdn", "newsuperior").contains(lower)) {
                    out.add(attribute);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return out;
    }

    private void readSchema(String variant) {
        List<Path> xsds;
        try (Stream<Path> files = Files.walk(manifest.schema)) {
            xsds = files.filter(p -> p.toString().endsWith(".xsd")).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + manifest.schema, e);
        }
        for (Path xsd : xsds) {
            Document doc;
            try {
                doc = Xml.parse(Files.readString(xsd, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + xsd, e);
            } catch (IllegalArgumentException e) {
                warnings.add(xsd + ": not read (" + e.getMessage() + ")");
                continue;
            }
            Element schema = doc.getDocumentElement();
            String namespace = schema.getAttribute("targetNamespace");
            for (Element complexType : Xml.children(schema, "complexType")) {
                boolean user = false;
                for (Element extension : Xml.descendants(complexType, "extension")) {
                    if (NS_ANNOTATION.equals(extension.getNamespaceURI())
                            && "UserType".equals(localPart(extension.getAttribute("ref")))) {
                        user = true;
                    }
                }
                if (!user) {
                    continue;
                }
                for (Element element : Xml.descendants(complexType, "element")) {
                    String name = element.getAttribute("name");
                    if (name.isEmpty() && !element.getAttribute("ref").isEmpty()) {
                        name = localPart(element.getAttribute("ref"));
                    }
                    if (!name.isEmpty()) {
                        add(variant, "UserExtension", "ItemRef", "extension/" + name, null, namespace,
                                manifest.dir.relativize(xsd).toString().replace('\\', '/'));
                    }
                }
            }
        }
    }

    private Name add(String variant, String group, String refType, String name, String resource, String namespace,
            String file) {
        Name n = groups.computeIfAbsent(group, g -> new TreeMap<>())
                .computeIfAbsent(name, k -> new Name(group, refType, name, resource, namespace));
        n.variants.add(variant);
        n.files.add(file);
        return n;
    }

    private String substitute(String text) {
        if (!text.contains("{{")) {
            return text;
        }
        Matcher m = PROPERTY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = manifest.properties.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String localPart(String qname) {
        int colon = qname.lastIndexOf(':');
        return (colon >= 0 ? qname.substring(colon + 1) : qname).trim();
    }
}
