package com.github.wadahiro.midpoint.tester;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Map;

/**
 * A real system a resource reaches through its real connector (experimental; manifest {@code connector: real},
 * {@code system: <class>}): one the test provides by some means, a server it starts itself or a container, and whose
 * data the tester saves and restores with midPoint's state through this interface. Every test then starts from the
 * system as the configuration was loaded, and a nested class's or a Kotest container's tests from the system as its
 * prefix left it.
 *
 * <p>The tester calls it on the test thread, with midPoint quiescent (no task running, the background work done):
 * {@link #start} once per JVM, before midPoint imports the resource; {@link #loadInitialData} once at the load (the
 * manifest's {@code initialData}); {@link #capture} right before the tester saves midPoint's database (after the load,
 * at a checkpoint); {@link #restore} after midPoint's database is restored and before any task may start (at every
 * reset). {@code restore} returns only when the system is back: midPoint reads it next.</p>
 *
 * <p>An implementation has a public constructor without arguments.</p>
 */
public interface RealSystem {

    /**
     * Starts the system, or connects to it, for the resource of this OID and name; called once per JVM, before
     * midPoint imports the resource.
     */
    default void start(String resourceOid, String resourceName) {
    }

    /**
     * The connector configuration properties (local names) to set over the resource's, after the manifest's
     * {@code properties}: where to reach this system ({@code host}, {@code port}), its credentials.
     */
    default Map<String, String> configuration() {
        return Map.of();
    }

    /** Loads the file the system starts with (the manifest's {@code initialData}), at the load. */
    default void loadInitialData(Path file) {
        throw new UnsupportedOperationException(getClass().getName() + " loads no initial data");
    }

    /** The system's data now, to give back to {@link #restore}. */
    Object capture();

    /** Back to the data {@link #capture} returned; null: the resource is no longer used (stop or disconnect). */
    void restore(Object captured);

    /**
     * The data as a serializable value for the deployment cache (another JVM restores it with
     * {@link #restorePersistent} after {@link #start}); null: the deployment is not cached.
     */
    default Serializable persistentContent() {
        return null;
    }

    default void restorePersistent(Serializable content) {
    }

    /** The system's accounts and objects for a test ({@code t.target(...)}); null: the test reads no data of it. */
    default TargetSystem view() {
        return null;
    }

    /** The local name of the attribute that names accounts ({@code dn}); null: ConnId's {@code icfs:name}. */
    default String accountNamingAttribute() {
        return null;
    }
}
