package com.github.wadahiro.midpoint.configtester;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * An authorization matrix as written ({@code authz-matrix.yaml}, {@link AuthzMatrixTest}): personas (columns),
 * objects (the samples the checks are made on, as midPoint's authorizations select an {@code object}, and the targets
 * the assign rows assign, as they select a {@code target}), a tree of checks
 * (rows), the expected values as a default and rules (a rule's note explains the cells it gives), the critical rows.
 * Read with the lines of the file, so a mistake is reported where it is; the rows a group lists with {@code auto} are
 * added from the running midPoint ({@link #expand}: every page of the GUI, the actions on an object, every item of
 * its type, the views, features and menu links of the personas' GUI profiles), and the names are checked against the
 * deployment when the matrix runs.
 *
 * <pre>
 * spec: authz-matrix/1
 * personas:
 *   end-user:  {label: End user, roles: [End user]}
 *   help-desk: {label: Help desk, group: Operations, roles: [Help desk], orgs: [{org: IT, relation: manager}]}
 * objects:
 *   it-user: {user: jack, label: A user of IT}
 *   auditor: {role: Auditor}
 * assignment-targets: [auditor, {object: auditor, relation: owner}]
 * rows:
 *   - {group: pages, label: Pages, auto: pages}
 *   - {group: gui, label: GUI configuration, auto: [views, features, menu-links]}
 *   - group: it-user
 *     object: it-user
 *     auto: [search, get, read, add, modify, delete, assign, unassign, items]
 *     rows: [{id: phone, check: modify telephoneNumber}]
 * default: N
 * rules:
 *   - {name: help-desk-it-users, for: [help-desk], rows: [pages/admin/users, it-user/items/read], value: Y}
 *   - {name: end-user-gets-anyone, for: "*", rows: [it-user/get], value: Y, note: the initial End user gets any user}
 * critical: [it-user/items/modify/assignment]
 * </pre>
 */
final class AuthzMatrixSpec {

    static final String VERSION = "authz-matrix/1";

    private static final List<String> AUTO = List.of("pages", "search", "get", "read", "add", "modify", "delete",
            "assign", "unassign", "items", "views", "features", "menu-links");

    /** The auto rows of the GUI's configuration, which need no object. */
    private static final List<String> GUI_AUTO = List.of("pages", "views", "features", "menu-links");

    /** The kinds of object an assignment may target (an abstract role), for {@code auto: [assign]}. */
    private static final List<String> ASSIGNABLE = List.of("RoleType", "OrgType", "ServiceType", "ArchetypeType");

    /** A persona's membership of an org: with a relation ({@code manager}), or the default (null). */
    static final class Membership {
        final String org;
        final String relation;

        Membership(String org, String relation) {
            this.org = org;
            this.relation = relation;
        }

        @Override
        public String toString() {
            return relation == null ? org : org + " (" + relation + ")";
        }
    }

    /** What an {@code auto: [assign, unassign]} row assigns: a sample object, with a relation or the default. */
    static final class AssignmentTarget {
        final String object;
        final String relation;
        final int line;

        AssignmentTarget(String object, String relation, int line) {
            this.object = object;
            this.relation = relation;
            this.line = line;
        }

        /** The row's id: the object's, with the relation ({@code it-org-manager}). */
        String id() {
            return relation == null ? object : object + "-" + slug(relation);
        }
    }

    /** A column: a user with these roles, orgs and archetype the matrix creates, or an existing user. */
    static final class Persona {
        final String id;
        final String label;
        final String group;
        final List<String> roles;
        final List<Membership> orgs;
        final String archetype;
        final String user;
        final int line;

        Persona(String id, String label, String group, List<String> roles, List<Membership> orgs, String archetype,
                String user, int line) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.roles = roles;
            this.orgs = orgs;
            this.archetype = archetype;
            this.user = user;
            this.line = line;
        }
    }

    /** A sample object the checks are made on, an existing one by type and name ({@code self}: the persona's user). */
    static final class SampleObject {
        final String id;
        final String type;
        final String name;
        final String label;
        final int line;

        SampleObject(String id, String type, String name, String label, int line) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.label = label;
            this.line = line;
        }
    }

    /**
     * What a row checks: open a page by URL; search, get, read (both) the object; add, modify, delete it (midPoint's
     * {@code #add}, {@code #modify}, {@code #delete} on it, in both phases); assign a target to it or unassign it
     * (with a relation), as the clockwork authorizes it; read or modify an item in its form; and from the persona's
     * GUI profile, whether the side menu lists an object collection view, shows a feature, has an additional link.
     */
    enum Kind {
        OPEN, SEARCH, GET, READ, ADD, MODIFY, DELETE, ASSIGN, UNASSIGN, READ_ITEM, MODIFY_ITEM, VIEW, FEATURE, MENU_LINK
    }

    /** What the running midPoint gives the {@code auto} rows: the GUI's pages, the items of a type. */
    interface Expander {
        /** The GUI's pages as {@code {pattern, source}}. */
        List<String[]> pages();

        /** The top-level items of the type ({@code UserType}) in the schema's order. */
        List<String> items(String type);

        /**
         * The object collection views of the personas' GUI profiles, as {@code {type, identifier, label}} ({@code
         * UserType}; the label may be null), each once.
         */
        default List<String[]> views() {
            return List.of();
        }

        /** The identifiers of the features the personas' GUI profiles configure, each once. */
        default List<String> features() {
            return List.of();
        }

        /** The labels of the additional menu links of the personas' GUI profiles, each once. */
        default List<String> menuLinks() {
            return List.of();
        }
    }

    /** A node of the rows' tree: a group, or a check (a row). */
    static final class Row {
        final String id;
        final String path;
        final String label;
        final List<Row> children = new ArrayList<>();
        final Kind kind;
        final String argument;
        final String object;
        final String check;
        final int line;
        /** An assign or unassign row's relation ({@code manager}); null: the default. */
        String relation;
        /** A group's {@code auto}: {@code pages}, or the actions and {@code items}; null when none. */
        List<String> auto;

        Row(String id, String path, String label, Kind kind, String argument, String object, String check, int line) {
            this.id = id;
            this.path = path;
            this.label = label;
            this.kind = kind;
            this.argument = argument;
            this.object = object;
            this.check = check;
            this.line = line;
        }

        boolean isGroup() {
            return kind == null;
        }
    }

    /** A rule of the expected values: for these personas, on these rows, this value (later rules win). */
    static final class Rule {
        final String name;
        final Set<String> personas;
        final List<String> rows;
        final String value;
        final String note;
        final int line;

        Rule(String name, Set<String> personas, List<String> rows, String value, String note, int line) {
            this.name = name;
            this.personas = personas;
            this.rows = rows;
            this.value = value;
            this.note = note;
            this.line = line;
        }
    }

    final String file;
    final Map<String, Persona> personas = new LinkedHashMap<>();
    final Map<String, SampleObject> objects = new LinkedHashMap<>();
    /** What {@code auto: [assign, unassign]} assigns; empty: every role, org, service, archetype of the objects. */
    final List<AssignmentTarget> assignmentTargets = new ArrayList<>();
    final List<Row> rows = new ArrayList<>();
    final String defaultValue;
    final List<Rule> rules = new ArrayList<>();
    final List<String> critical = new ArrayList<>();
    /** The rule patterns that select no row: an error, unless the rows are partly automatic (lines differ). */
    final List<String> warnings = new ArrayList<>();

    private final String line;
    /** The row patterns to check once the rows are expanded, with where they are written. */
    private final List<Object[]> patterns = new ArrayList<>();

    private AuthzMatrixSpec(String file, MappingNode root, String line) {
        this.file = file;
        this.line = line;
        String version = scalar(required(root, "spec"));
        if (!VERSION.equals(version)) {
            throw error(required(root, "spec"), "spec must be " + VERSION + ", not " + version);
        }
        for (NodeTuple persona : mapping(required(root, "personas")).getValue()) {
            persona(persona);
        }
        Node objectsNode = optional(root, "objects");
        if (objectsNode != null) {
            for (NodeTuple object : mapping(objectsNode).getValue()) {
                object(object);
            }
        }
        Node targetsNode = optional(root, "assignment-targets");
        if (targetsNode != null) {
            for (Node target : sequence(targetsNode).getValue()) {
                assignmentTarget(target);
            }
        }
        for (Node row : sequence(required(root, "rows")).getValue()) {
            rows.add(row(row, "", null));
        }
        defaultValue = value(required(root, "default"));
        Node rulesNode = optional(root, "rules");
        if (rulesNode != null) {
            for (Node rule : sequence(rulesNode).getValue()) {
                rule(mapping(rule));
            }
        }
        Node criticalNode = optional(root, "critical");
        if (criticalNode != null) {
            critical.addAll(rowPatterns(criticalNode));
        }
        allowed(root, "spec", "personas", "objects", "assignment-targets", "rows", "default", "rules", "critical");
    }

    /**
     * Reads the file for the line ({@code 4.8}): a per-line value ({@code {default: N, "4.8": Y}}) resolves to it.
     * The {@code auto} rows are added by {@link #expand}.
     */
    static AuthzMatrixSpec read(Path file, String line) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Node root = new Yaml().compose(reader);
            String name = file.getFileName().toString();
            if (!(root instanceof MappingNode)) {
                throw new IllegalArgumentException(name + ": not a mapping");
            }
            return new AuthzMatrixSpec(name, (MappingNode) root, line);
        } catch (org.yaml.snakeyaml.error.MarkedYAMLException e) {
            int at = (e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark()).getLine() + 1;
            throw new IllegalArgumentException(file.getFileName() + ":" + at + ": not YAML: " + e.getProblem(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Adds the {@code auto} rows of the groups from the running midPoint, then checks the rows the rules and the
     * critical list select: a pattern that selects no row is an error, or a warning when the rows are partly
     * automatic (another line has other pages).
     */
    AuthzMatrixSpec expand(Expander expander) {
        rows.forEach(row -> expand(row, expander));
        List<String> paths = new ArrayList<>();
        rows.forEach(row -> paths(row, paths));
        boolean automatic = rows.stream().anyMatch(AuthzMatrixSpec::automatic);
        for (Object[] pattern : patterns) {
            String text = (String) pattern[0];
            if (paths.stream().noneMatch(path -> matches(List.of(text), path))) {
                IllegalArgumentException error = error((Node) pattern[1], "no row matches " + text);
                if (!automatic) {
                    throw new IllegalArgumentException(error.getMessage() + "; rows: " + paths);
                }
                warnings.add(error.getMessage());
            }
        }
        return this;
    }

    /** The id, or with -2, -3... when the group has a row of that id already. */
    private static String unique(Row group, String id) {
        String out = id;
        for (int n = 2; ; n++) {
            String candidate = out;
            if (group.children.stream().noneMatch(child -> child.id.equals(candidate))) {
                return candidate;
            }
            out = id + "-" + n;
        }
    }

    private static boolean automatic(Row row) {
        return row.auto != null || row.children.stream().anyMatch(AuthzMatrixSpec::automatic);
    }

    private void expand(Row group, Expander expander) {
        if (!group.isGroup()) {
            return;
        }
        if (group.auto != null) {
            List<Row> added = new ArrayList<>();
            if (group.auto.contains("pages")) {
                Map<String, Row> sections = new LinkedHashMap<>();
                for (String[] page : expander.pages()) {
                    String[] segments = page[0].substring(1).split("/", 2);
                    String section = slug(segments[0]);
                    Row parent = sections.computeIfAbsent(section, id -> new Row(id, group.path + "/" + id,
                            "/" + segments[0], null, null, null, null, group.line));
                    String id = segments.length > 1
                            ? slug(segments[1].replace("**", "any").replace("*", "any").replace("?", "any")) : "index";
                    // a URL the pattern matches: an OID for a trailing /**, x for any other wildcard
                    String url = page[0].replaceAll("/\\*\\*$", "/00000000-0000-0000-0000-000000000000")
                            .replace("**", "x").replace("*", "x").replace("?", "x");
                    // a page class by its simple name, a URL mapping as Mapping.CONSTANT
                    String source = page[1].contains("Mapping.") ? page[1]
                            : page[1].substring(page[1].lastIndexOf('.') + 1);
                    id = unique(parent, id);
                    parent.children.add(new Row(id, parent.path + "/" + id, page[0] + " (" + source + ")", Kind.OPEN,
                            url, null, "open " + url, group.line));
                }
                added.addAll(sections.values());
            }
            for (String action : group.auto) {
                Kind kind = action.equals("search") ? Kind.SEARCH : action.equals("get") ? Kind.GET
                        : action.equals("read") ? Kind.READ : action.equals("add") ? Kind.ADD
                        : action.equals("modify") ? Kind.MODIFY : action.equals("delete") ? Kind.DELETE : null;
                if (kind != null) {
                    added.add(new Row(action, group.path + "/" + action, action, kind, null, group.object, action,
                            group.line));
                }
                if (action.equals("assign") || action.equals("unassign")) {
                    Row sub = new Row(action, group.path + "/" + action, action, null, null, group.object, null,
                            group.line);
                    for (AssignmentTarget target : assignmentTargets()) {
                        String check = action + " " + target.object
                                + (target.relation == null ? "" : " as " + target.relation);
                        Row row = new Row(target.id(), sub.path + "/" + target.id(),
                                objects.get(target.object).label + (target.relation == null ? ""
                                        : " as " + target.relation),
                                action.equals("assign") ? Kind.ASSIGN : Kind.UNASSIGN, target.object, group.object,
                                check, group.line);
                        row.relation = target.relation;
                        sub.children.add(row);
                    }
                    added.add(sub);
                }
            }
            if (group.auto.contains("items")) {
                String type = group.object.equals("self") ? "UserType" : objects.get(group.object).type;
                Row items = new Row("items", group.path + "/items", "Items", null, null, group.object, null,
                        group.line);
                for (String action : List.of("read", "modify")) {
                    Row sub = new Row(action, items.path + "/" + action, action + " (form)", null, null, group.object,
                            null, group.line);
                    for (String item : expander.items(type)) {
                        sub.children.add(new Row(item, sub.path + "/" + item, item,
                                action.equals("read") ? Kind.READ_ITEM : Kind.MODIFY_ITEM, item, group.object,
                                action + " " + item, group.line));
                    }
                    items.children.add(sub);
                }
                added.add(items);
            }
            if (group.auto.contains("views")) {
                Row views = new Row("views", group.path + "/views", "Views in the menu", null, null, null, null,
                        group.line);
                Map<String, Row> types = new LinkedHashMap<>();
                for (String[] view : expander.views()) {
                    Row type = types.computeIfAbsent(view[0], t -> new Row(t, views.path + "/" + t, t, null, null,
                            null, null, group.line));
                    String id = unique(type, slug(view[1]));
                    type.children.add(new Row(id, type.path + "/" + id, view[1] + (view[2] == null ? ""
                            : " (" + view[2] + ")"), Kind.VIEW, view[0] + "/" + view[1], null,
                            "view " + view[0] + "/" + view[1], group.line));
                }
                views.children.addAll(types.values());
                added.add(views);
            }
            if (group.auto.contains("features")) {
                Row features = new Row("features", group.path + "/features", "Features", null, null, null, null,
                        group.line);
                for (String feature : expander.features()) {
                    String id = unique(features, slug(feature));
                    features.children.add(new Row(id, features.path + "/" + id, feature, Kind.FEATURE, feature, null,
                            "feature " + feature, group.line));
                }
                added.add(features);
            }
            if (group.auto.contains("menu-links")) {
                Row links = new Row("menu-links", group.path + "/menu-links", "Additional menu links", null, null,
                        null, null, group.line);
                for (String link : expander.menuLinks()) {
                    String id = unique(links, slug(link));
                    links.children.add(new Row(id, links.path + "/" + id, link, Kind.MENU_LINK, link, null,
                            "menu-link " + link, group.line));
                }
                added.add(links);
            }
            group.children.addAll(0, added);
        }
        group.children.forEach(child -> expand(child, expander));
    }

    /** The targets of the automatic assign rows: as written, or every role, org, service, archetype object. */
    private List<AssignmentTarget> assignmentTargets() {
        if (!assignmentTargets.isEmpty()) {
            return assignmentTargets;
        }
        List<AssignmentTarget> out = new ArrayList<>();
        for (SampleObject object : objects.values()) {
            if (ASSIGNABLE.contains(object.type)) {
                out.add(new AssignmentTarget(object.id, null, object.line));
            }
        }
        return out;
    }

    /** The leaves of the rows' tree, in order. */
    List<Row> checks() {
        List<Row> out = new ArrayList<>();
        rows.forEach(row -> leaves(row, out));
        return out;
    }

    /** The value a persona should get on a row, with the rule that gave it (null: the default). */
    Object[] expected(String persona, Row row) {
        String value = defaultValue;
        Rule winner = null;
        for (Rule rule : rules) {
            if (selects(rule.personas, persona) && matches(rule.rows, row.path)) {
                value = rule.value;
                winner = rule;
            }
        }
        return new Object[] {value, winner};
    }

    boolean critical(Row row) {
        return matches(critical, row.path);
    }

    /**
     * Whether a pattern selects the path: segments separated by {@code /}, {@code *} one segment, {@code **} any; a
     * pattern that selects a group selects its rows ({@code pages} selects {@code pages/users}).
     */
    static boolean matches(List<String> patterns, String path) {
        String[] segments = path.split("/");
        for (String pattern : patterns) {
            String[] parts = pattern.split("/");
            for (int prefix = 1; prefix <= segments.length; prefix++) {
                if (glob(parts, 0, segments, 0, prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean glob(String[] parts, int p, String[] segments, int s, int end) {
        if (p == parts.length) {
            return s == end;
        }
        if (parts[p].equals("**")) {
            for (int next = s; next <= end; next++) {
                if (glob(parts, p + 1, segments, next, end)) {
                    return true;
                }
            }
            return false;
        }
        if (s == end) {
            return false;
        }
        String part = parts[p];
        boolean match = part.equals("*") || part.equals(segments[s])
                || (part.contains("*") && segments[s].matches(part.replace(".", "\\.").replace("*", ".*")));
        return match && glob(parts, p + 1, segments, s + 1, end);
    }

    private static boolean selects(Set<String> personas, String persona) {
        return personas.contains("*") || personas.contains(persona);
    }

    // ---- reading ---------------------------------------------------------------------------------------------

    private void persona(NodeTuple tuple) {
        String id = id(tuple.getKeyNode());
        MappingNode map = mapping(tuple.getValueNode());
        allowed(map, "label", "group", "roles", "orgs", "archetype", "user");
        String user = optionalScalar(map, "user");
        Persona persona = new Persona(id, labelOr(map, id), optionalScalar(map, "group"), strings(map, "roles"),
                memberships(map), optionalScalar(map, "archetype"), user, line(tuple.getKeyNode()));
        if (user != null && (!persona.roles.isEmpty() || !persona.orgs.isEmpty() || persona.archetype != null)) {
            throw error(map, "persona " + id + ": an existing user (user) has its own roles, orgs and archetype");
        }
        personas.put(id, persona);
    }

    /** {@code orgs: [IT, {org: IT, relation: manager}]}. */
    private List<Membership> memberships(MappingNode map) {
        List<Membership> out = new ArrayList<>();
        Node node = optional(map, "orgs");
        if (node == null) {
            return out;
        }
        for (Node item : node instanceof ScalarNode ? List.of(node) : sequence(node).getValue()) {
            if (item instanceof MappingNode) {
                MappingNode org = (MappingNode) item;
                allowed(org, "org", "relation");
                out.add(new Membership(scalar(required(org, "org")), optionalScalar(org, "relation")));
            } else {
                out.add(new Membership(scalar(item), null));
            }
        }
        return out;
    }

    /** {@code assignment-targets: [requestable-role, {object: it-org, relation: manager}]}. */
    private void assignmentTarget(Node node) {
        String object;
        String relation = null;
        if (node instanceof MappingNode) {
            MappingNode map = (MappingNode) node;
            allowed(map, "object", "relation");
            object = scalar(required(map, "object"));
            relation = optionalScalar(map, "relation");
        } else {
            object = scalar(node);
        }
        if (!objects.containsKey(object)) {
            throw error(node, "unknown object " + object + "; objects: " + objects.keySet());
        }
        AssignmentTarget target = new AssignmentTarget(object, relation, line(node));
        if (assignmentTargets.stream().anyMatch(t -> t.id().equals(target.id()))) {
            throw error(node, "assignment target " + target.id() + " is listed twice");
        }
        assignmentTargets.add(target);
    }

    private void object(NodeTuple tuple) {
        String id = id(tuple.getKeyNode());
        if (id.equals("self")) {
            throw error(tuple.getKeyNode(), "object self is the persona's own user; it needs no entry");
        }
        MappingNode map = mapping(tuple.getValueNode());
        String kind = null;
        String name = null;
        for (NodeTuple entry : map.getValue()) {
            String key = scalar(entry.getKeyNode());
            if (key.equals("label")) {
                continue;
            }
            if (type(key) == null || kind != null) {
                throw error(entry.getKeyNode(), "object " + id + ": one of user, role, org, service, archetype with"
                        + " the object's name, and a label");
            }
            kind = key;
            name = scalar(entry.getValueNode());
        }
        if (kind == null) {
            throw error(map, "object " + id + ": one of user, role, org, service, archetype with the object's name");
        }
        objects.put(id, new SampleObject(id, type(kind), name, labelOr(map, kind + " " + name),
                line(tuple.getKeyNode())));
    }

    private Row row(Node node, String parent, String inheritedObject) {
        MappingNode map = mapping(node);
        String object = optionalScalar(map, "object");
        if (object != null && !object.equals("self") && !objects.containsKey(object)) {
            throw error(required(map, "object"), "unknown object " + object + "; objects: " + objects.keySet());
        }
        String effectiveObject = object != null ? object : inheritedObject;
        Node group = optional(map, "group");
        if (group != null) {
            allowed(map, "group", "label", "object", "rows", "auto");
            String id = id(group);
            String path = parent.isEmpty() ? id : parent + "/" + id;
            Row row = new Row(id, path, labelOr(map, id), null, null, effectiveObject, null, line(node));
            Node auto = optional(map, "auto");
            if (auto != null) {
                row.auto = strings(map, "auto");
                for (String action : row.auto) {
                    if (!AUTO.contains(action)) {
                        throw error(auto, "unknown auto " + action + " (pages, views, features, menu-links, or search,"
                                + " get, read, add, modify, delete, assign, unassign, items)");
                    }
                    if (!GUI_AUTO.contains(action) && effectiveObject == null) {
                        throw error(auto, "auto " + action + " needs an object (object: on the group or above it)");
                    }
                }
            }
            Node children = auto == null ? required(map, "rows") : optional(map, "rows");
            if (children != null) {
                for (Node child : sequence(children).getValue()) {
                    row.children.add(row(child, path, effectiveObject));
                }
            }
            return row;
        }
        allowed(map, "id", "label", "object", "check");
        String check = scalar(required(map, "check")).trim();
        String[] words = check.split("\\s+", 2);
        Kind kind;
        String argument = words.length > 1 ? words[1].trim() : null;
        switch (words[0]) {
            case "open":
                kind = Kind.OPEN;
                if (argument == null || !argument.startsWith("/")) {
                    throw error(map, "open needs a URL after the GUI's context: open /admin/users");
                }
                break;
            case "search":
                kind = Kind.SEARCH;
                break;
            case "get":
                kind = Kind.GET;
                break;
            case "read":
                kind = argument == null ? Kind.READ : Kind.READ_ITEM;
                break;
            case "add":
                kind = Kind.ADD;
                break;
            case "modify":
                kind = argument == null ? Kind.MODIFY : Kind.MODIFY_ITEM;
                break;
            case "delete":
                kind = Kind.DELETE;
                break;
            case "assign":
                kind = Kind.ASSIGN;
                break;
            case "unassign":
                kind = Kind.UNASSIGN;
                break;
            case "view":
                kind = Kind.VIEW;
                if (argument == null || !argument.matches("[A-Za-z]+Type/\\S+")) {
                    throw error(map, "view needs a type and a view's identifier: view UserType/people");
                }
                break;
            case "feature":
                kind = Kind.FEATURE;
                if (argument == null) {
                    throw error(map, "feature needs a feature's identifier: feature orgTreeExpandAll");
                }
                break;
            case "menu-link":
                kind = Kind.MENU_LINK;
                if (argument == null) {
                    throw error(map, "menu-link needs a link's label: menu-link Service desk");
                }
                break;
            default:
                throw error(map, "unknown check " + words[0] + " (open <url>, search, get, read, add, modify, delete,"
                        + " assign <object> [as <relation>], unassign <object> [as <relation>], read <item>,"
                        + " modify <item>, view <type>/<identifier>, feature <identifier>, menu-link <label>)");
        }
        boolean gui = kind == Kind.OPEN || kind == Kind.VIEW || kind == Kind.FEATURE || kind == Kind.MENU_LINK;
        String relation = null;
        if (kind == Kind.ASSIGN || kind == Kind.UNASSIGN) {
            String[] target = argument == null ? new String[0] : argument.split("\\s+as\\s+", 2);
            if (target.length == 0 || !objects.containsKey(target[0])) {
                throw error(map, words[0] + " needs an object to assign (" + words[0] + " <object> [as <relation>]);"
                        + " objects: " + objects.keySet());
            }
            argument = target[0];
            relation = target.length > 1 ? target[1].trim() : null;
        } else if (!gui && argument != null && kind != Kind.READ_ITEM && kind != Kind.MODIFY_ITEM) {
            throw error(map, words[0] + " takes no argument");
        }
        if (!gui && effectiveObject == null) {
            throw error(map, check + " needs an object (object: on the row or a group above it)");
        }
        String id = optionalScalar(map, "id");
        if (id == null) {
            id = slug(check);
        }
        String path = parent.isEmpty() ? id : parent + "/" + id;
        Row row = new Row(id, path, labelOr(map, check), kind, argument, gui ? null : effectiveObject, check,
                line(node));
        row.relation = relation;
        return row;
    }

    private void rule(MappingNode map) {
        allowed(map, "name", "for", "rows", "value", "note");
        String name = optionalScalar(map, "name");
        rules.add(new Rule(name != null ? name : "rule at line " + line(map), personaSet(required(map, "for")),
                rowPatterns(required(map, "rows")), value(required(map, "value")), optionalScalar(map, "note"),
                line(map)));
    }

    private Set<String> personaSet(Node node) {
        Set<String> out = new LinkedHashSet<>();
        if (node instanceof ScalarNode) {
            out.add(scalar(node));
        } else {
            for (Node item : sequence(node).getValue()) {
                out.add(scalar(item));
            }
        }
        for (String id : out) {
            if (!id.equals("*") && !personas.containsKey(id)) {
                throw error(node, "unknown persona " + id + "; personas: " + personas.keySet());
            }
        }
        return out;
    }

    private List<String> rowPatterns(Node node) {
        List<String> out = new ArrayList<>();
        if (node instanceof ScalarNode) {
            out.add(scalar(node));
        } else {
            for (Node item : sequence(node).getValue()) {
                out.add(scalar(item));
            }
        }
        // checked once the auto rows are added (expand)
        out.forEach(pattern -> patterns.add(new Object[] {pattern, node}));
        return out;
    }

    /** {@code Y} or {@code N}, or per line: {@code {default: N, "4.8": Y}}. */
    private String value(Node node) {
        if (node instanceof MappingNode) {
            String out = null;
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                String key = scalar(tuple.getKeyNode());
                if (key.equals("default") && out == null || key.equals(line)) {
                    out = yesNo(tuple.getValueNode());
                } else if (!key.equals("default") && !key.matches("\\d+\\.\\d+")) {
                    throw error(tuple.getKeyNode(), "a per-line value has default and lines (\"4.8\"), not " + key);
                } else {
                    yesNo(tuple.getValueNode());
                }
            }
            if (out == null) {
                throw error(node, "no value for " + line + " and no default");
            }
            return out;
        }
        return yesNo(node);
    }

    private String yesNo(Node node) {
        String value = scalar(node);
        if (!value.equals("Y") && !value.equals("N")) {
            throw error(node, "a value is Y or N, not " + value);
        }
        return value;
    }

    private static void leaves(Row row, List<Row> out) {
        if (row.isGroup()) {
            row.children.forEach(child -> leaves(child, out));
        } else {
            out.add(row);
        }
    }

    private static void paths(Row row, List<String> out) {
        out.add(row.path);
        row.children.forEach(child -> paths(child, out));
    }

    private static String type(String kind) {
        switch (kind) {
            case "user":
                return "UserType";
            case "role":
                return "RoleType";
            case "org":
                return "OrgType";
            case "service":
                return "ServiceType";
            case "archetype":
                return "ArchetypeType";
            default:
                return null;
        }
    }

    private static String slug(String check) {
        // the case kept: a URL's or an item's own spelling
        return check.replaceAll("[^A-Za-z0-9{}]+", "-").replaceAll("^-|-$", "");
    }

    private String id(Node node) {
        String id = scalar(node);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw error(node, "an id is letters, digits, '.', '_' and '-', not " + id);
        }
        return id;
    }

    private String labelOr(MappingNode map, String fallback) {
        String label = optionalScalar(map, "label");
        return label != null ? label : fallback;
    }

    private List<String> strings(MappingNode map, String key) {
        Node node = optional(map, key);
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (node instanceof ScalarNode) {
            out.add(scalar(node));
            return out;
        }
        for (Node item : sequence(node).getValue()) {
            out.add(scalar(item));
        }
        return out;
    }

    private Node required(MappingNode map, String key) {
        Node node = optional(map, key);
        if (node == null) {
            throw error(map, "missing " + key);
        }
        return node;
    }

    private static Node optional(MappingNode map, String key) {
        for (NodeTuple tuple : map.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode && key.equals(((ScalarNode) tuple.getKeyNode()).getValue())) {
                return tuple.getValueNode();
            }
        }
        return null;
    }

    private String optionalScalar(MappingNode map, String key) {
        Node node = optional(map, key);
        return node == null ? null : scalar(node);
    }

    private void allowed(MappingNode map, String... keys) {
        List<String> allowed = List.of(keys);
        for (NodeTuple tuple : map.getValue()) {
            String key = scalar(tuple.getKeyNode());
            if (!allowed.contains(key)) {
                throw error(tuple.getKeyNode(), "unknown key " + key + " (" + String.join(", ", allowed) + ")");
            }
        }
    }

    /** The text as written: YAML's own typing (yes, on, 1.0) is not applied. */
    private String scalar(Node node) {
        if (!(node instanceof ScalarNode)) {
            throw error(node, "expected a single value");
        }
        return ((ScalarNode) node).getValue();
    }

    private MappingNode mapping(Node node) {
        if (!(node instanceof MappingNode)) {
            throw error(node, "expected a mapping (key: value)");
        }
        return (MappingNode) node;
    }

    private SequenceNode sequence(Node node) {
        if (!(node instanceof SequenceNode)) {
            throw error(node, "expected a list");
        }
        return (SequenceNode) node;
    }

    private static int line(Node node) {
        return node.getStartMark().getLine() + 1;
    }

    IllegalArgumentException error(Node node, String message) {
        return new IllegalArgumentException(file + ":" + line(node) + ": " + message);
    }

    /** An error at a line of the file, for the checks against the deployment. */
    IllegalArgumentException error(int line, String message) {
        return new IllegalArgumentException(file + ":" + line + ": " + message);
    }
}
