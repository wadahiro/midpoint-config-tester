package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.github.wadahiro.midpoint.scan.ConfigScanner;
import com.github.wadahiro.midpoint.tester.Identity;

/**
 * Configuration files read as plain XML: their objects and elements, the structural {@link Identity} of each element,
 * and their expression places ({@link ConfigScanner}'s definition). This part of the configuration index needs no
 * midPoint runtime, so the static checks (tester/static) use it too; {@link ConfigIndex} adds locating evaluations.
 *
 * Re-reading a file (or another file with the same object) replaces what was known about it.
 */
public class ConfigFiles {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** An element of a configuration file. */
    static final class Node {
        final String name;
        final Node parent;
        final ObjectEntry object;
        final int line;
        /** From the object's root, e.g. {@code role/inducement/construction}. */
        final String path;
        /** Below the object's root, with positions: {@code inducement[1]/construction[1]}. */
        final String indexedPath;
        final Map<String, Integer> childCounts = new HashMap<>();
        final List<Node> children = new ArrayList<>();
        /** The text of a {@code <name>} child, if any. */
        String nameChild;
        /** The texts of child elements without children (the first of each name), e.g. {@code number}. */
        final Map<String, String> leafTexts = new HashMap<>();
        /** The {@code oid} attribute (object roots, references), else null. */
        String oid;
        /** A reference's {@code type} attribute as an object's element name ({@code RoleType}: {@code role}), else null. */
        String refType;
        /** The {@code id} attribute (container values), else null. */
        String id;
        /** The text of an element without children, else null. */
        String text;

        Node(String name, Node parent, ObjectEntry object, int line) {
            this.name = name;
            this.parent = parent;
            this.object = object;
            this.line = line;
            if (parent == null) {
                path = name;
                indexedPath = "";
            } else {
                int index = parent.childCounts.merge(name, 1, Integer::sum);
                path = parent.path + "/" + name;
                indexedPath = (parent.indexedPath.isEmpty() ? "" : parent.indexedPath + "/") + name + "[" + index + "]";
                parent.children.add(this);
            }
        }

        Node child(String name) {
            for (Node child : children) {
                if (child.name.equals(name)) {
                    return child;
                }
            }
            return null;
        }

