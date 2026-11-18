package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a reset may keep midPoint's provisioning caches: the resources with their parsed schemas (the resource cache)
 * and the connector instances and types (the connector manager). The reset clears midPoint's caches, as the restored
 * rows may differ from what they hold; then every test parses the schemas of the resources and connectors it uses
 * again, and initializes their connector instances again. Those caches hold what the resource and connector rows
 * say: when the reset restores the state they were last known to match, and no resource or connector row was written
 * since, they still match it ({@link #keepAtReset}).
 */
final class ProvisioningCaches {

    /** The provisioning caches among those of midPoint's cache registry (4.4+), by their class's simple name. */
    private static final java.util.Set<String> PROVISIONING = java.util.Set.of("ResourceCache", "ConnectorManager");

    /** Whether a resource or connector row was written since the caches were last known to match a state. */
    private static volatile boolean changed = true;
    /** The state (a snapshot) the caches match while nothing changed ({@link #nowInLineWith}), or null. */
    private static volatile Object inLineWith;
    /** Whether the reset in progress keeps the provisioning caches ({@link #resetStarts}). */
    private static volatile boolean keepAtReset;

    private ProvisioningCaches() {
    }

    /** A resource or connector row was written: the next reset clears the caches. */
    static void changed() {
        changed = true;
    }

    /** The caches match the database, which is {@code state} now (a snapshot, the end of a reset); null: not known. */
    static void nowInLineWith(Object state) {
        inLineWith = state;
        changed = false;
    }

    /** At the start of a reset to {@code state}: whether the caches still match it, so the reset keeps them. */
    static boolean resetStarts(Object state) {
        keepAtReset = state != null && state == inLineWith && !changed;
        return keepAtReset;
    }

    /** Whether the reset in progress keeps the provisioning caches ({@link #resetStarts}). */
    static boolean keepAtReset() {
        return keepAtReset;
    }

    /**
     * Invalidates every cache of midPoint's cache registry but the provisioning ones: the caches are the registry's
     * list, which it has no accessor for ({@code caches} of {@code CacheRegistryImpl} 4.4 to 4.10, {@code listeners} of
     * {@code CacheInvalidationDispatcherImpl} 4.11). False when the list cannot be read or a cache not invalidated so
     * (another version of midPoint): the caller then invalidates them all.
     */
    static boolean invalidateAllButProvisioning(Object registry) {
        return invalidateAllButProvisioning(registry, "caches")
                || invalidateAllButProvisioning(registry, "listeners");
    }

    static boolean invalidateAllButProvisioning(Object registry, String listField) {
        if (registry == null) {
            return false;
        }
        try {
            java.lang.reflect.Field field = registry.getClass().getDeclaredField(listField);
            field.setAccessible(true);
            List<Object> caches;
            synchronized (registry) {
                caches = new ArrayList<>((List<?>) field.get(registry));
            }
            for (Object cache : caches) {
                if (isProvisioning(cache.getClass())) {
                    continue;
                }
                java.lang.reflect.Method invalidate = invalidate(cache.getClass());
                if (invalidate == null) {
                    return false;
                }
                invalidate.invoke(cache, null, null, null);
            }
            return true;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;
        }
    }

    private static boolean isProvisioning(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (PROVISIONING.contains(c.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** {@code invalidate(Class, String, CacheInvalidationContext)} of a cache. */
    private static java.lang.reflect.Method invalidate(Class<?> type) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            if ("invalidate".equals(m.getName()) && m.getParameterCount() == 3
                    && m.getParameterTypes()[0] == Class.class && m.getParameterTypes()[1] == String.class) {
                return m;
            }
        }
        return null;
    }
}
