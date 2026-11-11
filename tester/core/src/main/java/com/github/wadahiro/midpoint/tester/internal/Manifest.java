package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import com.github.wadahiro.midpoint.tester.ResourceOptions;

/**
 * The manifest of a deployment ({@code midpoint-config-tester.yaml}): which directories hold the post-initial objects,
 * what changes per line, and what the tester puts in place of the target systems, the mail servers and the file
 * system. Paths are relative to the manifest's directory. Unknown keys fail the load (a typo must not be ignored).
 *
 * <pre>
 * config:
 *   dirs: [post-initial-objects]          # layered: a later directory replaces the same relative path
 *   exclude: ["tasks/900-*.xml"]          # globs on the path relative to a config directory
 *   properties: { ldapBase: dc=example,dc=com }   # {{ldapBase}} in the files
 *   replace: { "@MAIL_HOST@": smtp.example.com }   # text replaced as is, as a deployment script's sed does
 *   overlays:
 *     "4.0": { dirs: [overlays/4.0], remove: [tasks/050-old.xml] }
 *   schema: schema                        # extension XSDs (midPoint's schema directory)
 * targets:                                # by resource name; any other resource is a mirror
 *   HR:   { connector: csv, initialData: initial-data/hr.csv }   # the target system's content at the start
 *   LDAP: { connector: ldap, initialData: initial-data/directory.ldif, referentialIntegrity: true }
 *   CRM:  { connector: mirror, namingAttribute: login, attributes: { AccountObjectClass: [category] } }
 *   SCIM: { namingAttribute: { User: userName, Group: displayName } }   # by object class
 *   AD:   { schema: schema/ad.xml }   # the stored <schema>, exported from a midPoint connected to the real one
 * mail: { redirect: true }
 * http: { redirect: ["https://hooks.example.com"] }   # URL prefixes the tester stands in for (HttpStandIn)
 * files: { map: [/opt/midpoint/var], initial: { /opt/midpoint/var: files/var } }   # initial: the directory's tree
 * names: { package: com.example.idm.names }   # typed names generated from the configuration (NamesGenerator)
 * </pre>
 */
public final class Manifest {

    /** The files that replace, add or remove files of the config directories on one line. */
    public static final class Overlay {
        public final List<Path> dirs;
        public final List<String> remove;

        Overlay(List<Path> dirs, List<String> remove) {
            this.dirs = dirs;
            this.remove = remove;
        }
    }

    /** What stands in for the system behind a resource. */
    public static final class Target {
        public final String resource;
        /** {@code mirror}, {@code csv} or {@code ldap}. */
        public final String connector;
        /** The file the target system starts with (a CSV file, an LDIF), or null. */
        public final Path initialData;
        public final ResourceOptions options;

        Target(String resource, String connector, Path initialData, ResourceOptions options) {
            this.resource = resource;
            this.connector = connector;
            this.initialData = initialData;
            this.options = options;
        }
    }

    public final Path file;
    public final Path dir;
    public final List<Path> configDirs;
    public final List<String> exclude;
    public final Map<String, String> properties;
    /** Texts of the files replaced as they are ({@code config.replace}), in the manifest's order. */
    public final Map<String, String> replace;
    public final Map<String, Overlay> overlays;
    public final Path schema;
    public final Map<String, Target> targets;
    public final boolean mailRedirect;
    /** URL prefixes of HTTP services the tester stands in for ({@code http.redirect}). */
    public final List<String> httpRedirect;
    /** midPoint's constants ({@code <constants>} of its config.xml: {@code <const>}, {@code midpoint.getConst}). */
    public final Map<String, String> constants;
    /** Stand-in databases ({@code databases.<name>.init}: SQL files, relative to the manifest). */
    public final Map<String, List<Path>> databases;
    /** Environment variables of the scripts ({@code environment}; {@code ${database.<name>.url}} and {@code .user}). */
    public final Map<String, String> environment;
    /** Loggers captured besides the scripts' ({@code logs.capture}: a custom library's package, for {@code s.logs()}). */
    public final List<String> logCapture;
    /** Hosts of other machines the test JVM may connect to ({@code network.allow}; none by default). */
    public final List<String> networkAllow;
    public final List<String> mappedDirectories;
    /**
     * Mapped directory -> a directory of the repository whose tree it holds when the deployment is loaded (files, and
     * directories kept by a {@code .gitkeep}).
     */
    public final Map<String, Path> initialFiles;
    /** {@code names.package}: the package of the generated names ({@code NamesGenerator}); null: none generated. */
    public final String namesPackage;

