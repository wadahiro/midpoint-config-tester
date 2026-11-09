package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * midPoint's initial import ({@code InitialDataImport} of {@code gui/admin-gui}): the objects the line ships for its
 * first start (system configuration, administrator, Superuser, End user, Approver and Reviewer roles, archetypes,
 * default security and value policies, object collections, reports, dashboards, lookup tables, the cleanup, validity
 * and trigger tasks...), imported before the post-initial import as midPoint does on its first start.
 *
 * <ol>
 *   <li>The files: the line's resource pattern ({@link LineDrivers#initialObjectsPattern()}; 4.0 and 4.4 from the
 *       GUI module's jar, 4.8+ from {@code system-init}), ordered as the line does: by file name (4.4+), or by the
 *       number of the name's first three characters (4.0, {@code DataImport.sortFiles}, stable over the names).</li>
 *   <li>Each object: an object that exists is skipped (midPoint imports with {@code overwrite} false), except the
 *       tester's own startup objects of the same OID ({@link Baseline}: a bare system configuration, Superuser,
 *       administrator and copies of the line's archetypes and roles), which the line's version replaces, so the result
 *       is midPoint as the line initializes it. A missing one is added with {@code isImport}, full processing
 *       ({@link LineDrivers#importInitialObject}), in a task of the line's init channel.</li>
 *   <li>A file that fails is noted and the import goes on, as in midPoint.</li>
 * </ol>
 * Deliberate deviations: the import runs logged in as the administrator (midPoint: a fake privileged principal
 * {@code initAdmin}); 4.8's random initial password of the administrator is not generated (the tester logs users in
 * without passwords); the PolyString normalizer of a system configuration's {@code internals} is not applied; the
 * tester's stand-ins are applied to the text first (mail servers redirected).
 */
final class InitialImport {

    /** What the import did. */
    static final class Outcome {
        /** File names imported, those that replaced a tester's startup object, skipped, failed (with the reason). */
        final List<String> imported = new ArrayList<>();
        final List<String> replaced = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
    }

    private InitialImport() {
    }

    /** The line's initial object files, in the order the line imports them. */
    static List<Resource> files(LineDrivers drivers) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(InitialImport.class.getClassLoader())
                    .getResources(drivers.initialObjectsPattern());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list the initial objects " + drivers.initialObjectsPattern(), e);
        }
        List<Resource> files = new ArrayList<>();
        for (Resource resource : resources) {
            String name = resource.getFilename();
            if (name != null && !name.isEmpty() && !name.endsWith("/")) {
                files.add(resource);
            }
        }
        files.sort(Comparator.comparing(Resource::getFilename));
        if (drivers.initialObjectsSortedByNumber()) {
            // DataImport.sortFiles: a stable sort by number
            files.sort(Comparator.comparingInt(r -> number(r.getFilename())));
        }
        return files;
    }

    /**
     * The objects of the line's initial object files as {@code {type element, OID, name}} (the type element as in the
     * file, e.g. {@code role}; the name's original), read as plain XML: what the line ships, for the check of the
     * typed initial names ({@code InitialNamesTest}).
     */
    static List<String[]> objectsOfTheLine() {
        List<String[]> objects = new ArrayList<>();
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        for (Resource file : files(((MidPointImpl) TesterRuntime.get().api()).drivers())) {
            org.w3c.dom.Element root;
            try (InputStream in = file.getInputStream()) {
                root = factory.newDocumentBuilder().parse(in).getDocumentElement();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read the initial object " + file.getFilename(), e);
            }
            List<org.w3c.dom.Element> elements = new ArrayList<>();
            if ("objects".equals(root.getLocalName())) {
                for (org.w3c.dom.Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof org.w3c.dom.Element) {
                        elements.add((org.w3c.dom.Element) n);
                    }
                }
            } else {
                elements.add(root);
            }
            for (org.w3c.dom.Element object : elements) {
                String name = null;
                for (org.w3c.dom.Node n = object.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof org.w3c.dom.Element && "name".equals(n.getLocalName())) {
                        org.w3c.dom.NodeList orig = ((org.w3c.dom.Element) n).getElementsByTagNameNS("*", "orig");
                        name = (orig.getLength() > 0 ? orig.item(0).getTextContent() : n.getTextContent()).strip();
                        break;
                    }
                }
                objects.add(new String[] { object.getLocalName(), object.getAttribute("oid"), name });
            }
        }
        return objects;
    }

    /** {@code DataImport.getNumberFromName}. */
    private static int number(String name) {
        String number = name.length() > 3 ? name.substring(0, 3) : name;
        return number.matches("[\\d]+") ? Integer.parseInt(number) : 0;
    }

    /** Runs the import, logged in as the administrator; Quartz is the caller's business. */
    static Outcome run(Harness harness, LineDrivers drivers, UnaryOperator<String> standIns) {
        List<Resource> files = files(drivers);
        if (files.isEmpty()) {
            throw new IllegalStateException("No initial objects of midPoint " + LineSupport.LINE + " on the classpath ("
                    + drivers.initialObjectsPattern() + ")");
        }
        Outcome outcome = new Outcome();
        PrismContext prismContext = harness.bean(PrismContext.class);
        ModelService model = harness.bean(ModelService.class);
        Task task = harness.createTask("initial import (tester)");
        task.setChannel(drivers.postInitialChannel());
        OperationResult mainResult = task.getResult();
        for (Resource file : files) {
            String name = file.getFilename();
            OperationResult result = mainResult.createSubresult("com.evolveum.midpoint.init.DataImport.importObject");
            result.addParam("file", name);
            try {
                String text;
                try (InputStream in = file.getInputStream()) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                // XML, as midPoint reads the files (4.0 does not autodetect a text that starts with a comment)
                PrismObject<? extends ObjectType> object = prismContext.parserFor(standIns.apply(text))
                        .language(PrismContext.LANG_XML).parse();
                Class<? extends ObjectType> type = object.getCompileTimeClass();
                if (type == null) {
                    outcome.skipped.add(name + " (no static type)");
                    continue;
                }
                boolean exists = exists(model, type, object.getOid(), task);
                boolean replace = exists && Baseline.oids().contains(object.getOid());
                if (exists && !replace) {
                    outcome.skipped.add(name + " (exists)");
                    continue;
                }
                String skipped = harness.inOperationContext("initial import " + name, task,
                        () -> drivers.importInitialObject(object, replace, task, result));
                result.computeStatusIfUnknown();
                result.computeStatus();
                if (skipped != null) {
                    outcome.skipped.add(name + " (" + skipped + ")");
                } else if (result.isError()) {
                    outcome.failed.add(name + ": " + PostInitialImport.errors(result).trim());
                } else {
                    (replace ? outcome.replaced : outcome.imported).add(name);
                }
            } catch (Exception | AssertionError | LinkageError e) {
                outcome.failed.add(name + ": " + e);
            }
        }
        return outcome;
    }

    private static boolean exists(ModelService model, Class<? extends ObjectType> type, String oid, Task task)
            throws Exception {
        if (oid == null) {
            return false;
        }
        try {
            model.getObject(type, oid, SelectorOptions.createCollection(GetOperationOptions.createAllowNotFound()),
                    task, new OperationResult("exists"));
            return true;
        } catch (ObjectNotFoundException e) {
            return false;
        }
    }
}
