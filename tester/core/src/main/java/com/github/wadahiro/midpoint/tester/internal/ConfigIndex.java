package com.github.wadahiro.midpoint.tester.internal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.github.wadahiro.midpoint.scan.ConfigScanner;
import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.Identity;
import com.github.wadahiro.midpoint.tester.Location;

/**
 * The configuration files given to the runtime (imported objects and resources), read as plain XML
 * ({@link ConfigFiles}: elements, identities, places), to tell where an evaluated mapping or expression is
 * ({@link #locate}). Mappings are found by what midPoint says about them (owner
 * object, position in it, name), expressions by their position in a located mapping or by their canonical form
 * ({@link ConfigScanner}), within the nearest located enclosing evaluation.
 *
 * Re-registering a file (or another file with the same object) replaces what was known about it.
 */
public final class ConfigIndex extends ConfigFiles implements Recording.Locator {

    private static final Set<String> PARTS = Set.of("expression", "condition");
    private static final ConfigIndex INSTANCE = new ConfigIndex();
    private static final Trace LOGGER = TraceManager.getTrace(ConfigIndex.class);

    /** Every configuration file registered with the runtime's index since the JVM started (never reset). */
    private static final Map<String, Path> HISTORY = Collections.synchronizedMap(new LinkedHashMap<>());

    /** The node each location handed out stands for (to search within a located mapping). */
    private final Map<Location, Node> nodes = Collections.synchronizedMap(new WeakHashMap<>());

    private ConfigIndex() {
    }

    public static ConfigIndex get() {
        return INSTANCE;
    }

    /** An index of its own (e.g. of a directory to report coverage on), apart from the runtime's. */
    public static ConfigIndex create() {
        return new ConfigIndex();
    }

    /**
     * The configuration files given to the runtime since the JVM started, by the name locations use: what coverage
     * reports on. XML given as text is not included.
     */
    public static Map<String, Path> registeredFiles() {
        synchronized (HISTORY) {
            return new LinkedHashMap<>(HISTORY);
        }
    }

    // ---- registration ---------------------------------------------------------------------------------------

    /** Reads a configuration file; {@code name} is how locations name it (the path as the test gave it). */
    public void register(Path file, String name) {
        register(file, name, true);
    }

    /**
     * Reads a file; {@code configuration} false for XML a test gave as text (written to a work file), which
     * {@link #registeredFiles()} leaves out.
     */
    public synchronized void register(Path file, String name, boolean configuration) {
        if (configuration && this == INSTANCE) {
            HISTORY.put(name, file);
        }
        // a file the runtime could import but this index cannot read: its evaluations stay unlocated
        if (read(file, name)) {
            for (ObjectEntry object : objectsOf(name)) {
                duplicateMappingNames(object).forEach(warning -> LOGGER.warn("{}", warning));
            }
        }
    }

    /** What is registered now; the runtime keeps it with its snapshots, so a reset forgets later registrations. */
    public synchronized Object capture() {
        return new LinkedHashMap<>(files);
    }

    @SuppressWarnings("unchecked")
    public synchronized void restore(Object captured) {
        files.clear();
        files.putAll((Map<String, FileEntry>) captured);
    }

    /** Whether an expression with this canonical form is in a registered file. */
    public synchronized boolean isKnownFingerprint(String fingerprint) {
        return !expressions(fingerprint).isEmpty();
    }

    /**
     * Whether a registered file (or XML given as text) has an object with this OID: the test imported it. Objects only,
     * unlike {@link #isKnownOid}, which also matches references (an {@code ownerRef} to the administrator).
     */
    public synchronized boolean hasObject(String oid) {
        return object(oid) != null;
    }

    public synchronized boolean isKnownOid(String oid) {
        return files.values().stream().anyMatch(f -> f.oids.contains(oid));
    }

    // ---- locating -------------------------------------------------------------------------------------------

