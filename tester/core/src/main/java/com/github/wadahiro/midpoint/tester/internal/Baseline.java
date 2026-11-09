package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Objects every runtime starts with (added to the repository directly, before the baseline snapshot): a bare system
 * configuration (tests import their own when they need more), the Superuser role and administrator, plus the objects
 * of the line ({@link LineSupport#BASELINE}: the archetypes cases, approvals, certification and reports need).
 */
final class Baseline {

    private static final String[] OBJECTS = {
            "/tester/baseline/010-system-configuration.xml",
            "/tester/baseline/020-role-superuser.xml",
            "/tester/baseline/030-user-administrator.xml"
    };

    private static final Pattern OID = Pattern.compile("\\boid=[\"']([^\"']+)[\"']");
    private static volatile Set<String> oids;

    private Baseline() {
    }

    /** The OIDs of the baseline objects (midPoint's own, not the tests' configuration). */
    static Set<String> oids() {
        if (oids == null) {
            Set<String> out = new HashSet<>();
            for (String xml : objects()) {
                Matcher m = OID.matcher(xml);
                if (m.find()) {
                    out.add(m.group(1));
                }
            }
            oids = Set.copyOf(out);
        }
        return oids;
    }

    static List<String> objects() {
        List<String> out = new ArrayList<>();
        for (String resource : OBJECTS) {
            out.add(read(resource));
        }
        for (String resource : LineSupport.BASELINE) {
            out.add(read(resource));
        }
        return out;
    }

    private static String read(String resource) {
        try (InputStream in = Baseline.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("Missing baseline object " + resource, e);
        }
    }

    /**
     * Uses the runtime's quiet logging unless the project configures logback itself. Call before midPoint starts. The
     * {@code logback.xml} of midPoint's own artifacts is not the project's: the GUI's (on the class path for scripts
     * that use its classes) would log to {@code midpoint.home_IS_UNDEFINED/log} in the project's directory.
     */
    static void defaultLogging() {
        ClassLoader cl = Baseline.class.getClassLoader();
        if (System.getProperty("logback.configurationFile") == null
                && !projectHas(cl, "logback-test.xml") && !projectHas(cl, "logback.xml")) {
            System.setProperty("logback.configurationFile", "tester/logback-tester.xml");
        }
    }

    private static boolean projectHas(ClassLoader cl, String resource) {
        try {
            for (java.util.Enumeration<java.net.URL> e = cl.getResources(resource); e.hasMoreElements(); ) {
                if (!e.nextElement().toString().contains("/com/evolveum/midpoint/")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return cl.getResource(resource) != null;
        }
    }
}
