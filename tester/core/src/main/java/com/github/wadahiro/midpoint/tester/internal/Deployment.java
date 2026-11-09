package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.evolveum.midpoint.common.Clock;
import com.github.wadahiro.midpoint.configtester.DeploymentLoadException;
import com.github.wadahiro.midpoint.tester.CsvFile;
import com.github.wadahiro.midpoint.tester.LdapDirectory;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.ResourceOptions;
import com.github.wadahiro.midpoint.tester.Run;

/**
 * A deployment loaded from its manifest ({@link Manifest}) into the runtime of this JVM, once: the state every
 * scenario starts from.
 *
 * <p>The load, on the running midPoint (reset first to midPoint as the line initializes it: the tester's startup
 * baseline plus the line's initial objects, see {@link TesterRuntime#resetToInitialized()}):</p>
 * <ol>
 *   <li>the config directories are copied, layered, to {@code <workDir>/tester-deployment/<name>/post-initial-objects}
 *       with the excludes, the line's overlay and the properties ({@code {{name}}}) applied; the user's files are
 *       only read;</li>
 *   <li>every resource in the copy is moved to the tester's target system ({@code targets:}, a mirror by default)
 *       and the target systems get their initial data; the mail servers of a system configuration are redirected to the
 *       tester's SMTP server; directories of {@code files.map} are mapped. So the import never reaches a real
 *       system;</li>
 *   <li>midPoint's post-initial import runs on the copy ({@link PostInitialImport}), with Quartz held, as midPoint's
 *       scheduler is not running yet while it imports; the first failing file fails the load;</li>
 *   <li>the recurring tasks are held ({@code tasks.recurring: held}) or left to Quartz; the background work the import
 *       started is awaited; the connection of every resource is tested;</li>
 *   <li>the database, the target systems and the mapped files are snapshot: {@link #reset()} goes back to it.</li>
 * </ol>
 */
public final class Deployment {

    /** {@code {{name}}}: not Groovy's {@code ${...}} nor Velocity's {@code $name}. */
    private static final Pattern PROPERTY = Pattern.compile("\\{\\{([A-Za-z0-9_.\\-]+)\\}\\}");

    private static final Pattern SYSTEM_CONFIGURATION = Pattern.compile("<([A-Za-z0-9_.-]+:)?systemConfiguration[\\s>]");

    /** Loaded deployments (or the failure of their load) by manifest path. */
    private static final Map<Path, Object> LOADED = new HashMap<>();

    /** An association of an account object type of a resource, as its {@code schemaHandling} declares it. */
    public static final class Association {
        /** {@code objectToSubject} (the group holds the members) or {@code subjectToObject}. */
        public final String direction;
        /** Local names: the attribute holding the references, and the attribute of the other side they refer to. */
        public final String associationAttribute;
        public final String valueAttribute;

        Association(String direction, String associationAttribute, String valueAttribute) {
            this.direction = direction;
            this.associationAttribute = associationAttribute;
            this.valueAttribute = valueAttribute;
        }
    }

    private final Manifest manifest;
    private final TesterRuntime runtime;
    private final MidPointImpl mp;
    private final Path workDir;
    private final List<String> importOrder = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> resourceOids = new LinkedHashMap<>();
    private final Map<String, List<Association>> associations = new LinkedHashMap<>();
    private TesterRuntime.State state;
    /** The stand-in XML of each resource as prepared (connector configuration with this JVM's ports and paths). */
    private final Map<String, String> standIns = new LinkedHashMap<>();
    /** Whether a file of the deployment holds a system configuration (it replaces the line's initial one). */
    private boolean systemConfigurationInDeployment;
    /** Whether the prepare step loads the initial data (not when the content comes from the cache). */
    private boolean loadingInitialData = true;
    /** What midPoint's initial import did (this JVM's, or the cached load's). */
    private InitialImport.Outcome initial;
    /** The cache entry this deployment was restored from; null after a fresh load. */
    private Path restoredFrom;
    /** The cache entry written after the fresh load, or restored from; null without the cache. */
    private Path cacheEntry;

    private Deployment(Manifest manifest, TesterRuntime runtime) {
        this.manifest = manifest;
        this.runtime = runtime;
        this.mp = (MidPointImpl) runtime.api();
        this.workDir = runtime.workDir().resolve("tester-deployment").resolve(id(manifest.file));
    }