    private Manifest(Path file, Map<String, Object> yaml) {
        this.file = file;
        this.dir = file.getParent();
        if (yaml.containsKey("tasks")) {
            throw invalid("tasks: removed, delete it (the recurring tasks are always held: they run only when a test"
                    + " runs them)");
        }
        check(yaml, "", Set.of("config", "targets", "constants", "databases", "environment", "mail", "http", "network", "logs", "files",
                "names"));
        Map<String, Object> config = map(yaml.get("config"), "config");
        check(config, "config.", Set.of("dirs", "exclude", "properties", "replace", "overlays", "schema"));
        configDirs = paths(config.get("dirs"), "config.dirs");
        if (configDirs.isEmpty()) {
            throw invalid("config.dirs: at least one directory of post-initial objects is needed");
        }
        for (Path d : configDirs) {
            if (!Files.isDirectory(d)) {
                throw invalid("config.dirs: " + d + " is not a directory");
            }
        }
        exclude = strings(config.get("exclude"), "config.exclude");
        Map<String, String> props = new LinkedHashMap<>();
        map(config.get("properties"), "config.properties").forEach((k, v) -> props.put(k, v == null ? "" : v.toString()));
        properties = Collections.unmodifiableMap(props);
        Map<String, String> texts = new LinkedHashMap<>();
        map(config.get("replace"), "config.replace").forEach((k, v) -> {
            if (k.isEmpty()) {
                throw invalid("config.replace: an empty text cannot be replaced");
            }
            texts.put(k, v == null ? "" : v.toString());
        });
        replace = Collections.unmodifiableMap(texts);
        Map<String, Overlay> ov = new LinkedHashMap<>();
        map(config.get("overlays"), "config.overlays").forEach((line, value) -> {
            String where = "config.overlays." + line;
            Map<String, Object> overlay = map(value, where);
            check(overlay, where + ".", Set.of("dirs", "remove"));
            List<Path> dirs = paths(overlay.get("dirs"), where + ".dirs");
            for (Path d : dirs) {
                if (!Files.isDirectory(d)) {
                    throw invalid(where + ".dirs: " + d + " is not a directory");
                }
            }
            ov.put(line, new Overlay(dirs, strings(overlay.get("remove"), where + ".remove")));
        });
        overlays = Collections.unmodifiableMap(ov);
        schema = config.get("schema") != null ? dir.resolve(config.get("schema").toString()).normalize() : null;

        Map<String, Target> t = new LinkedHashMap<>();
        map(yaml.get("targets"), "targets").forEach((resource, value) -> t.put(resource, target(resource, value)));
        targets = Collections.unmodifiableMap(t);

        Map<String, String> c = new LinkedHashMap<>();
        map(yaml.get("constants"), "constants").forEach((name, value) -> {
            if (value == null || value instanceof Map || value instanceof List) {
                throw invalid("constants." + name + ": a value");
            }
            c.put(name, value.toString());
        });
        constants = Collections.unmodifiableMap(c);

        Map<String, List<Path>> dbs = new LinkedHashMap<>();
        map(yaml.get("databases"), "databases").forEach((name, value) -> {
            Map<String, Object> db = map(value, "databases." + name);
            check(db, "databases." + name + ".", Set.of("init"));
            List<Path> init = new ArrayList<>();
            for (String script : strings(db.get("init"), "databases." + name + ".init")) {
                Path path = dir.resolve(script).normalize();
                if (!Files.isRegularFile(path)) {
                    throw invalid("databases." + name + ".init: no file " + path);
                }
                init.add(path);
            }
            dbs.put(name, init);
        });
        databases = Collections.unmodifiableMap(dbs);
        Map<String, String> env = new LinkedHashMap<>();
        map(yaml.get("environment"), "environment").forEach((name, value) -> {
            if (value == null || value instanceof Map || value instanceof List) {
                throw invalid("environment." + name + ": a value");
            }
            env.put(name, value.toString());
        });
        environment = Collections.unmodifiableMap(env);

        Map<String, Object> mail = map(yaml.get("mail"), "mail");
        check(mail, "mail.", Set.of("redirect"));
        mailRedirect = bool(mail.get("redirect"), "mail.redirect", true);
        Map<String, Object> http = map(yaml.get("http"), "http");
        check(http, "http.", Set.of("redirect"));
        httpRedirect = strings(http.get("redirect"), "http.redirect");
        for (String prefix : httpRedirect) {
            if (!prefix.matches("(?i)https?://[^/\\s]+.*")) {
                throw invalid("http.redirect: a URL prefix with a scheme and a host, not " + prefix);
            }
        }

        Map<String, Object> network = map(yaml.get("network"), "network");
        check(network, "network.", Set.of("allow"));
        networkAllow = strings(network.get("allow"), "network.allow");

        Map<String, Object> logs = map(yaml.get("logs"), "logs");
        check(logs, "logs.", Set.of("capture"));
        logCapture = strings(logs.get("capture"), "logs.capture");

        Map<String, Object> files = map(yaml.get("files"), "files");
        check(files, "files.", Set.of("map", "initial"));
        List<String> mapped = new ArrayList<>(strings(files.get("map"), "files.map"));
        Map<String, Path> initial = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(files.get("initial"), "files.initial").entrySet()) {
            Path tree = dir.resolve(String.valueOf(e.getValue())).normalize();
            if (!Files.isDirectory(tree)) {
                throw invalid("files.initial." + e.getKey() + ": no directory " + tree);
            }
            initial.put(e.getKey(), tree);
            if (!mapped.contains(e.getKey())) {
                mapped.add(e.getKey());
            }
        }
        mappedDirectories = List.copyOf(mapped);
        initialFiles = java.util.Collections.unmodifiableMap(initial);