    @Override
    public synchronized Location locate(Record record) {
        return record.kind == Evaluation.Kind.MAPPING ? locateMapping(record) : locateExpression(record);
    }

    private Location locateMapping(Record r) {
        ObjectEntry object = r.ownerOid != null ? object(r.ownerOid) : null;
        // an owner no file has: a user's own assignment, or (4.0, 4.4) the object a role's condition is evaluated for
        boolean unknownOwner = r.ownerOid != null && object == null;
        if (object != null && r.elementPath != null) {
            Node node = object.byIndexedPath.get(r.elementPath);
            // a named mapping must have its name there (the file may differ from what midPoint loaded)
            if (node != null && (r.mappingName == null || r.mappingName.equals(node.nameChild))) {
                return location(node, "origin", List.of());
            }
        }
        Node enclosing = enclosingNode(r);
        if (object != null && r.mappingName != null) {
            List<Node> named = within(object.nodes.stream()
                    .filter(n -> n.parent != null && r.mappingName.equals(n.nameChild))
                    .collect(Collectors.toList()), enclosing);
            if (named.size() == 1) {
                return location(named.get(0), "name", List.of());
            }
        }
        // the mapping's expression (or condition) by its canonical form
        for (Record child : children(r)) {
            if (child.part != null && PARTS.contains(child.part) && child.fingerprint != null) {
                List<Node> mappings = within(expressions(child.fingerprint).stream()
                        .filter(n -> n.name.equals(child.part) && n.parent != null)
                        .filter(n -> object == null || n.object == object)
                        .map(n -> n.parent)
                        .filter(n -> r.mappingName == null ? !unknownOwner : r.mappingName.equals(n.nameChild))
                        .collect(Collectors.toList()), enclosing);
                if (!mappings.isEmpty()) {
                    return location(mappings.get(0), "fingerprint", mappings.subList(1, mappings.size()));
                }
            }
        }
        return Location.UNKNOWN;
    }

    private Location locateExpression(Record r) {
        Record parent = r.parent;
        if (parent != null && parent.kind == Evaluation.Kind.MAPPING && r.part != null && PARTS.contains(r.part)) {
            Location mapping = parent.location();
            Node mappingNode = nodes.get(mapping);
            if (mappingNode != null) {
                for (Node child : mappingNode.children) {
                    if (child.name.equals(r.part)) {
                        return location(child, mapping.resolvedBy(), List.of());
                    }
                }
                // no element: midPoint's default expression (asIs) of the mapping
                return mapping;
            }
        }
        if (r.fingerprint == null) {
            return Location.UNKNOWN;
        }
        List<Node> candidates = expressions(r.fingerprint);
        // within the nearest enclosing evaluation that is located, e.g. the mapping of a search filter
        for (Record enclosing = parent; enclosing != null; enclosing = enclosing.parent) {
            Node node = nodes.get(enclosing.location());
            if (node == null && inUnknownObject(enclosing)) {
                // inside a mapping not found in the files whose owner no file has: an identical expression elsewhere
                // is not it
                return Location.UNKNOWN;
            }
            if (node != null) {
                List<Node> within = candidates.stream().filter(n -> n.isWithin(node)).collect(Collectors.toList());
                if (!within.isEmpty()) {
                    candidates = within;
                }
                break;
            }
        }
        candidates = narrow(candidates, r);
        if (candidates.isEmpty()) {
            return Location.UNKNOWN;
        }
        return location(candidates.get(0), "fingerprint", candidates.subList(1, candidates.size()));
    }