    /**
     * The deployment of the manifest, loaded on first use in this JVM; a load that failed fails again with the same
     * message, without a second attempt.
     */
    public static synchronized Deployment load(Path manifestFile) {
        Path key = manifestFile.toAbsolutePath().normalize();
        Object loaded = LOADED.get(key);
        if (loaded instanceof Deployment) {
            return (Deployment) loaded;
        }
        if (loaded instanceof DeploymentLoadException) {
            DeploymentLoadException failure = (DeploymentLoadException) loaded;
            throw new DeploymentLoadException(failure.getMessage(), failure);
        }
        try {
            Deployment deployment = loadNow(key);
            LOADED.put(key, deployment);
            return deployment;
        } catch (DeploymentLoadException e) {
            LOADED.put(key, e);
            throw e;
        }
    }

    private static Deployment loadNow(Path key) {
        long start = System.currentTimeMillis();
        Manifest manifest;
        try {
            manifest = Manifest.read(key);
        } catch (IllegalArgumentException e) {
            throw new DeploymentLoadException("Invalid manifest: " + e.getMessage(), e);
        }
        useSchema(manifest);
        TesterRuntime runtime = TesterRuntime.get();
        // the load's own time, without midPoint's start
        start = System.currentTimeMillis();
        DeploymentCache cache = null;
        try {
            cache = DeploymentCache.of(manifest, id(manifest.file));
        } catch (RuntimeException e) {
            // the fresh load reports what is wrong with the files
        }
        if (cache == null) {
            return loadFresh(manifest, runtime, key, start);
        }
        Deployment restored = restoreFromCache(manifest, runtime, cache, key, start);
        if (restored != null) {
            return restored;
        }
        // the first JVM (test fork) builds, the others wait for it and restore
        try (AutoCloseable lock = cache.lock()) {
            restored = restoreFromCache(manifest, runtime, cache, key, start);
            if (restored != null) {
                return restored;
            }
            Deployment deployment = loadFresh(manifest, runtime, key, start);
            long t = System.currentTimeMillis();
            try {
                cache.save(deployment::writeCache);
                deployment.cacheEntry = cache.entry;
                System.out.println("Tester: deployment cached in " + cache.entry + " ("
                        + (System.currentTimeMillis() - t) + " ms)");
            } catch (IOException | RuntimeException e) {
                System.err.println("Tester: deployment not cached (" + e + ")");
            }
            return deployment;
        } catch (DeploymentLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new DeploymentLoadException("Cannot lock the deployment cache " + cache.dir + ": " + e, e);
        }
    }

    private static Deployment loadFresh(Manifest manifest, TesterRuntime runtime, Path key, long start) {
        runtime.resetToInitialized();
        Deployment deployment = new Deployment(manifest, runtime);
        try {
            deployment.loadInto();
        } catch (DeploymentLoadException e) {
            runtime.resetToInitialized();
            throw e;
        } catch (RuntimeException | AssertionError e) {
            runtime.resetToInitialized();
            throw new DeploymentLoadException("Cannot load " + key + " on midPoint " + LineSupport.LINE + ": "
                    + e.getMessage(), e);
        }
        System.out.println("Tester: deployment " + key + " loaded on midPoint " + LineSupport.LINE + " in "
                + (System.currentTimeMillis() - start) + " ms (" + deployment.importOrder.size() + " files)");
        return deployment;
    }

    /**
     * The deployment restored from the cache, or null when it has no entry, or the restore failed (logged; the entry
     * is discarded and the caller loads afresh). Generated names the restored deployment does not have fail as after
     * a fresh load.
     */
    private static Deployment restoreFromCache(Manifest manifest, TesterRuntime runtime, DeploymentCache cache,
            Path key, long start) {
        if (!cache.exists()) {
            return null;
        }
        Deployment deployment = new Deployment(manifest, runtime);
        try {
            deployment.restoreFrom(cache);
        } catch (NamesCheck.Failure e) {
            runtime.resetToBaseline();
            throw e;
        } catch (IOException | RuntimeException | AssertionError e) {
            System.err.println("Tester: the cached deployment " + cache.entry + " cannot be restored, loading it"
                    + " afresh: " + e);
            cache.discard();
            runtime.resetToBaseline();
            return null;
        }
        System.out.println("Tester: deployment " + key + " restored from the cache on midPoint " + LineSupport.LINE
                + " in " + (System.currentTimeMillis() - start) + " ms (" + deployment.importOrder.size() + " files)");
        return deployment;
    }

