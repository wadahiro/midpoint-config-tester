package com.github.wadahiro.midpoint.scan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.codehaus.groovy.control.CompilerConfiguration;

import groovy.lang.GroovyClassLoader;

/**
 * Lists every expression in the XML files of a configuration directory and compiles its Groovy scripts on the current
 * midPoint line.
 *
 * The files are read as plain XML, not with prism: the list does not depend on the midPoint line, survives files the
 * line's prism rejects, and includes the expressions in search filters ({@code q:expression}), which prism keeps raw.
 * An expression is the parent element of an evaluator element ({@link #EVALUATORS}; {@code value}, {@code path},
 * {@code function} and {@code const} only under an element named like an expression, as they have other meanings
 * elsewhere). Its place ({@link Entry#place}) is a best-effort guess from the element path; the path itself is always
 * reported.
 *
 * Scripts without a language or in Groovy (midPoint's default) are compiled the way midPoint compiles them without an
 * expression profile. Compiling resolves the classes named in imports, {@code new}, declarations, casts and class
 * literals, so a class removed in the target line is found; a fully qualified name used as a value
 * ({@code com.example.Util.call()}) and missing methods are only found at run time. Classes of production that are
 * not on the test classpath (model-impl, GUI, connectors, custom jars) do not resolve on any line: compare the results
 * of two lines (golden files) rather than reading one alone.
 *
 * This class does not depend on the midPoint line nor on a runtime: every line module and the static checks include it.
 */
public final class ConfigScanner {

    /** Evaluator elements that make their parent an expression wherever they appear. */
    static final Set<String> EVALUATORS = Set.of("script", "asIs", "generate", "assignmentTargetSearch",
            "referenceSearch", "associationTargetSearch", "associationFromLink", "shadowOwnerReferenceSearch",
            "proportional", "sequentialValue", "assignmentFromAssociation", "associationSynchronization",
            "associationConstruction");
    /**
     * Evaluator elements whose names have other meanings too: counted only under an expression-like parent
     * ({@code filter} is an evaluator since 4.10).
     */
    static final Set<String> AMBIGUOUS_EVALUATORS = Set.of("value", "path", "function", "const", "filter");
    /** Elements whose content looks like expressions but is not, e.g. the evaluators an expression profile allows. */
    static final Set<String> IGNORED = Set.of("expressionProfile");
    private static final Pattern EXPRESSION_LIKE = Pattern.compile(
            "(?i).*(expression|condition|token)$");

    private static final String GROOVY = "http://midpoint.evolveum.com/xml/ns/public/expression/language#Groovy";

    /** Place labels: the first pattern (matched against {@link Entry#path}) wins. */
    private static final List<String[]> PLACES = List.of(
            new String[] { "/correlation/|/correlators/", "correlation" },
            new String[] { "/filter/.*expression$", "search filter" },
            new String[] { "^functionLibrary/function$", "function library" },
            new String[] { "/construction/.*/outbound/", "construction outbound" },
            new String[] { "/(inbound)/", "inbound" },
            new String[] { "/(outbound)/", "outbound" },
            new String[] { "^objectTemplate/(item/)?mapping/", "object template mapping" },
            new String[] { "/focusMappings/mapping/", "focus mapping" },
            new String[] { "/autoassign/focus/mapping/", "autoassign mapping" },
            new String[] { "/(assignment|inducement)/condition(/expression)?$", "assignment condition" },
            new String[] { "/iteration(Specification)?/token(Expression)?$", "iteration token" },
            new String[] { "/iteration/(pre|post)IterationCondition$", "iteration condition" },
            new String[] { "/confirmation$", "confirmation" },
            new String[] { "/delineation/classificationCondition$", "classification condition" },
            new String[] { "/synchronization/reaction/condition$", "synchronization reaction" },
            new String[] { "/synchronization/.*/condition$", "synchronization condition" },
            new String[] { "/view/column/export/expression$", "report column" },
            new String[] { "^objectCollection/.*/column/export/expression$", "collection view column" },
            new String[] { "^report/.*/condition$", "report condition" },
            new String[] { "^report/.*/subreport/expression$", "report subreport" },
            new String[] { "^report/", "report" },
            new String[] { "^dashboard/", "dashboard widget" },
            new String[] { "/valuePolicyRef|/stringPolicy/|^valuePolicy/", "value policy" },
            new String[] { "/approverExpression$", "approver" },
            new String[] { "/automaticallyCompleted$", "approval auto-completion" },
            new String[] { "/stage/additionalInformation$", "approval additional information" },
            new String[] { "/approvalDisplayName/", "approval display name" },
            new String[] { "/reviewerExpression$", "certification reviewer" },
            new String[] { "/itemSelectionExpression$", "certification item selection" },
            new String[] { "/policyConstraints/", "policy constraint" },
            new String[] { "/policyActions/[^/]+/condition$", "policy action condition" },
            new String[] { "^systemConfiguration/modelHooks/", "model hook" },
            new String[] { "^systemConfiguration/notificationConfiguration/", "notifier" },
            new String[] { "/lifecycleStateModel/", "lifecycle" },
            new String[] { "^task/", "task" });