        boolean isWithin(Node ancestor) {
            for (Node n = this; n != null; n = n.parent) {
                if (n == ancestor) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One object of a file (the root element, or a child of {@code <objects>}). */
    static final class ObjectEntry {
        final String file;
        String type;
        String oid;
        Node root;
        final Map<String, Node> byIndexedPath = new HashMap<>();
        final List<Node> nodes = new ArrayList<>();

        ObjectEntry(String file) {
            this.file = file;
        }

        String name() {
            return root.nameChild;
        }
    }

    static final class FileEntry {
        final List<ObjectEntry> objects = new ArrayList<>();
        /** The expression elements ({@link ConfigScanner}'s entries), in document order. */
        final Map<Node, ConfigScanner.Entry> scanned = new LinkedHashMap<>();
        /** Expression elements by fingerprint. */
        final Map<String, List<Node>> expressions = new HashMap<>();
        final Set<String> oids = new HashSet<>();
    }

    final Map<String, FileEntry> files = new LinkedHashMap<>();

    ConfigFiles() {
    }

    /** Files of its own, read with {@link #read}. */
    public static ConfigFiles create() {
        return new ConfigFiles();
    }

    /** Every {@code .xml} file under the directory, named by its path relative to it ({@code /} separators). */
    public static ConfigFiles scan(Path dir) {
        return scan(dir, new ConfigFiles());
    }

    /** Reads every {@code .xml} file under the directory into {@code files}, in path order. */
    public static <T extends ConfigFiles> T scan(Path dir, T files) {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dir)) {
            paths = walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".xml")).sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dir, e);
        }
        for (Path file : paths) {
            files.register(file, dir.relativize(file).toString().replace(File.separatorChar, '/'));
        }
        return files;
    }

    // ---- reading --------------------------------------------------------------------------------------------

    /** Reads a configuration file; {@code name} is how places and identities name it. */
    public void register(Path file, String name) {
        read(file, name);
    }

    /** Reads a file; false (and nothing known about it) when it is no readable XML. */
    public synchronized boolean read(Path file, String name) {
        FileEntry entry = new FileEntry();
        try {
            readElements(file, name, entry);
            for (ConfigScanner.Entry expression : ConfigScanner.scanFile(file, name)) {
                Node node = find(entry, expression.line, expression.path);
                if (node != null) {
                    entry.expressions.computeIfAbsent(expression.fingerprint(), k -> new ArrayList<>()).add(node);
                    entry.scanned.put(node, expression);
                }
            }
            Matcher m = UUID.matcher(Files.readString(file));
            while (m.find()) {
                entry.oids.add(m.group());
            }
        } catch (IOException | XMLStreamException e) {
            return false;
        }
        // the latest registration of an object wins (see isCurrent())
        files.remove(name);
        files.put(name, entry);
        return true;
    }

    /** The objects of a file as read, in document order (the file name as given to {@link #read}). */
    synchronized List<ObjectEntry> objectsOf(String file) {
        FileEntry entry = files.get(file);
        return entry != null ? entry.objects : List.of();
    }

    private static void readElements(Path file, String name, FileEntry entry) throws IOException, XMLStreamException {
        try (InputStream in = Files.newInputStream(file)) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.IS_COALESCING, true);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            Deque<Node> stack = new ArrayDeque<>();
            StringBuilder text = new StringBuilder();
            boolean wrapped = false;
            try {
                while (reader.hasNext()) {
                    switch (reader.next()) {
                        case XMLStreamConstants.START_ELEMENT:
                            String local = reader.getLocalName();
                            int line = reader.getLocation().getLineNumber();
                            text.setLength(0);
                            if (stack.isEmpty() && !wrapped && "objects".equals(local)) {
                                wrapped = true;
                                continue;
                            }
                            Node parent = stack.peek();
                            Node node;
                            if (parent == null && "object".equals(local)) {
                                // <object xsi:type="c:RoleType">: the element name of its type
                                String typed = elementName(reader.getAttributeValue(
                                        javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
                                local = typed != null ? typed : local;
                            }
                            if (parent == null) {
                                ObjectEntry object = new ObjectEntry(name);
                                object.type = local;
                                object.oid = reader.getAttributeValue(null, "oid");
                                node = new Node(local, null, object, line);
                                object.root = node;
                                entry.objects.add(object);
                            } else {
                                node = new Node(local, parent, parent.object, line);
                            }
                            node.oid = reader.getAttributeValue(null, "oid");
                            node.refType = elementName(reader.getAttributeValue(null, "type"));
                            node.id = reader.getAttributeValue(null, "id");
                            node.object.byIndexedPath.put(node.indexedPath, node);
                            node.object.nodes.add(node);
                            stack.push(node);
                            break;
                        case XMLStreamConstants.CHARACTERS:
                        case XMLStreamConstants.CDATA:
                            text.append(reader.getText());
                            break;
                        case XMLStreamConstants.END_ELEMENT:
                            if (stack.isEmpty()) {
                                break;
                            }
                            Node ended = stack.pop();
                            if ("name".equals(ended.name) && ended.parent != null && ended.children.isEmpty()) {
                                ended.parent.nameChild = text.toString().strip();
                            }
                            if (ended.parent != null && ended.children.isEmpty()) {
                                ended.text = text.toString().strip();
                                ended.parent.leafTexts.putIfAbsent(ended.name, ended.text);
                            }
                            text.setLength(0);
                            break;
                        default:
                            break;
                    }
                }
            } finally {
                reader.close();
            }
        }
    }

    private static Node find(FileEntry entry, int line, String path) {
        for (ObjectEntry object : entry.objects) {
            for (Node node : object.nodes) {
                if (node.line == line && node.path.equals(path)) {
                    return node;
                }
            }
        }
        return null;
    }

    // ---- identity -------------------------------------------------------------------------------------------

    /** The elements holding a mapping that a {@code <name>} should tell apart within an object. */
    private static final Set<String> MAPPINGS = Set.of("mapping", "inbound", "outbound");

    Identity identity(Node node) {
        List<Identity.Segment> segments = new ArrayList<>();
        for (Node n = node; n != null && n.parent != null; n = n.parent) {
            segments.add(0, segment(n));
        }
        ObjectEntry object = node.object;
        return new Identity(object.type, object.oid, object.name(), segments, true);
    }

    private Identity.Segment segment(Node node) {
        String[] key = key(node);
        List<Node> siblings = node.parent.children.stream().filter(n -> n.name.equals(node.name))
                .collect(Collectors.toList());
        List<Node> same = siblings.stream().filter(n -> Arrays.equals(key, key(n))).collect(Collectors.toList());
        if (same.size() < 2) {
            // nothing else has this discriminator (or, without one, nothing else lacks one)
            return new Identity.Segment(node.name, key != null ? key[0] : null, key != null ? key[1] : null, 0);
        }
        String[] secondary = secondaryKey(node, same, siblings);
        if (secondary != null) {
            return key == null ? new Identity.Segment(node.name, secondary[0], secondary[1], 0)
                    : new Identity.Segment(node.name, key[0], key[1], secondary[0], secondary[1], 0);
        }
        // its position: among all same-named siblings without a discriminator, else among those that share it
        int ordinal = key == null ? siblings.indexOf(node) + 1 : same.indexOf(node) + 1;
        return new Identity.Segment(node.name, key != null ? key[0] : null, key != null ? key[1] : null, ordinal);
    }

    /** Free-text discriminators are cut to this length (with "..."): identities stay readable. */
    private static final int TEXT_LENGTH = 40;

    /**
     * What tells the element apart from the siblings that share its discriminator (or lack of one), instead of its
     * position: the first of its {@link #secondaryKeys} whose value no other of them has; null when none does.
     */
    private String[] secondaryKey(Node node, List<Node> same, List<Node> siblings) {
        if (node.name.equals("argument") || node.name.equals("argumentExpression")) {
            // the arguments of a message: their position is what they are ({0}, {1}...), not an accident of order
            return new String[] { "argument", String.valueOf(siblings.indexOf(node) + 1) };
        }
        for (Map.Entry<String, java.util.function.Function<Node, String>> kind : secondaryKeys(node).entrySet()) {
            String value = kind.getValue().apply(node);
            if (value == null || value.isEmpty()) {
                continue;
            }
            boolean unique = same.stream().filter(n -> n != node)
                    .noneMatch(n -> value.equals(kind.getValue().apply(n)));
            if (unique) {
                return new String[] { kind.getKey(), value };
            }
        }
        return null;
    }

    /**
     * The discriminators used only among siblings that the primary one ({@link #key}) does not tell apart, in order:
     * element-specific ones (an inducement's {@code order}, focus mappings and target filter; a lifecycle transition's
     * target state; a widget data field's type; a check expression's failure message; a dashboard variation's
     * condition), then the display label, the description and the documentation of any element.
     */
    private Map<String, java.util.function.Function<Node, String>> secondaryKeys(Node node) {
        Map<String, java.util.function.Function<Node, String>> keys = new LinkedHashMap<>();
        switch (node.name) {
            case "assignment":
            case "inducement":
                keys.put("order", ConfigFiles::order);
                keys.put("focusMappings", ConfigFiles::focusMappings);
                keys.put("targetFilter", ConfigFiles::targetFilter);
                break;
            case "transition":
                keys.put("targetState", n -> n.leafTexts.get("targetState"));
                break;
            case "dataField":
                keys.put("fieldType", n -> n.leafTexts.get("fieldType"));
                break;
            case "checkExpression":
                keys.put("failureMessage", n -> text(n.leafTexts.get("failureMessage")));
                break;
            case "variation":
                keys.put("condition", n -> {
                    Node condition = n.child("condition");
                    return condition == null ? null : condition.nameChild != null ? condition.nameChild
                            : text(condition.leafTexts.get("description"));
                });
                break;
            default:
                break;
        }
        keys.put("label", n -> {
            Node display = n.child("display");
            return display != null ? text(display.leafTexts.get("label")) : null;
        });
        keys.put("description", n -> text(n.leafTexts.get("description")));
        keys.put("documentation", n -> text(n.leafTexts.get("documentation")));
        return keys;
    }

    /** An inducement's {@code order} (or its single {@code orderConstraint/order}); 1 when not given. */
    private static String order(Node node) {
        String order = node.leafTexts.get("order");
        if (order == null) {
            List<Node> constraints = node.children.stream().filter(n -> n.name.equals("orderConstraint"))
                    .collect(Collectors.toList());
            order = constraints.size() == 1 ? constraints.get(0).leafTexts.get("order") : null;
        }
        return order != null ? order : "1";
    }

    /** The names of an assignment's focus mappings (the target path of an unnamed one), comma-separated. */
    private static String focusMappings(Node node) {
        Node mappings = node.child("focusMappings");
        if (mappings == null) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (Node mapping : mappings.children) {
            if (mapping.name.equals("mapping")) {
                Node target = mapping.child("target");
                String name = mapping.nameChild != null ? mapping.nameChild
                        : target != null ? target.leafTexts.get("path") : null;
                names.add(name != null ? name : "?");
            }
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    /**
     * A target given by a filter (no OID): the reference's type and the paths the filter compares, e.g.
     * {@code org:costCenter}.
     */
    private static String targetFilter(Node node) {
        Node targetRef = node.child("targetRef");
        Node filter = targetRef != null && targetRef.oid == null ? targetRef.child("filter") : null;
        if (filter == null) {
            return null;
        }
        List<String> paths = new ArrayList<>();
        collectPaths(filter, paths);
        return or(targetRef.refType, "?") + ":" + String.join(",", paths);
    }

    private static void collectPaths(Node node, List<String> paths) {
        for (Node child : node.children) {
            if (child.name.equals("path") && child.text != null && !child.text.isEmpty()
                    && !child.parent.name.equals("expression")) {
                paths.add(child.text);
            } else if (!child.name.equals("expression")) {
                collectPaths(child, paths);
            }
        }
    }

    /** Free text as a discriminator: whitespace collapsed, cut to {@link #TEXT_LENGTH} characters. */
    private static String text(String text) {
        if (text == null) {
            return null;
        }
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= TEXT_LENGTH ? collapsed : collapsed.substring(0, TEXT_LENGTH).strip() + "...";
    }

    /** What tells this element apart from its siblings: {kind of discriminator, value}, or null. */
    private String[] key(Node node) {
        Map<String, String> leaf = node.leafTexts;
        switch (node.name) {
            case "objectType":
                if (node.parent != null && "schemaHandling".equals(node.parent.name)) {
                    return new String[] { "kind/intent", or(leaf.get("kind"), "account") + "/"
                            + or(leaf.get("intent"), "default") };
                }
                break;
            case "objectSynchronization":
                // the synchronization of a resource object type before 4.6
                return new String[] { "kind/intent", or(leaf.get("kind"), "account") + "/"
                        + or(leaf.get("intent"), "default") };
            case "assignment":
            case "inducement":
                Node targetRef = node.child("targetRef");
                if (targetRef != null && targetRef.oid != null) {
                    return new String[] { "target", objectName(targetRef, null) };
                }
                Node construction = node.child("construction");
                if (construction != null) {
                    return new String[] { "construction", construction(construction) };
                }
                Node policyRule = node.child("policyRule");
                if (policyRule != null && policyRule.leafTexts.get("name") != null) {
                    return new String[] { "policyRule", policyRule.leafTexts.get("name") };
                }
                return null;
            case "mapping":
            case "inbound":
            case "outbound":
                if (leaf.get("name") != null) {
                    return new String[] { "name", leaf.get("name") };
                }
                Node target = node.child("target");
                String path = target != null ? target.leafTexts.get("path") : null;
                return path != null ? new String[] { "target", path } : null;
            case "stage":
            case "stageDefinition":
                // approval and certification stages run in the order of their numbers
                if (leaf.get("number") != null) {
                    return new String[] { "number", leaf.get("number") };
                }
                break;
            case "reaction":
                if (leaf.get("name") != null) {
                    return new String[] { "name", leaf.get("name") };
                }
                String situations = node.children.stream().filter(n -> n.name.equals("situation") && n.text != null)
                        .map(n -> n.text).collect(Collectors.joining(","));
                return situations.isEmpty() ? null : new String[] { "situation", situations };
            default:
                break;
        }
        for (String k : List.of("name", "ref", "identifier", "number")) {
            if (leaf.get(k) != null && !leaf.get(k).isEmpty()) {
                return new String[] { k, leaf.get(k) };
            }
        }
        return null;
    }

    private String construction(Node construction) {
        Node resourceRef = construction.child("resourceRef");
        String resource = resourceRef != null && resourceRef.oid != null ? objectName(resourceRef, "resource") : "?";
        return resource + "/" + or(construction.leafTexts.get("kind"), "account") + "/"
                + or(construction.leafTexts.get("intent"), "default");
    }

    /**
     * The name of the registered object a reference points to (of the reference's type, else of this type, when
     * objects of different types share the OID in the files), else the OID.
     */
    private String objectName(Node ref, String type) {
        String refType = ref.refType != null ? ref.refType : type;
        ObjectEntry object = refType != null ? object(ref.oid, refType) : null;
        object = object != null ? object : object(ref.oid, null);
        return object != null && object.name() != null ? object.name() : ref.oid;
    }

    /** {@code c:RoleType} to {@code role}, as the object's element is named; null for null. */
    private static String elementName(String type) {
        return ConfigScanner.elementNameOfType(type);
    }

    private static String or(String value, String otherwise) {
        return value != null && !value.isEmpty() ? value : otherwise;
    }

    // ---- elements by item path ------------------------------------------------------------------------------

    /** An element of a file: its identity, and file and line for display. */
    public static final class Element {
        public final Identity identity;
        public final String file;
        public final int line;

        Element(Identity identity, String file, int line) {
            this.identity = identity;
            this.file = file;
            this.line = line;
        }

        @Override
        public String toString() {
            return identity + " @ " + file + ":" + line;
        }
    }

    /** The objects of a file, in document order: their roots (identities without segments). */
    public synchronized List<Element> objects(String file) {
        return objectsOf(file).stream().map(o -> new Element(identity(o.root), o.file, o.root.line))
                .collect(Collectors.toList());
    }

    /**
     * The element an item path of midPoint's points to in the {@code index}-th object of the file: each step an
     * element name and the container value's ID (null for none). An ID is matched to the element's {@code id}
     * attribute; values without one count as numbered in document order after the highest ID of their siblings,
     * as the repository numbers them on import. Where a step is not found, the deepest element found; null when
     * the file or object is unknown.
     */
    public synchronized Element element(String file, int index, List<Map.Entry<String, Long>> path) {
        List<ObjectEntry> objects = objectsOf(file);
        if (index < 0 || index >= objects.size()) {
            return null;
        }
        Node node = objects.get(index).root;
        for (Map.Entry<String, Long> step : path) {
            Node next = child(node, step.getKey(), step.getValue());
            if (next == null) {
                break;
            }
            node = next;
        }
        return new Element(identity(node), node.object.file, node.line);
    }

    private static Node child(Node node, String name, Long id) {
        List<Node> named = node.children.stream().filter(n -> n.name.equals(name)).collect(Collectors.toList());
        if (named.isEmpty()) {
            return null;
        }
        if (id == null) {
            return named.get(0);
        }
        long next = 1;
        for (Node n : named) {
            Long explicit = parseId(n.id);
            if (explicit != null && explicit.equals(id)) {
                return n;
            }
            if (explicit != null) {
                next = Math.max(next, explicit + 1);
            }
        }
        for (Node n : named) {
            if (parseId(n.id) == null && next++ == id) {
                return n;
            }
        }
        return null;
    }

    private static Long parseId(String id) {
        try {
            return id != null ? Long.valueOf(id.strip()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- mapping names --------------------------------------------------------------------------------------

    /**
     * Mappings ({@code mapping}, {@code inbound}, {@code outbound}) that share their {@code <name>} with another one
     * in the same object, one line per name: {@code role:Employee has 2 mappings named 'title': <identity>, ...}.
     * midPoint accepts them; selecting by name is then ambiguous.
     */
    public synchronized List<String> duplicateMappingNames() {
        List<String> out = new ArrayList<>();
        for (FileEntry file : files.values()) {
            for (ObjectEntry object : file.objects) {
                if (isCurrent(object)) {
                    out.addAll(duplicateMappingNames(object));
                }
            }
        }
        return out;
    }

    /**
     * The same as {@link #duplicateMappingNames()}, structured: per object (its root element), per name, the
     * mappings.
     */
    public synchronized Map<Element, Map<String, List<Element>>> duplicateMappings() {
        Map<Element, Map<String, List<Element>>> out = new LinkedHashMap<>();
        for (FileEntry file : files.values()) {
            for (ObjectEntry object : file.objects) {
                Map<String, List<Node>> duplicates = duplicates(object);
                if (isCurrent(object) && !duplicates.isEmpty()) {
                    Map<String, List<Element>> byName = new LinkedHashMap<>();
                    duplicates.forEach((name, nodes) -> byName.put(name, nodes.stream()
                            .map(n -> new Element(identity(n), object.file, n.line)).collect(Collectors.toList())));
                    out.put(new Element(identity(object.root), object.file, object.root.line), byName);
                }
            }
        }
        return out;
    }

    List<String> duplicateMappingNames(ObjectEntry object) {
        List<String> out = new ArrayList<>();
        duplicates(object).forEach((name, list) -> out.add(object.type + ":" + object.name() + " has " + list.size()
                + " mappings named '" + name + "': "
                + list.stream().map(n -> identity(n) + " (" + object.file + ":" + n.line + ")")
                        .collect(Collectors.joining(", "))));
        return out;
    }

    private static Map<String, List<Node>> duplicates(ObjectEntry object) {
        Map<String, List<Node>> byName = new LinkedHashMap<>();
        for (Node node : object.nodes) {
            String name = node.leafTexts.get("name");
            if (MAPPINGS.contains(node.name) && name != null) {
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(node);
            }
        }
        byName.values().removeIf(list -> list.size() < 2);
        return byName;
    }

    // ---- objects --------------------------------------------------------------------------------------------

    /** The object with this OID from the file registered last. */
    ObjectEntry object(String oid) {
        return object(oid, null);
    }

    /** The object with this OID (and element name, unless null) from the file registered last. */
    ObjectEntry object(String oid, String type) {
        ObjectEntry found = null;
        for (FileEntry file : files.values()) {
            for (ObjectEntry object : file.objects) {
                if (oid.equals(object.oid) && (type == null || type.equals(object.type))) {
                    found = object;
                }
            }
        }
        return found;
    }

    /** Whether no file registered later has an object of the same type with the same OID. */
    boolean isCurrent(ObjectEntry object) {
        return object.oid == null || object(object.oid, object.type) == object;
    }

    List<Node> expressions(String fingerprint) {
        List<Node> out = new ArrayList<>();
        files.values().forEach(f -> f.expressions.getOrDefault(fingerprint, List.of()).stream()
                .filter(n -> isCurrent(n.object))
                .forEach(out::add));
        return out;
    }

    // ---- places ---------------------------------------------------------------------------------------------

    /** An expression element of a registered file, with the identity evaluations of it get. */
    public static final class Place {
        public final Identity identity;
        /** The file as registered, the line of the element's start tag and its element path: for display. */
        public final String file;
        public final int line;
        public final String path;
        /** The evaluator elements, in document order ({@code script}, {@code path}, ...). */
        public final List<String> evaluators;
        /** The canonical form of the evaluator elements: identical expressions have the same. */
        public final String fingerprint;
        /** The expression this one is nested in (e.g. the search whose filter it is in), or null. */
        public Place enclosing;
        /** False when a file registered later has the same object (that one is what midPoint has). */
        public final boolean current;
        /** The scanner's entry: scripts and their languages ({@link ConfigScanner.Entry#compile()}). */
        public final ConfigScanner.Entry entry;

        Place(Identity identity, String file, int line, String path, ConfigScanner.Entry entry, boolean current) {
            this.identity = identity;
            this.file = file;
            this.line = line;
            this.path = path;
            this.evaluators = List.copyOf(entry.evaluators);
            this.fingerprint = entry.fingerprint();
            this.current = current;
            this.entry = entry;
        }

        /**
         * Why midPoint cannot evaluate this place: its first evaluator element, or that of an enclosing expression,
         * is not among {@code available} (local names); null when it can, or when {@code available} is empty
         * (unknown).
         */
        public String noEvaluator(Set<String> available) {
            if (available.isEmpty()) {
                return null;
            }
            for (Place p = this; p != null; p = p.enclosing) {
                String evaluator = p.evaluators.get(0);
                if (!available.contains(evaluator)) {
                    return "no evaluator for <" + evaluator + ">"
                            + (p == this ? "" : " of the enclosing " + p.identity + " (" + p.file + ":" + p.line + ")");
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return identity + " @ " + file + ":" + line;
        }
    }

    /**
     * Every expression element of the registered files ({@link ConfigScanner}'s definition: the parent of an
     * evaluator element), with the identity the recorder gives the evaluations of it, in file order.
     */
    public synchronized List<Place> places() {
        List<Place> out = new ArrayList<>();
        Map<ConfigScanner.Entry, Place> byEntry = new HashMap<>();
        for (FileEntry file : files.values()) {
            file.scanned.forEach((node, entry) -> {
                Place place = new Place(identity(node), node.object.file, node.line, node.path, entry,
                        isCurrent(node.object));
                byEntry.put(entry, place);
                out.add(place);
            });
            file.scanned.values().forEach(entry -> {
                if (entry.enclosing != null) {
                    byEntry.get(entry).enclosing = byEntry.get(entry.enclosing);
                }
            });
        }
        return out;
    }
}
