package com.github.wadahiro.midpoint.tester.internal;

import java.util.Map;

/**
 * The environment variables the deployment's scripts read ({@code System.getenv(name)} in Groovy), as the manifest's
 * {@code environment} gives them, over the process's: a value known only when the tester runs (a stand-in database's
 * URL) cannot be given to the forked JVM beforehand. Groovy's {@code System.getenv(String)} is redirected here
 * ({@link #installGroovy()}); Java code still reads the process's environment.
 */
public final class ScriptEnvironment {

    private static volatile Map<String, String> values = Map.of();

    private ScriptEnvironment() {
    }

    static void use(Map<String, String> environment) {
        values = Map.copyOf(environment);
    }

    /** The manifest's value, else the process's. Called by Groovy's {@code System.getenv(String)}. */
    public static String get(String name) {
        String value = values.get(name);
        return value != null ? value : System.getenv().get(name);
    }

    /** Redirects Groovy's {@code System.getenv(String)}; null on success, else why not. */
    static String installGroovy() {
        try {
            Class<?> shell = Class.forName("groovy.lang.GroovyShell");
            shell.getMethod("evaluate", String.class).invoke(shell.getConstructor().newInstance(),
                    "System.metaClass.static.getenv = { String name -> "
                            + ScriptEnvironment.class.getName() + ".get(name) }");
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "Groovy's System.getenv is not redirected: " + e;
        }
    }
}