    private ConfigScanner() {
    }

    /** One expression. */
    public static final class Entry {
        /** The file, relative to the scanned directory, with {@code /} separators. */
        public final String file;
        /** Line of the expression's start tag. */
        public final int line;
        /** Local names from the object's root element to the expression, e.g. {@code role/inducement/condition}. */
        public final String path;
        /** Where midPoint evaluates it (best effort), e.g. {@code inbound}; {@code other} when unknown. */
        public final String place;
        /** The evaluator elements, in document order. */
        public final List<String> evaluators = new ArrayList<>();
        /** The scripts: language (null = Groovy) and code. */
        public final List<String[]> scripts = new ArrayList<>();
        /** The canonical form of each evaluator element ({@link EvaluatorFingerprint}), in document order. */
        public final List<String> fingerprints = new ArrayList<>();
        /** The expression this one is nested in (e.g. a filter of a search expression), or null. */
        public Entry enclosing;

        Entry(String file, int line, String path) {
            this.file = file;
            this.line = line;
            this.path = path;
            this.place = place(path);
        }

        /** {@code file:line path}. */
        public String id() {
            return file + ":" + line + " " + path;
        }

        /**
         * The expression's fingerprint: the canonical forms of its evaluator elements. midPoint hands all of them to
         * one evaluator, so the coverage of non-script expressions matches the whole list.
         */
        public String fingerprint() {
            return String.join(" | ", fingerprints);
        }

        /** Whether some script is Groovy, i.e. {@link #compile()} has something to compile. */
        public boolean hasGroovy() {
            return scripts.stream().anyMatch(s -> isGroovy(s[0]));
        }

        /** Languages of the scripts that are not compiled. */
        public List<String> otherLanguages() {
            return scripts.stream().map(s -> s[0]).filter(l -> !isGroovy(l)).distinct().collect(Collectors.toList());
        }

        /** Compiles the Groovy scripts on this line; throws the compilation error of the first that fails. */
        public void compile() throws Exception {
            compile(ConfigScanner.class.getClassLoader());
        }

        /** As {@link #compile()}, with the classes of this loader (the line's and, e.g., a deployment's own jars). */
        public void compile(ClassLoader classes) throws Exception {
            for (String[] script : scripts) {
                if (isGroovy(script[0])) {
                    ConfigScanner.compile(script[1], classes);
                }
            }
        }

        @Override
        public String toString() {
            return id() + " (" + place + ": " + evaluators + ")";
        }
    }

