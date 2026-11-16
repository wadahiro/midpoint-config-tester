package com.github.wadahiro.midpoint.configtester.provided;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.wadahiro.midpoint.tester.RealSystem;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryDirectoryServerSnapshot;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;

/**
 * A {@link RealSystem} of the LDAP connector: an UnboundID in-memory LDAP server the test starts itself, saved and
 * restored by the server's snapshots. An example of what a test provides for {@code connector: real} (a container
 * with a directory, a Samba 4 AD, would export and import its data instead); a test reads the entries itself
 * ({@link #of(String)}, {@link #entry(String)}).
 */
public class InMemoryLdapSystem implements RealSystem {

    static final String BASE = "dc=example,dc=com";
    static final String BIND_DN = "cn=tester";
    static final String BIND_PASSWORD = "tester";

    private static final Map<String, InMemoryLdapSystem> BY_RESOURCE = new ConcurrentHashMap<>();

    private InMemoryDirectoryServer server;

    public static InMemoryLdapSystem of(String resourceName) {
        return BY_RESOURCE.get(resourceName);
    }

    @Override
    public void start(String resourceOid, String resourceName) {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
            config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap",
                    java.net.InetAddress.getLoopbackAddress(), 0, null));
            config.setGenerateOperationalAttributes(true);
            server = new InMemoryDirectoryServer(config);
            server.add("dn: " + BASE, "objectClass: domain", "dc: example");
            server.startListening();
        } catch (LDAPException | com.unboundid.ldif.LDIFException e) {
            throw new IllegalStateException("Cannot start the LDAP server of " + resourceName, e);
        }
        BY_RESOURCE.put(resourceName, this);
    }

    /** Where the resource's LDAP connector reaches the server. */
    @Override
    public Map<String, String> configuration() {
        return Map.of("host", "127.0.0.1", "port", String.valueOf(server.getListenPort()), "connectionSecurity",
                "none", "bindDn", BIND_DN, "bindPassword", BIND_PASSWORD);
    }

    @Override
    public void loadInitialData(Path ldif) {
        try {
            server.importFromLDIF(false, ldif.toFile());
        } catch (LDAPException e) {
            throw new IllegalStateException("Cannot load " + ldif, e);
        }
    }

    /** The entry of this DN, or null. */
    public Entry entry(String dn) {
        try {
            return server.getEntry(dn);
        } catch (LDAPException e) {
            throw new IllegalStateException(e);
        }
    }

    public void delete(String dn) {
        try {
            server.delete(dn);
        } catch (LDAPException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Object capture() {
        return server.createSnapshot();
    }

    @Override
    public void restore(Object captured) {
        if (captured == null) {
            server.shutDown(true);
            return;
        }
        server.restoreSnapshot((InMemoryDirectoryServerSnapshot) captured);
    }

    @Override
    public String accountNamingAttribute() {
        return "dn";
    }
}
