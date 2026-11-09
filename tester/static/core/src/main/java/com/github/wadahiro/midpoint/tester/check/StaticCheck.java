package com.github.wadahiro.midpoint.tester.check;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.ParsingContext;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.github.wadahiro.midpoint.tester.Identity;
import com.github.wadahiro.midpoint.tester.check.Finding.Check;
import com.github.wadahiro.midpoint.tester.check.Finding.Severity;
import com.github.wadahiro.midpoint.scan.ConfigScanner;
import com.github.wadahiro.midpoint.tester.internal.ConfigFiles;

/**
 * Static checks of a directory of configuration XML for one midPoint line, without a midPoint runtime (no Spring, no
 * database):
 *
 * <ol>
 * <li>{@link Check#COMPILE}: every Groovy script compiles on the line's classpath (midPoint's compilation without an
 * expression profile). Finds missing and removed classes in imports, declarations, {@code new}, casts and class
 * literals, and syntax errors; a missing method or a fully qualified name used as a value is only found at run time
 * (the tester).</li>
 * <li>{@link Check#SCHEMA}: every file parses with the line's prism, strict (as an import without {@code compatMode})
 * and in compatibility mode (its warnings).</li>
 * <li>{@link Check#UPGRADE}: every object goes through ninja verify's {@code ObjectUpgradeValidator} (4.4+; skipped
 * with the reason on older lines, {@link StaticReport#skipped(Check)}).</li>
 * <li>{@link Check#LINT}: mappings sharing a name in one object, identities that depend on the order of siblings,
 * expressions whose evaluator element the line's schema does not have, Groovy scripts that read the JVM's clock
 * instead of midPoint's ({@link #JDK_TIME}).</li>
 * </ol>
 *
 * Every finding is identified like the tester's evaluations ({@link Identity}, from the same index code), so a
 * finding and the evaluations of the place are named alike.
 *
 * <pre>
 * StaticReport report = StaticCheck.of(Path.of("config")).schemaDir(Path.of("config/schema")).run();
 * report.write(Path.of("target/static-check.md"));
 * report.assertNoErrors(Check.COMPILE);     // AssertionError listing each failing place
 * assertThat(report.errors()).isEmpty();
 * </pre>
 */
public final class StaticCheck {

    /** Prism contexts by extension schema directory: initializing one takes seconds. */
    private static final Map<String, PrismContext> PRISM = new ConcurrentHashMap<>();

    private final Path dir;
    private Path schemaDir;
    /** Jars and class directories the scripts compile against, after the line's classpath. */
    private final List<Path> classpath = new ArrayList<>();
    private final Set<Check> checks = EnumSet.allOf(Check.class);
    /** Why a check could not run on this line, e.g. no upgrade validator. */
    private final Map<Check, String> skipped = new EnumMap<>(Check.class);

    private StaticCheck(Path dir) {
        this.dir = dir;
    }

    /** The configuration directory: every {@code .xml} file under it. */
    public static StaticCheck of(Path dir) {
        return new StaticCheck(dir);
    }

    /** The midPoint line of the classpath, e.g. {@code 4.8}. */
    public static String line() {
        return StaticLine.LINE;
    }

    /** Extension schemas ({@code *.xsd}) to parse the objects with; none by default. */
    public StaticCheck schemaDir(Path schemaDir) {
        this.schemaDir = schemaDir;
        return this;
    }

    /**
     * Jars, class directories, or directories of jars the scripts compile against besides the line's classpath: the
     * deployment's own libraries (a jar its scripts import, a library of midPoint's {@code lib} directory, the GUI's
     * jar for a script that uses a GUI class).
     */
    public StaticCheck classpath(Path... entries) {
        classpath.addAll(List.of(entries));
        return this;
    }

    /** Only these checks (all by default). */
    public StaticCheck checks(Check first, Check... more) {
        checks.clear();
        checks.add(first);
        checks.addAll(List.of(more));
        return this;
    }