    /** The expressions of every {@code .xml} file under the directory, by file name and line. */
    public static List<Entry> scan(File dir) throws IOException {
        Path root = dir.toPath();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".xml")).sorted()
                    .collect(Collectors.toList());
        }
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            entries.addAll(scanFile(file, root.relativize(file).toString().replace(File.separatorChar, '/')));
        }
        return entries;
    }

    /** The expressions of one file. */
    public static List<Entry> scanFile(Path file, String name) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return scan(in, name);
        } catch (XMLStreamException e) {
            throw new IOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    // ---- reading ------------------------------------------------------------------------------------------

    private static final class Element {
        final String name;
        final int line;
        Entry entry;
        /** The text of its first {@code name} child, once read. */
        String nameText;
        /** Whether it is a script evaluator ({@code script}, or the script of an {@code execute-script} action). */
        boolean scriptEvaluator;

        Element(String name, int line) {
            this.name = name;
            this.line = line;
        }
    }

    /** A StAX factory that reads text in one piece and resolves no external entities. */
    static XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /** The form of an evaluator element being read, and where it goes in its entry. */
    private static final class OpenFingerprint {
        final EvaluatorFingerprint fingerprint;
        final Entry entry;
        final int index;

        OpenFingerprint(EvaluatorFingerprint fingerprint, Entry entry, int index) {
            this.fingerprint = fingerprint;
            this.entry = entry;
            this.index = index;
        }
    }

    /**
     * The element's local name; for an object written as {@code <object xsi:type="c:RoleType">} (the root, or a child
     * of {@code <objects>}) the element name of its type ({@code role}), so paths are the same either way.
     */
    private static String objectName(XMLStreamReader reader, Deque<Element> stack) {
        String local = reader.getLocalName();
        boolean object = stack.isEmpty() || stack.size() == 1 && "objects".equals(stack.peek().name);
        if (!object || !"object".equals(local)) {
            return local;
        }
        String typed = elementNameOfType(
                reader.getAttributeValue(javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
        return typed != null ? typed : local;
    }

    /** The element name of an object type ({@code c:RoleType} -> {@code role}); null for none. */
    public static String elementNameOfType(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        String local = type.substring(type.indexOf(':') + 1);
        String name = local.endsWith("Type") ? local.substring(0, local.length() - 4) : local;
        return name.isEmpty() ? null : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static List<Entry> scan(InputStream in, String name) throws XMLStreamException {
        XMLStreamReader reader = inputFactory().createXMLStreamReader(in);

        List<Entry> entries = new ArrayList<>();
        Deque<Element> stack = new ArrayDeque<>();
        // evaluator elements being read; an evaluator inside another (a filter expression) is in both
        List<OpenFingerprint> fingerprints = new ArrayList<>();
        String[] script = null;
        StringBuilder text = new StringBuilder();
        try {
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT:
                        String local = objectName(reader, stack);
                        Element parent = stack.peek();
                        Element element = new Element(local, reader.getLocation().getLineNumber());
                        String oid = EvaluatorFingerprint.oid(reader);
                        fingerprints.forEach(f -> f.fingerprint.start(local, oid));
                        boolean scriptParameter = "value".equals(local) && isScriptParameter(stack);
                        if (parent != null && (isEvaluator(local, parent.name) || scriptParameter)
                                && stack.stream().noneMatch(e -> IGNORED.contains(e.name))) {
                            // the script of an execute-script action is a ScriptExpressionEvaluatorType: midPoint
                            // runs it as a <script>, which is what its canonical form is made of
                            String evaluator = scriptParameter ? "script" : local;
                            if (parent.entry == null) {
                                parent.entry = new Entry(name, parent.line, path(stack));
                                parent.entry.enclosing = stack.stream().skip(1).map(e -> e.entry)
                                        .filter(e -> e != null).findFirst().orElse(null);
                                entries.add(parent.entry);
                            }
                            parent.entry.evaluators.add(evaluator);
                            if ("script".equals(evaluator)) {
                                script = new String[] { null, "" };
                                parent.entry.scripts.add(script);
                                element.scriptEvaluator = true;
                            }
                            parent.entry.fingerprints.add(null);
                            fingerprints.add(new OpenFingerprint(new EvaluatorFingerprint(evaluator, oid),
                                    parent.entry, parent.entry.fingerprints.size() - 1));
                        }
                        stack.push(element);
                        text.setLength(0);
                        break;
                    case XMLStreamConstants.CHARACTERS:
                    case XMLStreamConstants.CDATA:
                        text.append(reader.getText());
                        String chunk = reader.getText();
                        fingerprints.forEach(f -> f.fingerprint.text(chunk));
                        break;
                    case XMLStreamConstants.END_ELEMENT:
                        for (Iterator<OpenFingerprint> it = fingerprints.iterator(); it.hasNext();) {
                            OpenFingerprint open = it.next();
                            if (open.fingerprint.end()) {
                                open.entry.fingerprints.set(open.index, open.fingerprint.form());
                                it.remove();
                            }
                        }
                        Element ended = stack.pop();
                        Element owner = stack.peek();
                        if (owner != null && owner.nameText == null && "name".equals(ended.name)) {
                            owner.nameText = text.toString().strip();
                        }
                        if (script != null && owner != null && owner.scriptEvaluator) {
                            if ("code".equals(ended.name)) {
                                script[1] = text.toString();
                            } else if ("language".equals(ended.name)) {
                                script[0] = text.toString().strip();
                            }
                        }
                        if (ended.scriptEvaluator) {
                            script = null;
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
        return entries;
    }

    /**
     * Whether the element being opened is in the {@code script} parameter of an {@code execute-script} action (the
     * bulk actions of 4.0, which has no {@code <s:execute>}; later lines still run them):
     * {@code action/parameter[name=script]/value}.
     */
    private static boolean isScriptParameter(Deque<Element> stack) {
        Iterator<Element> up = stack.iterator();
        Element parameter = up.hasNext() ? up.next() : null;
        Element action = up.hasNext() ? up.next() : null;
        return parameter != null && action != null && "parameter".equals(parameter.name)
                && "action".equals(action.name) && "script".equals(parameter.nameText);
    }

    private static boolean isEvaluator(String local, String parent) {
        return EVALUATORS.contains(local)
                || AMBIGUOUS_EVALUATORS.contains(local) && EXPRESSION_LIKE.matcher(parent).matches();
    }

    /** The element path without an {@code objects} wrapper. */
    private static String path(Deque<Element> stack) {
        List<String> names = new ArrayList<>();
        stack.descendingIterator().forEachRemaining(e -> names.add(e.name));
        if (names.size() > 1 && "objects".equals(names.get(0))) {
            names.remove(0);
        }
        return String.join("/", names);
    }

    private static String place(String path) {
        String withSlash = path + "/";
        for (String[] place : PLACES) {
            Pattern pattern = Pattern.compile(place[0]);
            if (pattern.matcher(path).find() || pattern.matcher(withSlash).find()) {
                return place[1];
            }
        }
        return "other";
    }

    // ---- compiling ----------------------------------------------------------------------------------------

    public static boolean isGroovy(String language) {
        return language == null || language.isEmpty() || GROOVY.equals(language);
    }

    /** midPoint's compilation without an expression profile; a fresh loader, as parseClass caches by file name. */
    private static void compile(String code, ClassLoader classes) throws IOException {
        try (GroovyClassLoader loader = new GroovyClassLoader(classes,
                new CompilerConfiguration(CompilerConfiguration.DEFAULT))) {
            loader.parseClass(code, "Script.groovy");
        }
    }
}