    /**
     * Identical expressions told apart by what midPoint or the driver says about the evaluation: a hook's script by the
     * hook's name, an evaluation in a driver's scope (a dashboard widget) or a notifier's scope (its filters) by the
     * scope's identity, a search's populate expression by its description ("populate expression in ..."). Each applies
     * only when it leaves a candidate.
     */
    private List<Node> narrow(List<Node> candidates, Record r) {
        if (candidates.size() < 2) {
            return candidates;
        }
        if (r.hook != null && r.hook.name() != null) {
            // the expression place of a hook is the hook itself (the parent of its script)
            candidates = prefer(candidates, n -> n.name.equals("hook") && r.hook.name().equals(n.nameChild)
                    || hasAncestor(n, a -> a.name.equals("hook") && r.hook.name().equals(a.nameChild)));
        }
        String[] scope = r.scope;
        if (scope != null) {
            candidates = prefer(candidates, n -> (scope[0] == null || scope[0].equals(n.object.oid)) && hasAncestor(n,
                    a -> (scope[1] == null || a.name.equals(scope[1])) && scope[3].equals(a.leafTexts.get(scope[2]))));
        }
        if (r.description != null) {
            boolean populate = r.description.contains("populate expression");
            candidates = prefer(candidates, n -> populate == hasAncestor(n, a -> a.name.startsWith("populate")));
        }
        return candidates;
    }

    private static List<Node> prefer(List<Node> candidates, java.util.function.Predicate<Node> test) {
        List<Node> preferred = candidates.stream().filter(test).collect(Collectors.toList());
        return preferred.isEmpty() ? candidates : preferred;
    }

    private static boolean hasAncestor(Node node, java.util.function.Predicate<Node> test) {
        for (Node n = node.parent; n != null; n = n.parent) {
            if (test.test(n)) {
                return true;
            }
        }
        return false;
    }

    /** Whether midPoint says the evaluation is in an object that no registered file has. */
    private boolean inUnknownObject(Record r) {
        return r.kind == Evaluation.Kind.MAPPING && r.ownerOid != null && object(r.ownerOid) == null;
    }

    /** The element of the nearest enclosing evaluation that is located; null when none is. */
    private Node enclosingNode(Record r) {
        for (Record enclosing = r.parent; enclosing != null; enclosing = enclosing.parent) {
            Node node = nodes.get(enclosing.location());
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /** The candidates within {@code enclosing}, if any are; else all of them. */
    private static List<Node> within(List<Node> candidates, Node enclosing) {
        if (enclosing == null) {
            return candidates;
        }
        List<Node> within = candidates.stream().filter(n -> n.isWithin(enclosing)).collect(Collectors.toList());
        return within.isEmpty() ? candidates : within;
    }

    /**
     * The text of {@code <keyChild>} of the nearest {@code <element>} that is (or encloses) the element of this
     * location; null when there is none or the location was not handed out by this index.
     */
    public String enclosingKey(Location location, String element, String keyChild) {
        Node node = nodes.get(location);
        for (Node n = node; n != null; n = n.parent) {
            if (n.name.equals(element)) {
                return n.leafTexts.get(keyChild);
            }
        }
        return null;
    }

    // ---- identity -------------------------------------------------------------------------------------------

    /**
     * The structural identity of the element of this location (see {@link Identity}); null when the location was not
     * handed out by this index.
     */
    public synchronized Identity identity(Location location) {
        Node node = nodes.get(location);
        return node != null ? identity(node) : null;
    }

    private static List<Record> children(Record r) {
        synchronized (r.children) {
            return new ArrayList<>(r.children);
        }
    }

    private Location location(Node node, String resolvedBy, List<Node> alternatives) {
        List<Location> others = alternatives.stream().map(n -> plain(n, resolvedBy)).collect(Collectors.toList());
        Location location = new Location(node.object.file, node.line, node.path, node.object.type, node.object.oid,
                node.object.name(), resolvedBy, others);
        nodes.put(location, node);
        return location;
    }

    private Location plain(Node node, String resolvedBy) {
        Location location = new Location(node.object.file, node.line, node.path, node.object.type, node.object.oid,
                node.object.name(), resolvedBy, List.of());
        // so that the identity of an alternative is known too
        nodes.put(location, node);
        return location;
    }
}