    public StaticReport run() {
        ConfigFiles files = ConfigFiles.scan(dir);
        List<ConfigFiles.Place> places = files.places();
        List<Finding> findings = new ArrayList<>();
        skipped.clear();
        PrismContext prism = checks.contains(Check.SCHEMA) || checks.contains(Check.UPGRADE)
                || checks.contains(Check.LINT) ? prismContext() : null;
        if (checks.contains(Check.COMPILE)) {
            compile(places, findings);
        }
        if (checks.contains(Check.SCHEMA) || checks.contains(Check.UPGRADE)) {
            for (Path file : xmlFiles()) {
                parse(prism, files, file, name(file), findings);
            }
        }
        if (checks.contains(Check.LINT)) {
            lint(StaticLine.evaluators(prism), files, places, findings);
        }
        int objects = xmlFiles().stream().mapToInt(f -> files.objects(name(f)).size()).sum();
        return new StaticReport(dir, StaticLine.LINE, checks, skipped, xmlFiles().size(), objects, places,
                findings);
    }

    // ---- compilation ----------------------------------------------------------------------------------------

    private void compile(List<ConfigFiles.Place> places, List<Finding> findings) {
        try (java.net.URLClassLoader classes = new java.net.URLClassLoader(urls(),
                StaticCheck.class.getClassLoader())) {
            for (ConfigFiles.Place place : places) {
                if (!place.entry.hasGroovy()) {
                    continue;
                }
                try {
                    place.entry.compile(classes);
                } catch (Exception | LinkageError e) {
                    findings.add(new Finding(Check.COMPILE, Severity.ERROR, "groovy", place.identity, place.file,
                            place.line, compileMessage(e)));
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The {@link #classpath} entries as URLs; a directory without class files stands for its jars. */
    private java.net.URL[] urls() throws java.io.IOException {
        List<java.net.URL> out = new ArrayList<>();
        for (Path entry : classpath) {
            if (!java.nio.file.Files.exists(entry)) {
                throw new IllegalArgumentException("classpath: no such file or directory " + entry);
            }
            List<Path> jars = new ArrayList<>();
            if (java.nio.file.Files.isDirectory(entry)) {
                try (java.util.stream.Stream<Path> list = java.nio.file.Files.list(entry)) {
                    list.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
                }
            }
            if (jars.isEmpty()) {
                out.add(entry.toUri().toURL());
            } else {
                for (Path jar : jars) {
                    out.add(jar.toUri().toURL());
                }
            }
        }
        return out.toArray(new java.net.URL[0]);
    }

    /** Groovy's errors without the source excerpt: {@code unable to resolve class X (script line 2, column 1)}. */
    static String compileMessage(Throwable e) {
        if (e instanceof MultipleCompilationErrorsException) {
            List<String> out = new ArrayList<>();
            for (Object message : ((MultipleCompilationErrorsException) e).getErrorCollector().getErrors()) {
                if (message instanceof SyntaxErrorMessage) {
                    SyntaxException cause = ((SyntaxErrorMessage) message).getCause();
                    out.add(cause.getOriginalMessage().strip() + " (script line " + cause.getLine() + ", column "
                            + cause.getStartColumn() + ")");
                } else if (message instanceof ExceptionMessage) {
                    out.add(String.valueOf(((ExceptionMessage) message).getCause()));
                } else {
                    out.add(String.valueOf(message));
                }
            }
            return String.join("; ", out);
        }
        return e.getClass().getSimpleName() + ": " + firstLine(String.valueOf(e.getMessage()));
    }

    // ---- schema and upgrade ---------------------------------------------------------------------------------

    private void parse(PrismContext prism, ConfigFiles files, Path file, String name, List<Finding> findings) {
        List<ConfigFiles.Element> objects = files.objects(name);
        // a finding of the file: its object when it has one
        Identity fileIdentity = objects.size() == 1 ? objects.get(0).identity : null;
        int fileLine = objects.size() == 1 ? objects.get(0).line : 0;
        ParsingContext compat = prism.createParsingContextForCompatibilityMode();
        List<PrismObject<? extends Objectable>> parsed;
        try {
            parsed = prism.parserFor(file.toFile()).language(PrismContext.LANG_XML).context(compat).parseObjects();
        } catch (Exception e) {
            if (checks.contains(Check.SCHEMA)) {
                findings.add(new Finding(Check.SCHEMA, Severity.ERROR, "unreadable", fileIdentity, name, fileLine,
                        "not parsed even in compatibility mode: " + message(e)));
            }
            return;
        }
        if (checks.contains(Check.SCHEMA)) {
            try {
                prism.parserFor(file.toFile()).language(PrismContext.LANG_XML).strict().parseObjects();
            } catch (Exception e) {
                findings.add(new Finding(Check.SCHEMA, Severity.ERROR, "strict", fileIdentity, name, fileLine,
                        "import without compatMode fails: " + message(e)));
            }
            for (String warning : compat.getWarnings()) {
                findings.add(new Finding(Check.SCHEMA, Severity.WARNING, "warning", fileIdentity, name, fileLine,
                        firstLine(warning)));
            }
        }
        if (!checks.contains(Check.UPGRADE) || skipped.containsKey(Check.UPGRADE)) {
            return;
        }
        for (int i = 0; i < parsed.size(); i++) {
            PrismObject<? extends Objectable> object = parsed.get(i);
            // the objects of the file as read: the same order (a count that differs: name the file only)
            int index = parsed.size() == objects.size() ? i : -1;
            ConfigFiles.Element owner = index >= 0 ? objects.get(index) : null;
            List<UpgradeItem> items;
            try {
                assignMissingIds(object);
                items = StaticLine.upgradeFindings(prism, object);
            } catch (Exception e) {
                findings.add(new Finding(Check.UPGRADE, Severity.WARNING, "validator-failed",
                        owner != null ? owner.identity : null, name, owner != null ? owner.line : 0,
                        "the validator failed: " + message(e)));
                continue;
            }
            if (items == null) {
                skipped.put(Check.UPGRADE, "midPoint " + StaticLine.LINE + " has no upgrade validator (ninja verify's "
                        + "ObjectUpgradeValidator is 4.4+)");
                return;
            }
            for (UpgradeItem item : items) {
                ConfigFiles.Element element = index >= 0 ? files.element(name, index, steps(item.path)) : null;
                String kind = item.priority != null ? item.priority : item.status;
                findings.add(new Finding(Check.UPGRADE, severity(item), kind,
                        element != null ? element.identity : null, name, element != null ? element.line : 0,
                        kind + " " + item.path + ": " + item.message));
            }
        }
    }

    /** CRITICAL: an error; NECESSARY: a warning; without a priority, the validator's status. */
    private static Severity severity(UpgradeItem item) {
        String level = item.priority != null ? item.priority : item.status;
        switch (level) {
            case "CRITICAL":
            case "ERROR":
                return Severity.ERROR;
            case "NECESSARY":
            case "WARNING":
                return Severity.WARNING;
            default:
                return Severity.NOTE;
        }
    }

    /** The item path as element names with container value IDs (null where none). */
    static List<Map.Entry<String, Long>> steps(ItemPath path) {
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                out.add(new AbstractMap.SimpleEntry<>(ItemPath.toName(segment).getLocalPart(), null));
            } else if (ItemPath.isId(segment) && !out.isEmpty()) {
                out.get(out.size() - 1).setValue(ItemPath.toId(segment));
            } else {
                break;
            }
        }
        return out;
    }

    /**
     * Gives the values of multi-valued containers an ID where they have none, after the highest of their siblings, as
     * the repository does when the object is imported (the validator addresses values by ID).
     */
    static void assignMissingIds(PrismObject<?> object) {
        object.accept(visitable -> {
            if (!(visitable instanceof PrismContainer)) {
                return;
            }
            PrismContainer<?> container = (PrismContainer<?>) visitable;
            if (container.getDefinition() == null || !container.getDefinition().isMultiValue()) {
                return;
            }
            long next = 1;
            for (PrismContainerValue<?> value : container.getValues()) {
                if (value.getId() != null) {
                    next = Math.max(next, value.getId() + 1);
                }
            }
            for (PrismContainerValue<?> value : container.getValues()) {
                if (value.getId() == null) {
                    value.setId(next++);
                }
            }
        });
    }

    // ---- lint -----------------------------------------------------------------------------------------------

    /**
     * Groovy that reads the JVM's clock: {@code new Date()}, {@code System.currentTimeMillis()}, {@code now()} of the
     * {@code java.time} types, {@code Calendar.getInstance()}, {@code new GregorianCalendar()}, and midPoint's
     * {@code basic.fromNow(...)}, which adds the duration to the JVM's time (prism's {@code XmlTypeConverter}).
     * midPoint's clock ({@code Clock}: the validity and trigger scanners, time constraints of mappings) is read by
     * {@code basic.currentDateTime()}. The tester's {@code mp.time()} shifts the JVM's clock with it, a clock override
     * of midPoint alone does not: then such a script disagrees with the scanners.
     */
    static final Pattern JDK_TIME = Pattern.compile("new\\s+Date\\s*\\(\\s*\\)"
            + "|System\\s*\\.\\s*currentTimeMillis\\s*\\("
            + "|\\b(?:Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth)"
            + "\\s*\\.\\s*now\\s*\\("
            + "|Calendar\\s*\\.\\s*getInstance\\s*\\("
            + "|new\\s+GregorianCalendar\\s*\\(\\s*\\)"
            + "|\\bfromNow\\s*\\(");

    /** The distinct reads of the JVM's clock in Groovy code ({@link #JDK_TIME}), e.g. {@code new Date()}. */
    static List<String> jdkTime(String code) {
        List<String> out = new ArrayList<>();
        Matcher m = JDK_TIME.matcher(code);
        while (m.find()) {
            String found = m.group().replaceAll("\\s*([.()])\\s*", "$1").replaceAll("\\s+", " ");
            found = found.endsWith("(") ? found + ")" : found;
            if (!out.contains(found)) {
                out.add(found);
            }
        }
        return out;
    }

    private static void lint(Set<String> evaluators, ConfigFiles files, List<ConfigFiles.Place> places,
            List<Finding> findings) {
        for (ConfigFiles.Place place : places) {
            List<String> unknown = place.evaluators.stream().filter(e -> !evaluators.contains(e)).distinct()
                    .collect(Collectors.toList());
            if (!unknown.isEmpty()) {
                findings.add(new Finding(Check.LINT, Severity.ERROR, "no-evaluator", place.identity, place.file,
                        place.line, "midPoint " + StaticLine.LINE + " has no evaluator "
                                + unknown.stream().map(e -> "<" + e + ">").collect(Collectors.joining(", "))));
            }
            List<String> clock = new ArrayList<>();
            for (String[] script : place.entry.scripts) {
                if (ConfigScanner.isGroovy(script[0])) {
                    jdkTime(script[1]).stream().filter(t -> !clock.contains(t)).forEach(clock::add);
                }
            }
            if (!clock.isEmpty()) {
                findings.add(new Finding(Check.LINT, Severity.WARNING, "jdk-time", place.identity, place.file,
                        place.line, "reads the JVM's clock (" + String.join(", ", clock) + "), not midPoint's "
                                + "Clock: the tester's mp.time() moves both, a clock override of midPoint alone "
                                + "(or a JVM without the tester's agent) only midPoint's; basic.currentDateTime() "
                                + "(and basic.addDuration(basic.currentDateTime(), 'P1D') for basic.fromNow('P1D')) "
                                + "always follows midPoint's Clock"));
            }
        }
        files.duplicateMappings().forEach((object, byName) -> byName.forEach((name, mappings) -> findings.add(
                new Finding(Check.LINT, Severity.WARNING, "duplicate-mapping-name", object.identity, object.file,
                        object.line, mappings.size() + " mappings named '" + name + "' (selecting by name is "
                                + "ambiguous): " + mappings.stream().map(m -> m.identity + " (" + m.file + ":"
                                        + m.line + ")").collect(Collectors.joining(", "))))));
        // one finding per element told apart by position, with the places below it
        Map<String, List<ConfigFiles.Place>> byOrdinal = new LinkedHashMap<>();
        Map<String, Identity> ordinalIdentity = new LinkedHashMap<>();
        for (ConfigFiles.Place place : places) {
            List<Identity.Segment> segments = place.identity.segments();
            for (int i = 0; i < segments.size(); i++) {
                Identity.Segment segment = segments.get(i);
                if (segment.isOrdinal() && isMapping(segment.element()) && "name".equals(segment.key())) {
                    // a mapping sharing its name: duplicate-mapping-name says so
                    break;
                }
                if (segment.isOrdinal()) {
                    Identity prefix = new Identity(place.identity.ownerType(), place.identity.ownerOid(),
                            place.identity.ownerName(), segments.subList(0, i + 1), true);
                    String key = place.file + " " + prefix;
                    byOrdinal.computeIfAbsent(key, k -> new ArrayList<>()).add(place);
                    ordinalIdentity.put(key, prefix);
                    break;
                }
            }
        }
        byOrdinal.forEach((key, list) -> {
            Identity identity = ordinalIdentity.get(key);
            ConfigFiles.Place first = list.get(0);
            findings.add(new Finding(Check.LINT, Severity.NOTE, "ordinal", identity, first.file, 0,
                    advice(identity.last()) + "; the identity of " + list.size() + " expression place"
                            + (list.size() > 1 ? "s" : "") + " in it changes when siblings are added or removed"));
        });
    }

    /** What would tell the element apart from its siblings. */
    static String advice(Identity.Segment segment) {
        String element = segment.element();
        if (segment.value() != null) {
            return "<" + element + "> shares " + segment.key() + " '" + segment.value() + "' with a sibling: make it "
                    + "unique" + (isMapping(element) ? " (rename the mapping)" : "");
        }
        if (isMapping(element)) {
            return "<" + element + "> has neither a <name> nor a target path: give it a <name>";
        }
        switch (element) {
            case "assignment":
            case "inducement":
                return "<" + element + "> has nothing that tells it apart from its siblings (targetRef, construction, "
                        + "named policyRule, order, focus mapping names, target filter): give it a <description>, or "
                        + "keep the order of its siblings";
            case "stage":
            case "stageDefinition":
                return "<" + element + "> has no <number>: give it one";
            default:
                return "<" + element + "> has no <name> (or identifier) and no unique <description>: give it one";
        }
    }

    private static boolean isMapping(String element) {
        return "mapping".equals(element) || "inbound".equals(element) || "outbound".equals(element);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private PrismContext prismContext() {
        String key = schemaDir != null ? schemaDir.toAbsolutePath().normalize().toString() : "";
        return PRISM.computeIfAbsent(key, k -> {
            try {
                return StaticLine.prismContext(schemaDir != null ? schemaDir.toFile() : null);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot initialize prism with the schemas of " + schemaDir, e);
            }
        });
    }

    private List<Path> xmlFiles() {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".xml")).sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dir, e);
        }
    }

    private String name(Path file) {
        return dir.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static String message(Throwable e) {
        return firstLine(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    /** The first line, without the dump of the container that some prism messages append. */
    static String firstLine(String text) {
        String line = text.split("\\R", 2)[0];
        int dump = line.indexOf(" (in ");
        return (dump >= 0 ? line.substring(0, dump) : line).strip();
    }
}