        Map<String, Object> names = map(yaml.get("names"), "names");
        check(names, "names.", Set.of("package"));
        Object pkg = names.get("package");
        if (pkg != null && !pkg.toString().matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw invalid("names.package: a Java package name, not " + pkg);
        }
        namesPackage = pkg == null ? null : pkg.toString();
    }

    /** Reads and checks the manifest. */
    public static Manifest read(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("No manifest " + absolute);
        }
        Object yaml;
        try (Reader reader = Files.newBufferedReader(absolute, StandardCharsets.UTF_8)) {
            yaml = new Yaml().load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the manifest " + absolute, e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(absolute + ": not valid YAML: " + e.getMessage(), e);
        }
        try {
            return new Manifest(absolute, map(yaml, "the manifest"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(absolute + ": " + e.getMessage(), e);
        }
    }

    /** The overlay of the line (exact match, e.g. {@code "4.0"}), or null. */
    public Overlay overlay(String line) {
        return overlays.get(line);
    }

    /**
     * The files of the deployment on the line, by relative path ({@code /}-separated): the layered config dirs with
     * the excludes, then the line's overlay (null: no overlay, the files every line without one gets). Fails when the
     * overlay removes a file the config dirs do not have.
     */
    public Map<String, Path> sources(String line) {
        List<java.nio.file.PathMatcher> matchers = new ArrayList<>();
        for (String glob : exclude) {
            matchers.add(java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob));
        }
        Map<String, Path> sources = new java.util.TreeMap<>();
        for (Path d : configDirs) {
            for (Path f : walk(d)) {
                Path relative = d.relativize(f);
                if (matchers.stream().noneMatch(m -> m.matches(relative))) {
                    sources.put(slashed(relative), f);
                }
            }
        }
        Overlay overlay = line != null ? overlay(line) : null;
        if (overlay != null) {
            for (Path d : overlay.dirs) {
                for (Path f : walk(d)) {
                    sources.put(slashed(d.relativize(f)), f);
                }
            }
            for (String removed : overlay.remove) {
                if (sources.remove(removed) == null) {
                    throw invalid("config.overlays." + line + ".remove: no file " + removed
                            + " in the config directories");
                }
            }
        }
        return sources;
    }

    private static List<Path> walk(Path d) {
        try (java.util.stream.Stream<Path> files = Files.walk(d)) {
            return files.filter(Files::isRegularFile).sorted().collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot read " + d, e);
        }
    }

    private static String slashed(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    private Target target(String resource, Object value) {
        String where = "targets." + resource;
        Map<String, Object> spec = map(value, where);
        check(spec, where + ".", Set.of("connector", "initialData", "schema", "namingAttribute", "identifier", "referentialIntegrity",
                "caseInsensitiveNames", "computed", "attributes", "multiValued", "keyedValues", "store"));
        String connector = spec.get("connector") != null ? spec.get("connector").toString() : "mirror";
        if (!Set.of("mirror", "csv", "ldap").contains(connector)) {
            throw invalid(where + ".connector: mirror, csv or ldap, not " + connector);
        }
        ResourceOptions options = ResourceOptions.defaults();
        if (!"mirror".equals(connector)) {
            options = options.realConnector();
        }
        if (spec.get("schema") != null) {
            // the stored schema of the real resource, exported from a midPoint connected to it
            Path schema = dir.resolve(spec.get("schema").toString()).normalize();
            if (!Files.isRegularFile(schema)) {
                throw invalid(where + ".schema: no file " + schema);
            }
            options = options.schema(schema);
        }
        Object naming = spec.get("namingAttribute");
        if (naming instanceof Map) {
            // { AccountObjectClass: login, GroupObjectClass: displayName }: by object class
            for (Map.Entry<String, Object> e : map(naming, where + ".namingAttribute").entrySet()) {
                options = options.namingAttribute(e.getKey(), String.valueOf(e.getValue()));
            }
        } else if (naming != null) {
            options = options.namingAttribute(naming.toString());
        }
        Object identifier = spec.get("identifier");
        if (identifier instanceof Map) {
            Map<String, Object> id = map(identifier, where + ".identifier");
            check(id, where + ".identifier.", Set.of("attribute", "value"));
            if (id.get("attribute") == null || id.get("value") == null) {
                throw new IllegalArgumentException(where + ".identifier: attribute and value (a Groovy expression)");
            }
            options = options.identifier(id.get("attribute").toString(), id.get("value").toString());
        } else if (identifier != null) {
            options = options.identifier(identifier.toString());
        }
        if (bool(spec.get("referentialIntegrity"), where + ".referentialIntegrity", false)) {
            options = options.referentialIntegrity();
        }
        if (bool(spec.get("caseInsensitiveNames"), where + ".caseInsensitiveNames", false)) {
            options = options.caseInsensitiveNames();
        }
        if (spec.get("attributes") != null) {
            // { user: [category, ...] }: attributes of the mirror's classes that schemaHandling does not name
            for (Map.Entry<String, Object> e : map(spec.get("attributes"), where + ".attributes").entrySet()) {
                for (Object attribute : list(e.getValue(), where + ".attributes." + e.getKey())) {
                    options = options.attribute(e.getKey(), attribute.toString());
                }
            }
        }
        if (spec.get("multiValued") != null) {
            // { Connection: [parameters, ...] }: attributes multi-valued on the real resource
            for (Map.Entry<String, Object> e : map(spec.get("multiValued"), where + ".multiValued").entrySet()) {
                for (Object attribute : list(e.getValue(), where + ".multiValued." + e.getKey())) {
                    options = options.multiValued(e.getKey(), attribute.toString());
                }
            }
        }
        if (spec.get("keyedValues") != null) {
            // { Connection: { parameters: "=" } }: key=value values the target system keeps one per key
            for (Map.Entry<String, Object> e : map(spec.get("keyedValues"), where + ".keyedValues").entrySet()) {
                for (Map.Entry<String, Object> a : map(e.getValue(), where + ".keyedValues." + e.getKey()).entrySet()) {
                    options = options.keyedValues(e.getKey(), a.getKey(), String.valueOf(a.getValue()));
                }
            }
        }
        if (spec.get("store") != null) {
            // { Connection: { parameters: "<Groovy of values, add, remove, replace, name>" } }
            for (Map.Entry<String, Object> e : map(spec.get("store"), where + ".store").entrySet()) {
                for (Map.Entry<String, Object> a : map(e.getValue(), where + ".store." + e.getKey()).entrySet()) {
                    options = options.store(e.getKey(), a.getKey(), String.valueOf(a.getValue()));
                }
            }
        }
        for (Object c : list(spec.get("computed"), where + ".computed")) {
            Map<String, Object> computed = map(c, where + ".computed");
            check(computed, where + ".computed.", Set.of("attribute", "objectClass", "from"));
            if (computed.get("attribute") == null || computed.get("objectClass") == null
                    || computed.get("from") == null) {
                throw invalid(where + ".computed: attribute, objectClass and from are needed");
            }
            options = options.computed(computed.get("attribute").toString(), computed.get("objectClass").toString(),
                    computed.get("from").toString());
        }
        Path initialData = null;
        if (spec.get("initialData") != null) {
            initialData = dir.resolve(spec.get("initialData").toString()).normalize();
            if (!Files.isRegularFile(initialData)) {
                throw invalid(where + ".initialData: no file " + initialData);
            }
            String name = initialData.getFileName().toString();
            if ("csv".equals(connector) && !name.endsWith(".csv") || "ldap".equals(connector) && !name.endsWith(".ldif")
                    || "mirror".equals(connector)) {
                throw invalid(where + ".initialData: a .csv file for connector csv, an .ldif file for connector"
                        + " ldap (a mirror has no initial data yet)");
            }
        }
        return new Target(resource, connector, initialData, options);
    }

    private List<Path> paths(Object value, String where) {
        List<Path> out = new ArrayList<>();
        for (String s : strings(value, where)) {
            out.add(dir.resolve(s).normalize());
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> strings(Object value, String where) {
        List<String> out = new ArrayList<>();
        for (Object o : list(value, where)) {
            if (o == null) {
                throw invalid(where + ": an empty entry");
            }
            out.add(o.toString());
        }
        return Collections.unmodifiableList(out);
    }

    private static List<?> list(Object value, String where) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List) {
            return (List<?>) value;
        }
        if (value instanceof String) {
            return List.of(value);
        }
        throw invalid(where + ": a list, not " + value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String where) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            throw invalid(where + ": a mapping, not " + value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<Object, Object>) value).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static boolean bool(Object value, String where, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw invalid(where + ": true or false, not " + value);
    }

    private static void check(Map<String, Object> map, String prefix, Set<String> allowed) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("unknown key " + prefix + key + " (known: " + new java.util.TreeSet<>(allowed) + ")");
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
