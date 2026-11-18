package com.github.wadahiro.midpoint.tester.internal;

/**
 * Whether a reset may keep midPoint's provisioning caches: the resources with their parsed schemas (the resource cache)
 * and the connector instances and types (the connector manager). The reset clears midPoint's caches, as the restored
 * rows may differ from what they hold; then every test parses the schemas of the resources and connectors it uses
 * again, and initializes their connector instances again. Those caches hold what the resource and connector rows
 * say: when the reset restores the state they were last known to match, and no resource or connector row was written
 * since, they still match it ({@link #keepAtReset}).
 */
final class ProvisioningCaches {

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
}
