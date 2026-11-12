package com.github.wadahiro.midpoint.configtester.names;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.wadahiro.midpoint.tester.internal.Manifest;
import com.github.wadahiro.midpoint.tester.internal.NamesModel;

/**
 * Generates the typed names of deployments: for every manifest ({@code midpoint-config-tester.yaml}) with
 * {@code names.package}, Java classes of constants in that package, from its configuration ({@link NamesModel}):
 * {@code Resources}, {@code Roles}, {@code Orgs}, {@code Services}, {@code Archetypes}, {@code Users}, {@code Tasks},
 * {@code Reports}, {@code CertificationDefinitions}, the attributes of each resource ({@code <Resource>Attrs}, e.g.
 * {@code LdapAttrs}), the user extension items ({@code UserExtension}) and {@code Names} (all of them, which the load
 * checks against the loaded deployment).
 *
 * <p>The names are those of every line together: a name that only some lines have (an overlay adds, replaces or
 * removes its file) carries its {@link Lines}, and a scenario that uses it on another line fails with a clear message.
 * So shared test sources compile on every line.</p>
 *
 * <p>Run at build time (exec-maven-plugin, see the README):
 * {@code NamesGenerator <output dir> <manifest file or directory to search>...}. Only files whose content changes are
 * written; Java files of the output directory that are not generated any more are deleted. A name that two objects
 * would both get (e.g. {@code Dept-Sales} and {@code Dept Sales}) fails the generation.</p>
 */
public final class NamesGenerator {

    public static final String MANIFEST_NAME = "midpoint-config-tester.yaml";

    private static final List<String> OBJECT_GROUPS = List.of("Resources", "Roles", "Orgs", "Services", "Archetypes",
            "Users", "Tasks", "Reports", "CertificationDefinitions");