    /** The extension schema must be known before midPoint starts (it is read once, at start). */
    private static void useSchema(Manifest manifest) {
        if (manifest.schema == null) {
            return;
        }
        String used = System.getProperty("tester.extensionSchemaDir");
        if (!TesterRuntime.isStarted()) {
            // the same schema in another directory (a copy) is the one given to the JVM: kept, the copy may go
            if (used == null || !sameSchemas(Path.of(used), manifest.schema)) {
                System.setProperty("tester.extensionSchemaDir", manifest.schema.toString());
            }
            return;
        }
        if (used == null || !Path.of(used).toAbsolutePath().normalize().equals(manifest.schema)
                && !sameSchemas(Path.of(used), manifest.schema)) {
            throw new DeploymentLoadException(manifest.file + ": config.schema " + manifest.schema
                    + " cannot be used: midPoint has already started in this JVM with the extension schema " + used
                    + " (one schema per JVM: run this manifest's tests in a JVM of their own)");
        }
    }

    /** Whether the two directories hold the same XSD files (relative path and content), e.g. a copy of the other. */
    private static boolean sameSchemas(Path a, Path b) {
        try {
            return xsds(a).equals(xsds(b));
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    private static Map<String, String> xsds(Path dir) throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xsd")).collect(Collectors.toList())) {
                out.put(slashed(dir.relativize(file)), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    // ---- the load -------------------------------------------------------------------------------------------

    private void loadInto() {
        Path copy = workDir.resolve("post-initial-objects");
        try {
            deleteTree(workDir);
            Files.createDirectories(copy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String directory : manifest.mappedDirectories) {
            FileArea.get().map(directory);
        }
        Map<String, Path> sources = sources();
        prepare(sources, copy);

        Harness harness = mp.harness;
        org.quartz.Scheduler scheduler = harness.quartzScheduler();
        PostInitialImport.Outcome outcome;
        TaskSchedules.hold(scheduler);
        try {
            outcome = PostInitialImport.run(harness, mp.drivers(), copy.toFile());
        } finally {
            if (manifest.recurring == Manifest.Recurring.HELD) {
                TaskSchedules.holdRegularRuns();
            } else {
                // held since the initial import, as midPoint's scheduler starts after the post-initial import
                TaskSchedules.releaseRegularRuns();
            }
            TaskSchedules.release(scheduler);
            runtime.login(TesterRuntime.ADMINISTRATOR);
        }
        for (java.io.File file : outcome.done) {
            importOrder.add(relative(copy, file.toPath()));
        }
        warnings.addAll(outcome.warnings);
        if (outcome.failedFile != null) {
            throw importFailure(copy, sources, outcome);
        }
        mp.awaitBackgroundWork("the post-initial import of " + manifest.file);
        for (Map.Entry<String, String> resource : resourceOids.entrySet()) {
            mp.query("test resource " + resource.getKey(), (task, result) -> {
                harness.checkResource(resource.getValue(), task, result);
                return null;
            });
        }
        mp.awaitBackgroundWork("the post-initial import of " + manifest.file);
        initial = runtime.initialImport();
        NamesCheck.check(this, mp, LineSupport.LINE);
        runtime.takeJournal();
        runtime.clearNotifications();
        state = runtime.snapshot(manifest.recurring == Manifest.Recurring.HELD);
        writeReport();
    }

    // ---- the cache --------------------------------------------------------------------------------------------

    /** Writes this deployment, as just loaded, to a cache entry ({@link DeploymentCache}). */
    private void writeCache(Path dir) throws IOException {
        runtime.dumpDatabase(dir.resolve("db"));
        java.util.HashMap<String, Object> meta = DeploymentCache.map();
        meta.put("line", LineSupport.LINE);
        meta.put("importOrder", new ArrayList<>(importOrder));
        meta.put("warnings", new ArrayList<>(warnings));
        meta.put("resourceOids", new LinkedHashMap<>(resourceOids));
        if (initial != null) {
            meta.put("initial.imported", new ArrayList<>(initial.imported));
            meta.put("initial.replaced", new ArrayList<>(initial.replaced));
            meta.put("initial.skipped", new ArrayList<>(initial.skipped));
            meta.put("initial.failed", new ArrayList<>(initial.failed));
        }
        DeploymentCache.writeObject(dir.resolve("meta.ser"), meta);
        java.util.HashMap<String, java.io.Serializable> targets = new java.util.HashMap<>();
        for (Map.Entry<String, String> resource : resourceOids.entrySet()) {
            ManagedTarget target = runtime.findTarget(resource.getValue());
            if (target != null) {
                targets.put(resource.getValue(), target.persistentContent());
            }
        }
        DeploymentCache.writeObject(dir.resolve("targets.ser"), targets);
        DeploymentCache.writeObject(dir.resolve("files.ser"), FileArea.get().persistentFiles());
    }

    /**
     * Instead of the import: the files are prepared again (the stand-ins of this JVM: ports, paths), then the cached
     * database, target systems and files are restored with the reset's machinery, the values that differ in this JVM
     * (the connector configuration of the stand-ins, the mail redirection) are written over the restored ones, and
     * the generated names are checked; then the snapshot every test starts from.
     */
    @SuppressWarnings("unchecked")
    private void restoreFrom(DeploymentCache cache) throws IOException {
        long t = Timing.start();
        cache.verify();
        Map<String, Object> meta = (Map<String, Object>) DeploymentCache.readObject(cache.entry.resolve("meta.ser"));
        Map<String, java.io.Serializable> targets = (Map<String, java.io.Serializable>) DeploymentCache.readObject(
                cache.entry.resolve("targets.ser"));
        Map<String, byte[]> files = (Map<String, byte[]>) DeploymentCache.readObject(cache.entry.resolve("files.ser"));
        if (!LineSupport.LINE.equals(meta.get("line"))) {
            throw new IOException("the entry is of midPoint " + meta.get("line"));
        }
        runtime.resetToBaseline();
        Path copy = workDir.resolve("post-initial-objects");
        deleteTree(workDir);
        Files.createDirectories(copy);
        for (String directory : manifest.mappedDirectories) {
            FileArea.get().map(directory);
        }
        loadingInitialData = false;
        prepare(sources(), copy);
        if (!resourceOids.equals(meta.get("resourceOids"))) {
            throw new IOException("the resources are not the cached ones: " + resourceOids + " / "
                    + meta.get("resourceOids"));
        }
        runtime.restoreDatabase(cache.entry.resolve("db"), manifest.recurring == Manifest.Recurring.HELD, () -> {
            for (Map.Entry<String, String> resource : resourceOids.entrySet()) {
                ManagedTarget target = runtime.findTarget(resource.getValue());
                if (target != null && targets.containsKey(resource.getValue())) {
                    target.restorePersistent(targets.get(resource.getValue()));
                }
            }
            FileArea.get().restorePersistentFiles(files);
            repointToThisJvm();
        });
        importOrder.addAll((List<String>) meta.get("importOrder"));
        warnings.addAll((List<String>) meta.get("warnings"));
        if (meta.containsKey("initial.imported")) {
            initial = new InitialImport.Outcome();
            initial.imported.addAll((List<String>) meta.get("initial.imported"));
            initial.replaced.addAll((List<String>) meta.get("initial.replaced"));
            initial.skipped.addAll((List<String>) meta.get("initial.skipped"));
            initial.failed.addAll((List<String>) meta.get("initial.failed"));
        }
        restoredFrom = cache.entry;
        cacheEntry = cache.entry;
        NamesCheck.check(this, mp, LineSupport.LINE);
        runtime.takeJournal();
        runtime.clearNotifications();
        state = runtime.snapshot(manifest.recurring == Manifest.Recurring.HELD);
        writeReport();
        Timing.end("cache.restore.total", t);
    }

    /**
     * The values of the restored database that are this JVM's own: the connector configuration of every stand-in
     * (the LDAP server's port, the CSV file's path) and the tester's SMTP server in the system configuration.
     */
    private void repointToThisJvm() {
        for (Map.Entry<String, String> resource : resourceOids.entrySet()) {
            Element standIn = Xml.child(Xml.parse(standIns.get(resource.getKey())).getDocumentElement(),
                    "connectorConfiguration");
            mp.patchStored(com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType.class,
                    resource.getValue(), xml -> {
                        Document doc = Xml.parse(xml);
                        Element current = Xml.child(doc.getDocumentElement(), "connectorConfiguration");
                        if (current == null || standIn == null) {
                            return xml;
                        }
                        doc.getDocumentElement().replaceChild(doc.importNode(standIn, true), current);
                        return Xml.serialize(doc);
                    }, "connectorConfiguration");
        }
        if (manifest.mailRedirect || !systemConfigurationInDeployment) {
            mp.patchStored(com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType.class,
                    "00000000-0000-0000-0000-000000000001", mp::redirectMail, "notificationConfiguration",
                    "messageTransportConfiguration");
        }
    }

    /** Relative path ({@code /}-separated) to the file it comes from: the layered config dirs, then the overlay. */
    private Map<String, Path> sources() {
        try {
            return manifest.sources(LineSupport.LINE);
        } catch (IllegalArgumentException e) {
            throw new DeploymentLoadException(manifest.file + ": " + e.getMessage(), e);
        }
    }

    /** Writes the copy: properties, resources moved to the tester's target systems, mail redirected; then the initial data. */
    private void prepare(Map<String, Path> sources, Path copy) {
        List<String> unknown = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> targetsUsed = new LinkedHashSet<>();
        for (Map.Entry<String, Path> entry : sources.entrySet()) {
            String relative = entry.getKey();
            Path source = entry.getValue();
            Path destination = copy.resolve(relative);
            try {
                Files.createDirectories(destination.getParent());
                if (!relative.endsWith(".xml")) {
                    Files.copy(source, destination);
                    continue;
                }
                String text = Files.readString(source, StandardCharsets.UTF_8);
                text = substitute(text, relative, unknown);
                if (!manifest.httpRedirect.isEmpty()) {
                    text = HttpStandIn.get().redirect(text, manifest.httpRedirect);
                }
                if (SYSTEM_CONFIGURATION.matcher(text).find()) {
                    systemConfigurationInDeployment = true;
                }
                text = retargetResources(text, relative, targetsUsed, problems);
                if (manifest.mailRedirect) {
                    text = mp.redirectMail(text);
                }
                int encrypted = count(text, "encryptedData>");
                if (encrypted > 0) {
                    warnings.add(relative + ": " + encrypted / 2 + " encrypted value(s) left as deployed (this midPoint"
                            + " has not the production key: replace them by an overlay or a property)");
                }
                Files.writeString(destination, text, StandardCharsets.UTF_8);
                ConfigIndex.get().register(source, slashed(source), false);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot prepare " + relative, e);
            }
        }
        if (!unknown.isEmpty()) {
            problems.add(manifest.file + ": properties not in config.properties: " + String.join(", ", unknown));
        }
        for (Manifest.Target target : manifest.targets.values()) {
            if (!targetsUsed.contains(target.resource)) {
                problems.add(manifest.file + ": targets." + target.resource
                        + ": no resource of that name in the config directories (resources: " + resourceOids.keySet()
                        + ")");
            }
        }
        if (problems.size() == 1) {
            throw new DeploymentLoadException(problems.get(0));
        }
        if (!problems.isEmpty()) {
            throw new DeploymentLoadException(problems.size() + " problems with the deployment:\n- "
                    + String.join("\n- ", problems));
        }
        for (Manifest.Target target : manifest.targets.values()) {
            if (target.initialData != null && loadingInitialData) {
                loadInitialData(target);
            }
        }
    }

    private String substitute(String text, String relative, List<String> unknown) {
        for (Map.Entry<String, String> r : manifest.replace.entrySet()) {
            text = text.replace(r.getKey(), r.getValue());
        }
        if (!text.contains("{{")) {
            return text;
        }
        Matcher m = PROPERTY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = manifest.properties.get(m.group(1));
            if (value == null) {
                int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                unknown.add("{{" + m.group(1) + "}} at " + relative + ":" + line);
                value = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Every {@code <resource>} of the file (the root, or a child of {@code <objects>}) moved to its target. */
    private String retargetResources(String text, String relative, Set<String> targetsUsed, List<String> problems) {
        if (!text.contains("resource")) {
            return text;
        }
        Document doc;
        try {
            doc = Xml.parse(text);
        } catch (IllegalArgumentException e) {
            // midPoint's import reports it
            return text;
        }
        Element root = doc.getDocumentElement();
        List<Element> resources = new ArrayList<>();
        if ("resource".equals(Xml.objectElementName(root))) {
            resources.add(root);
        } else {
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element && "resource".equals(Xml.objectElementName((Element) n))) {
                    resources.add((Element) n);
                }
            }
        }
        if (resources.isEmpty()) {
            return text;
        }
        int index = 0;
        for (Element resource : resources) {
            String name = childText(resource, "name");
            if (name == null) {
                throw new DeploymentLoadException(relative + ": a resource without a name");
            }
            name = name.trim();
            Manifest.Target target = manifest.targets.get(name);
            ResourceOptions options = target != null ? target.options : ResourceOptions.defaults();
            String fileName = relative.replace('/', '_') + (resources.size() > 1 ? "-" + (++index) : "");
            MidPointImpl.Retargeted retargeted;
            try {
                retargeted = mp.retarget(standalone(resource), fileName, options);
            } catch (IllegalArgumentException e) {
                // reported with the others after every file (a deployment often has several such resources)
                problems.add(relative + ": resource " + name + " cannot be moved to the tester's "
                        + (target != null ? target.connector : "mirror") + " target system: " + e.getMessage()
                        + (target == null ? " (targets." + name + " in the manifest says what stands in for it)" : ""));
                targetsUsed.add(name);
                continue;
            }
            resourceOids.put(name, retargeted.oid);
            standIns.put(name, retargeted.xml);
            targetsUsed.add(name);
            Document transformed = Xml.parse(retargeted.xml);
            associations.put(name, associations(transformed.getDocumentElement()));
            Node imported = doc.importNode(transformed.getDocumentElement(), true);
            resource.getParentNode().replaceChild(imported, resource);
        }
        return Xml.serialize(doc);
    }

    /** The element as a document of its own, with the namespace declarations of its ancestors. */
    private static String standalone(Element element) {
        Document doc;
        try {
            doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        Element copy = (Element) doc.importNode(element, true);
        doc.appendChild(copy);
        for (Node n = element.getParentNode(); n instanceof Element; n = n.getParentNode()) {
            NamedNodeMap attributes = n.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Attr attribute = (Attr) attributes.item(i);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                        && !copy.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getLocalName())) {
                    copy.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getName(), attribute.getValue());
                }
            }
        }
        return Xml.serialize(doc);
    }

    private static List<Association> associations(Element resource) {
        List<Association> out = new ArrayList<>();
        for (Element type : Xml.descendants(resource, "objectType")) {
            String kind = childText(type, "kind");
            if (kind != null && !"account".equals(kind.trim())) {
                continue;
            }
            for (Element association : Xml.children(type, "association")) {
                Element attribute = Xml.child(association, "associationAttribute");
                Element value = Xml.child(association, "valueAttribute");
                String direction = childText(association, "direction");
                if (attribute != null && value != null && direction != null) {
                    out.add(new Association(direction.trim(), Xml.qname(attribute).getLocalPart(),
                            Xml.qname(value).getLocalPart()));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private void loadInitialData(Manifest.Target target) {
        ManagedTarget system = runtime.findTarget(resourceOids.get(target.resource));
        try {
            if (system instanceof CsvFile) {
                ((CsvFile) system).writeText(Files.readString(target.initialData, StandardCharsets.UTF_8));
            } else if (system instanceof LdapDirectory) {
                ((LdapDirectory) system).loadLdif(target.initialData.toFile());
            } else {
                throw new DeploymentLoadException("targets." + target.resource + ".initialData: not supported for "
                        + target.connector);
            }
        } catch (IOException | RuntimeException | AssertionError e) {
            if (e instanceof DeploymentLoadException) {
                throw (DeploymentLoadException) e;
            }
            throw new DeploymentLoadException("targets." + target.resource + ".initialData " + target.initialData
                    + " cannot be loaded: " + e.getMessage(), e);
        }
    }

    private DeploymentLoadException importFailure(Path copy, Map<String, Path> sources,
            PostInitialImport.Outcome outcome) {
        String relative = relative(copy, outcome.failedFile.toPath());
        Path failureFile = workDir.resolve("load-failure.txt");
        try {
            Files.writeString(failureFile, "Post-initial import of " + relative + " (" + sources.get(relative)
                    + ") failed on midPoint " + LineSupport.LINE + "\n\n" + outcome.failedResult.debugDump() + "\n"
                    + (outcome.failure != null ? stackTrace(outcome.failure) : ""), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // diagnostics only
        }
        StringBuilder message = new StringBuilder("midPoint ").append(LineSupport.LINE)
                .append(": the post-initial import failed on ").append(relative).append(" (")
                .append(sources.get(relative)).append(") after ").append(outcome.done.size())
                .append(" file(s)");
        if (outcome.failure != null) {
            message.append(": ").append(outcome.failure.getMessage());
        }
        message.append("\n").append(PostInitialImport.errors(outcome.failedResult))
                .append("Full operation result: ").append(failureFile)
                .append("\nPrepared copy (as imported): ").append(copy.resolve(relative));
        return new DeploymentLoadException(message.toString(), outcome.failure);
    }

    private void writeReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Deployment ").append(manifest.file).append(" on midPoint ").append(LineSupport.LINE).append("\n\n");
        if (restoredFrom != null) {
            sb.append("Restored from the deployment cache: ").append(restoredFrom).append(" (the load below is the"
                    + " cached one's)\n\n");
        }
        if (initial != null) {
            sb.append("Initial objects of midPoint ").append(LineSupport.LINE).append(" (initial import, before the"
                    + " post-initial import):\n");
            sb.append("  imported: ").append(initial.imported.size()).append('\n');
            sb.append("  replaced the tester's startup objects: ").append(initial.replaced).append('\n');
            sb.append("  skipped: ").append(initial.skipped).append('\n');
            sb.append("  failed: ").append(initial.failed).append("\n\n");
        }
        sb.append("Import order (post-initial import):\n");
        importOrder.forEach(f -> sb.append("  ").append(f).append('\n'));
        sb.append("\nResources:\n");
        resourceOids.forEach((name, oid) -> {
            Manifest.Target target = manifest.targets.get(name);
            sb.append("  ").append(name).append(" (").append(oid).append("): ")
                    .append(target != null ? target.connector : "mirror").append('\n');
        });
        sb.append("\nRecurring tasks: ").append(manifest.recurring.name().toLowerCase(java.util.Locale.ROOT))
                .append('\n');
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings:\n");
            warnings.forEach(w -> sb.append("  ").append(w).append('\n'));
        }
        try {
            Files.writeString(workDir.resolve("load-report.txt"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // diagnostics only
        }
        warnings.forEach(w -> System.err.println("Tester: " + w));
    }

    // ---- use ------------------------------------------------------------------------------------------------

    /**
     * Back to the deployment as loaded: database, target systems, mapped files, the clock at the real time, the
     * recurring tasks as the manifest says; logged in as the administrator, no mails or notifications.
     */
    public void reset() {
        runtime.reset(state);
        runtime.takeJournal();
        runtime.clearNotifications();
        mp.step(null);
    }

    /**
     * Loads the manifest's deployment again in this JVM (the cache's tests): from the cache when it has the entry,
     * else afresh. The deployment loaded before is forgotten.
     */
    public static synchronized Deployment reload(Path manifestFile) {
        LOADED.remove(manifestFile.toAbsolutePath().normalize());
        return load(manifestFile);
    }

    /** The cache entry this deployment was restored from; null when it was loaded afresh. */
    public Path restoredFromCache() {
        return restoredFrom;
    }

    /** The cache entry written after the load or restored from; null when the cache is off or could not write. */
    public Path cacheEntry() {
        return cacheEntry;
    }

    public Manifest manifest() {
        return manifest;
    }

    /** The PoC API on the same midPoint. */
    public MidPoint api() {
        return mp;
    }

    /** The prepared copy the post-initial import ran on. */
    public Path prepared() {
        return workDir.resolve("post-initial-objects");
    }

    /** The files of the copy (relative paths), in the order the post-initial import took them. */
    public List<String> importOrder() {
        return Collections.unmodifiableList(importOrder);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** The names of the deployment's resources. */
    public java.util.Set<String> resources() {
        return Collections.unmodifiableSet(resourceOids.keySet());
    }

    /**
     * The local name of the attribute that names the accounts of the resource's system ({@code dn}, the CSV's name
     * column, the mirror's naming attribute); null for no such resource, or one without accounts.
     */
    public String namingAttribute(String resource) {
        String oid = resourceOids.get(resource);
        ManagedTarget target = oid != null ? runtime.findTarget(oid) : null;
        if (target == null) {
            return null;
        }
        try {
            return target.accountNamingAttribute().getLocalPart();
        } catch (IllegalStateException e) {
            // no account object type (e.g. only entitlements)
            return null;
        }
    }

    /** The OID of the deployment's resource of this name, or null. */
    public String resourceOid(String name) {
        return resourceOids.get(name);
    }

    /**
     * The name of the user whose link is the resource's live shadow of this name (compared ignoring case, as a DN may
     * be); null when there is none.
     */
    public String owner(String resourceOid, String accountName) {
        return mp.query("owner of " + accountName, (task, result) -> {
            com.evolveum.midpoint.repo.api.RepositoryService repository =
                    mp.bean(com.evolveum.midpoint.repo.api.RepositoryService.class);
            com.evolveum.midpoint.prism.query.ObjectQuery shadows = mp.prismContext()
                    .queryFor(com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType.class)
                    .item(com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType.F_RESOURCE_REF)
                    .ref(resourceOid).build();
            for (com.evolveum.midpoint.prism.PrismObject<com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType> shadow
                    : repository.searchObjects(com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType.class,
                            shadows, null, result)) {
                if (shadow.getName() == null || !accountName.equalsIgnoreCase(shadow.getName().getOrig())
                        || Boolean.TRUE.equals(shadow.asObjectable().isDead())) {
                    continue;
                }
                com.evolveum.midpoint.prism.query.ObjectQuery owners = mp.prismContext()
                        .queryFor(com.evolveum.midpoint.xml.ns._public.common.common_3.UserType.class)
                        .item(com.evolveum.midpoint.xml.ns._public.common.common_3.UserType.F_LINK_REF)
                        .ref(shadow.getOid()).build();
                for (com.evolveum.midpoint.prism.PrismObject<com.evolveum.midpoint.xml.ns._public.common.common_3.UserType> user
                        : repository.searchObjects(com.evolveum.midpoint.xml.ns._public.common.common_3.UserType.class,
                                owners, null, result)) {
                    return user.getName().getOrig();
                }
            }
            return null;
        });
    }

    /** The associations of the resource's account object types (empty if none or unknown). */
    public List<Association> associations(String resource) {
        return associations.getOrDefault(resource, List.of());
    }

    /** What midPoint's initial import of the line's initial objects did before this deployment (shared by all). */
    public List<String> initialObjectsFailed() {
        return initial == null ? List.of() : Collections.unmodifiableList(initial.failed);
    }

    /** The initial objects skipped, by file name with the reason. */
    public List<String> initialObjectsSkipped() {
        return initial == null ? List.of() : Collections.unmodifiableList(initial.skipped);
    }

    /** The initial objects imported (or replacing the tester's startup objects), by file name. */
    public List<String> initialObjectsImported() {
        List<String> out = new ArrayList<>();
        if (initial != null) {
            out.addAll(initial.replaced);
            out.addAll(initial.imported);
        }
        return out;
    }

    /** The driver calls' runs since the last call (see {@link TesterRuntime#takeJournal()}). */
    public List<Run> takeRuns() {
        return runtime.takeJournal();
    }

    /** midPoint's current time. */
    public Instant now() {
        return Instant.ofEpochMilli(clock().currentTimeMillis());
    }

    /**
     * Moves midPoint's clock (and the JVM's wall clock, see {@code Time}) forward to the instant; holds the regular runs
     * of recurring tasks until the reset, as {@code Time.advance} does. Fails if the instant is not in the future.
     */
    public void advanceTo(Instant instant) {
        Clock clock = clock();
        long offset = instant.toEpochMilli() - clock.currentTimeMillis();
        if (offset <= 0) {
            throw new IllegalArgumentException("Cannot move the clock back: " + instant + " is not after "
                    + Instant.ofEpochMilli(clock.currentTimeMillis()));
        }
        TaskSchedules.holdRegularRuns();
        clock.overrideDuration(Long.valueOf(offset));
        TimeAgent.sync(clock);
    }

    /**
     * Stops midPoint's clock (and the JVM's wall clock, see {@code Time}) at the instant, to the millisecond: it says
     * that instant until the reset; {@link #advanceTo} and {@code Time.advance} move the stopped clock. Holds the
     * regular runs of recurring tasks as {@link #advanceTo} does. Fails if the instant is not in the future.
     */
    public void stopAt(Instant instant) {
        Clock clock = clock();
        if (!instant.isAfter(now())) {
            throw new IllegalArgumentException("Cannot move the clock back: " + instant + " is not after " + now());
        }
        TaskSchedules.holdRegularRuns();
        clock.override(instant.toEpochMilli());
        clock.overrideOffset(null);
        TimeAgent.sync(clock);
    }

    private Clock clock() {
        return mp.harness.bean(Clock.class);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static String id(Path manifest) {
        Path parent = manifest.getParent();
        String name = parent != null && parent.getFileName() != null ? parent.getFileName().toString() : "deployment";
        return name.replaceAll("[^A-Za-z0-9_.-]", "_") + "-"
                + Integer.toHexString(manifest.toString().hashCode());
    }

    private static String slashed(Path path) {
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    private static String relative(Path root, Path file) {
        return slashed(root.relativize(file));
    }

    private static String childText(Element parent, String localName) {
        Element e = Xml.child(parent, localName);
        return e == null || e.getTextContent().trim().isEmpty() ? null : e.getTextContent().trim();
    }

    private static int count(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + what.length())) {
            n++;
        }
        return n;
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }
}