    private NamesGenerator() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: NamesGenerator <output dir> <manifest or directory>...");
        }
        List<Path> inputs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            inputs.add(Path.of(args[i]));
            if (!Files.exists(inputs.get(i - 1))) {
                // e.g. the build of a parent POM, which has no manifest of its own
                System.out.println("midpoint-config-tester: no manifest or directory " + args[i] + ", no names from it");
            }
        }
        List<String> written = generate(Path.of(args[0]), inputs);
        System.out.println("midpoint-config-tester: typed names in " + args[0] + " ("
                + (written.isEmpty() ? "unchanged" : written.size() + " file(s) written") + ")");
    }

    /** Generates the names of every manifest found; returns the files written (changed). */
    public static List<String> generate(Path output, List<Path> inputs) {
        Path outputDir = output.toAbsolutePath().normalize();
        Map<Path, String> files = new TreeMap<>();
        Map<String, Path> packages = new TreeMap<>();
        for (Path manifest : manifests(inputs)) {
            Manifest m = Manifest.read(manifest);
            if (m.namesPackage == null) {
                continue;
            }
            Path other = packages.put(m.namesPackage, m.file);
            if (other != null) {
                throw new IllegalArgumentException("names.package " + m.namesPackage + " of " + m.file
                        + " is also the package of " + other);
            }
            files.putAll(sources(NamesModel.read(m.file), outputDir));
        }
        return write(outputDir, files);
    }

    /** The manifests: the files given, and every {@value #MANIFEST_NAME} under the directories given. */
    static List<Path> manifests(List<Path> inputs) {
        Set<Path> out = new TreeSet<>();
        for (Path input : inputs) {
            if (Files.isRegularFile(input)) {
                out.add(input.toAbsolutePath().normalize());
            } else if (Files.isDirectory(input)) {
                try (Stream<Path> walk = Files.walk(input)) {
                    walk.filter(p -> p.getFileName().toString().equals(MANIFEST_NAME))
                            .forEach(p -> out.add(p.toAbsolutePath().normalize()));
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + input, e);
                }
            }
        }
        return new ArrayList<>(out);
    }

    // ---- sources ------------------------------------------------------------------------------------------------

    /** The Java sources of one deployment's names, by file. */
    static Map<Path, String> sources(NamesModel model, Path outputDir) {
        String pkg = model.manifest.namesPackage;
        Path dir = outputDir.resolve(pkg.replace('.', '/'));
        Map<Path, String> files = new LinkedHashMap<>();
        Map<String, List<String>> all = new LinkedHashMap<>();
        Set<String> classNames = new TreeSet<>();
        for (String group : OBJECT_GROUPS) {
            List<NamesModel.Name> names = model.group(group);
            if (!names.isEmpty()) {
                classNames.add(group);
                files.put(dir.resolve(group + ".java"), constants(model, pkg, group, names, describe(group), all));
            }
        }
        for (NamesModel.Name resource : model.group("Resources")) {
            List<NamesModel.Name> attributes = model.group("attributes:" + resource.name);
            if (attributes.isEmpty()) {
                continue;
            }
            String className = className(resource.name) + "Attrs";
            if (!classNames.add(className)) {
                throw new IllegalArgumentException(model.manifest.file + ": the attributes of resource "
                        + resource.name + " would be class " + className + ", which another resource or group has");
            }
            files.put(dir.resolve(className + ".java"), constants(model, pkg, className, attributes,
                    "The attributes of resource " + javadoc(resource.name) + ": its schemaHandling, stored"
                            + " schema, connector configuration (CSV columns) and initial data.", all));
        }
        List<NamesModel.Name> extension = model.group("UserExtension");
        if (!extension.isEmpty()) {
            files.put(dir.resolve("UserExtension.java"), constants(model, pkg, "UserExtension", extension,
                    "The items of the extension schema ({@code config.schema}) that extend users.", all));
        }
        files.put(dir.resolve("Names.java"), index(model, pkg, all));
        return files;
    }

    private static String describe(String group) {
        switch (group) {
            case "Resources":
                return "The resources of the deployment: {@code s.source(Resources.HR)}, {@code s.target(...)}.";
            case "Users":
                return "The users the configuration holds.";
            case "CertificationDefinitions":
                return "The certification definitions of the deployment: {@code s.certification(...)}.";
            default:
                return "The " + group.toLowerCase(Locale.ROOT) + " of the deployment.";
        }
    }

    private static String constants(NamesModel model, String pkg, String className, List<NamesModel.Name> names,
            String doc, Map<String, List<String>> all) {
        Map<String, NamesModel.Name> byConstant = new TreeMap<>();
        for (NamesModel.Name name : names) {
            String constant = constant(name.refType.equals("ItemRef")
                    ? name.name.substring(name.name.lastIndexOf('/') + 1) : name.name);
            NamesModel.Name other = byConstant.put(constant, name);
            if (other != null) {
                throw new IllegalArgumentException(model.manifest.file + ": " + className + "." + constant
                        + " would name both '" + other.name + "' and '" + name.name + "': rename one of them");
            }
        }
        StringBuilder sb = header(model, pkg);
        sb.append("/**\n * ").append(doc).append("\n */\n");
        sb.append("public final class ").append(className).append(" {\n\n");
        List<String> constants = new ArrayList<>();
        for (Map.Entry<String, NamesModel.Name> entry : byConstant.entrySet()) {
            NamesModel.Name name = entry.getValue();
            sb.append("    /** \"").append(javadoc(name.name)).append("\", in ")
                    .append(javadoc(String.join(", ", name.files))).append(". */\n");
            sb.append("    public static final ").append(name.refType).append(' ').append(entry.getKey())
                    .append(" = new ").append(name.refType).append('(').append(arguments(name))
                    .append(", ").append(lines(model, name)).append(");\n\n");
            constants.add(className + "." + entry.getKey());
        }
        sb.append("    private ").append(className).append("() {\n    }\n}\n");
        all.put(className, constants);
        return sb.toString();
    }

    private static String arguments(NamesModel.Name name) {
        switch (name.refType) {
            case "AttributeRef":
                return literal(name.resource) + ", " + literal(name.name);
            case "ItemRef":
                return literal("UserType") + ", " + literal(name.namespace) + ", " + literal(name.name);
            default:
                return literal(name.name) + ", " + (name.oids.size() == 1 ? literal(name.oids.iterator().next())
                        : "null");
        }
    }

    private static String lines(NamesModel model, NamesModel.Name name) {
        if (name.variants.contains(NamesModel.EVERY_LINE)) {
            List<String> except = model.variants.stream()
                    .filter(v -> !v.equals(NamesModel.EVERY_LINE) && !name.variants.contains(v))
                    .collect(Collectors.toList());
            return except.isEmpty() ? "Lines.ALL"
                    : "Lines.except(" + except.stream().map(NamesGenerator::literal).collect(Collectors.joining(", "))
                            + ")";
        }
        return "Lines.only(" + name.variants.stream().map(NamesGenerator::literal).collect(Collectors.joining(", "))
                + ")";
    }

    private static String index(NamesModel model, String pkg, Map<String, List<String>> all) {
        StringBuilder sb = header(model, pkg);
        sb.append("/**\n * Every generated name of the deployment, which the load checks against the loaded deployment.\n"
                + " */\n");
        sb.append("public final class Names {\n\n");
        sb.append("    /** The manifest the names were generated from. */\n");
        sb.append("    public static final String MANIFEST = ")
                .append(literal(model.manifest.file.getFileName().toString())).append(";\n\n");
        sb.append("    private Names() {\n    }\n\n");
        sb.append("    public static java.util.List<DeploymentName> all() {\n");
        sb.append("        java.util.List<DeploymentName> all = new java.util.ArrayList<>();\n");
        for (List<String> constants : all.values()) {
            for (String constant : constants) {
                sb.append("        all.add(").append(constant).append(");\n");
            }
        }
        sb.append("        return all;\n    }\n}\n");
        return sb.toString();
    }

    private static StringBuilder header(NamesModel model, String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by midpoint-config-tester (NamesGenerator) from the deployment of ")
                .append(model.manifest.dir.getFileName()).append('/').append(model.manifest.file.getFileName())
                .append(". Do not edit.\n");
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("import com.github.wadahiro.midpoint.configtester.names.*;\n\n");
        return sb;
    }

    // ---- identifiers --------------------------------------------------------------------------------------------

    /** {@code Dept Sales} to {@code DEPT_SALES}, {@code firstName} to {@code FIRST_NAME}, {@code 1st} to {@code _1ST}. */
    static String constant(String name) {
        StringBuilder sb = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (Character.isUpperCase(c) && (Character.isLowerCase(previous) || Character.isDigit(previous))) {
                    sb.append('_');
                }
                sb.append(Character.toUpperCase(c));
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                sb.append('_');
            }
            previous = c;
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    /** {@code LDAP} to {@code Ldap}, {@code Active Directory} to {@code ActiveDirectory}. */
    static String className(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : constant(name).split("_")) {
            if (!part.isEmpty()) {
                sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        if (sb.length() == 0 || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, 'R');
        }
        return sb.toString();
    }

    static String literal(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String javadoc(String text) {
        return text.replace("*/", "*&#47;").replace("@", "&#64;").replace("{", "&#123;").replace("}", "&#125;");
    }

    /** Non-ASCII characters as unicode escapes: the sources compile whatever the project's source encoding. */
    private static String ascii(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c < 128) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    // ---- writing ------------------------------------------------------------------------------------------------

    static List<String> write(Path outputDir, Map<Path, String> files) {
        List<String> written = new ArrayList<>();
        try {
            Files.createDirectories(outputDir);
            for (Map.Entry<Path, String> file : files.entrySet()) {
                byte[] content = ascii(file.getValue()).getBytes(StandardCharsets.UTF_8);
                Path path = file.getKey();
                if (Files.isRegularFile(path) && java.util.Arrays.equals(Files.readAllBytes(path), content)) {
                    continue;
                }
                Files.createDirectories(path.getParent());
                Files.write(path, content);
                written.add(outputDir.relativize(path).toString());
            }
            // the generated files by identity, not by path: on macOS, JDK 11 writes and reads names decomposed (NFD)
            // and JDK 17+ as they are (NFC), and the file system takes both for one file. A class with a kana with
            // dakuten in its name that the other JDK wrote is the same file, not a stale one
            Set<Object> generated = new HashSet<>();
            for (Path path : files.keySet()) {
                generated.add(fileKey(path));
            }
            try (Stream<Path> walk = Files.walk(outputDir)) {
                for (Path stale : walk.filter(p -> p.toString().endsWith(".java") && !files.containsKey(p))
                        .sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    if (!generated.contains(fileKey(stale))) {
                        Files.delete(stale);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the names to " + outputDir, e);
        }
        return written;
    }

    /** What identifies the file: its file key (device and inode), or its real path where the file system has none */
    private static Object fileKey(Path path) throws IOException {
        Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        return key != null ? key : path.toRealPath();
    }
}
