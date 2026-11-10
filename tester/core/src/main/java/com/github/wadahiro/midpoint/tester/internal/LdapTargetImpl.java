package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.tester.LdapDirectory;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryDirectoryServerSnapshot;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFChangeRecord;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.ldif.LDIFRecord;

/**
 * The in-memory LDAP server of a resource on the real LDAP connector (UnboundID LDAP SDK), as an {@link LdapDirectory}
 * and as a target system: objects are entries named by DN, accounts of the object class of the first account object
 * type, groups of the first other one. Attributes are the entry's user attributes (with {@code objectClass}); the
 * identifier is {@code entryUUID}. An object added here gets its object class and its superior classes, and the
 * attributes of its RDN.
 */
final class LdapTargetImpl implements ManagedTarget, LdapDirectory {

    static final String BIND_PASSWORD = "tester";
    private static final String DEFAULT_BIND_DN = "cn=tester";
    private static final int CHANGE_LOG_ENTRIES = 10_000;

    private final String resourceOid;
    private final SimulatedActivation simulatedActivation;
    private final String baseDn;
    private final String bindDn;
    private final List<String> accountClasses;
    private final List<String> otherClasses;
    private final InMemoryDirectoryServer server;
    private final Schema schema;
    /**
     * Whether the server listens: a reset to a state without this target shuts it down, a reset to a state with it
     * (a loaded deployment, after a test class that does not use it) listens again, on the same port.
     */
    private boolean listening;

    LdapTargetImpl(RealResourceXml resource, boolean referentialIntegrity) {
        this.resourceOid = resource.oid;
        this.simulatedActivation = resource.simulatedActivation;
        this.baseDn = resource.property("baseContext", null);
        if (baseDn == null) {
            throw new IllegalArgumentException(resource.name + ": the LDAP connector needs baseContext");
        }
        this.bindDn = resource.property("bindDn", DEFAULT_BIND_DN);
        this.accountClasses = resource.accountClasses;
        this.otherClasses = resource.otherClasses;
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(baseDn);
            config.addAdditionalBindCredentials(bindDn, BIND_PASSWORD);
            // a fixed port: the resource definition names it, also when the server listens again after a shutdown
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("tester",
                    InetAddress.getLoopbackAddress(), freePort(), null));
            schema = Schema.getDefaultStandardSchema();
            config.setSchema(schema);
            // a change log (cn=changelog, the root DSE's changeLog and change numbers): the LDAP connector's live
            // synchronization reads it (synchronizationStrategy auto or sunChangeLog)
            config.setMaxChangeLogEntries(CHANGE_LOG_ENTRIES);
            if (referentialIntegrity && !resource.associationAttributes.isEmpty()) {
                config.setReferentialIntegrityAttributes(resource.associationAttributes);
            }
            server = new InMemoryDirectoryServer(config);
            server.startListening();
            listening = true;
            server.add(baseEntry(new DN(baseDn)));
        } catch (LDAPException e) {
            throw new IllegalStateException("Cannot start the LDAP server of " + resource.name + ": "
                    + e.getMessage(), e);
        }
    }

    /** The values the resource definition gets: the server's address, no TLS, the tester's bind password. */
    Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("host", host());
        out.put("port", String.valueOf(port()));
        out.put("connectionSecurity", "none");
        out.put("bindDn", bindDn);
        return out;
    }

    private static Entry baseEntry(DN dn) {
        RDN rdn = dn.getRDN();
        Entry entry = new Entry(dn);
        String attribute = rdn.getAttributeNames()[0].toLowerCase();
        String objectClass;
        switch (attribute) {
            case "dc":
                objectClass = "domain";
                break;
            case "o":
                objectClass = "organization";
                break;
            case "ou":
                objectClass = "organizationalUnit";
                break;
            case "c":
                objectClass = "country";
                break;
            default:
                objectClass = "extensibleObject";
        }
        entry.addAttribute("objectClass", "top", objectClass);
        for (int i = 0; i < rdn.getAttributeNames().length; i++) {
            entry.addAttribute(rdn.getAttributeNames()[i], rdn.getAttributeValues()[i]);
        }
        return entry;
    }

    // ---- LdapDirectory ------------------------------------------------------------------------------------------

    @Override
    public String host() {
        return server.getListenAddress().getHostAddress();
    }

    @Override
    public int port() {
        return server.getListenPort();
    }

    @Override
    public String baseDn() {
        return baseDn;
    }

    @Override
    public String bindDn() {
        return bindDn;
    }

    @Override
    public String bindPassword() {
        return BIND_PASSWORD;
    }

    @Override
    public void loadLdif(String ldif) {
        try (LDIFReader reader = new LDIFReader(new java.io.BufferedReader(new StringReader(ldif)))) {
            LDIFRecord record;
            while ((record = reader.readLDIFRecord()) != null) {
                if (record instanceof LDIFChangeRecord) {
                    ((LDIFChangeRecord) record).processChange(server);
                } else {
                    server.add((Entry) record);
                }
            }
        } catch (IOException | LDIFException e) {
            throw new IllegalArgumentException("Cannot read LDIF: " + e.getMessage(), e);
        } catch (LDAPException e) {
            throw new AssertionError("LDIF not applied: " + e.getMessage(), e);
        }
    }

    @Override
    public void loadLdif(File file) {
        try {
            loadLdif(Files.readString(file.toPath()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, List<String>> entry(String dn) {
        Entry entry = get(dn);
        if (entry == null) {
            return null;
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Attribute attribute : entry.getAttributes()) {
            out.put(attribute.getName(), List.of(attribute.getValues()));
        }
        return out;
    }

    @Override
    public List<String> search(String filter) {
        List<String> out = new ArrayList<>();
        try {
            for (SearchResultEntry entry : server.search(baseDn, SearchScope.SUB, filter).getSearchEntries()) {
                out.add(entry.getDN());
            }
        } catch (LDAPException e) {
            throw new AssertionError("Search " + filter + " failed: " + e.getMessage(), e);
        }
        out.sort(null);
        return out;
    }

    @Override
    public String ldif() {
        StringBuilder sb = new StringBuilder();
        try {
            for (SearchResultEntry entry : server.search(baseDn, SearchScope.SUB, "(objectClass=*)", "*", "+")
                    .getSearchEntries()) {
                sb.append(entry.toLDIFString()).append('\n');
            }
        } catch (LDAPException e) {
            throw new AssertionError(e.getMessage(), e);
        }
        return sb.toString();
    }

    // ---- TargetSystem -------------------------------------------------------------------------------------------

    @Override
    public String resourceOid() {
        return resourceOid;
    }

    @Override
    public QName accountNamingAttribute() {
        return new QName(Xml.NS_RI, "dn");
    }

    @Override
    public void addAccount(String name, Map<String, ?> attributes) {
        addObject(accountClass(), name, attributes);
    }

    @Override
    public void modifyAccount(String name, String attribute, Object... values) {
        modifyObject(accountClass(), name, attribute, values);
    }

    @Override
    public void renameAccount(String name, String newName) {
        renameObject(accountClass(), name, newName);
    }

    @Override
    public void deleteAccount(String name) {
        deleteObject(accountClass(), name);
    }

    @Override
    public Map<String, List<Object>> account(String name) {
        return find(accountClass(), name);
    }

    @Override
    public Object attribute(String account, String attribute) {
        List<Object> values = attributeValues(account, attribute);
        if (values.size() > 1) {
            throw new AssertionError("Attribute " + attribute + " of " + account + " has several values: " + values);
        }
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public List<Object> attributeValues(String account, String attribute) {
        return values(account, attribute);
    }

    @Override
    public List<String> accountNames() {
        return objectNames(accountClass());
    }

    @Override
    public boolean enabled(String account) {
        if (simulatedActivation == null) {
            throw new IllegalStateException("An LDAP entry has no standard activation (the LDAP connector has no"
                    + " native one) and resource " + resourceOid + " simulates none; read its attributes instead");
        }
        return simulatedActivation.enabled(this, account);
    }

    @Override
    public void setEnabled(String account, boolean enabled) {
        if (simulatedActivation == null) {
            throw new IllegalStateException("An LDAP entry has no standard activation (the LDAP connector has no"
                    + " native one) and resource " + resourceOid + " simulates none; modify its attributes instead");
        }
        simulatedActivation.setEnabled(this, account, enabled);
    }

    @Override
    public String password(String account) {
        List<Object> values = values(account, "userPassword");
        return values.isEmpty() ? null : String.valueOf(values.get(0));
    }

    @Override
    public void setPassword(String account, String password) {
        existing(account);
        try {
            server.modify(account, password == null ? new Modification(ModificationType.DELETE, "userPassword")
                    : new Modification(ModificationType.REPLACE, "userPassword", password));
        } catch (LDAPException e) {
            throw new AssertionError("Cannot set the password of " + account + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void addGroup(String name, Map<String, ?> attributes) {
        addObject(groupClass(), name, attributes);
    }

    @Override
    public void modifyGroup(String name, String attribute, Object... values) {
        modifyObject(groupClass(), name, attribute, values);
    }

    @Override
    public void deleteGroup(String name) {
        deleteObject(groupClass(), name);
    }

    @Override
    public Map<String, List<Object>> group(String name) {
        return find(groupClass(), name);
    }

    @Override
    public List<Object> groupValues(String group, String attribute) {
        return values(group, attribute);
    }

    @Override
    public List<String> groupNames() {
        return objectNames(groupClass());
    }

    @Override
    public void addObject(String objectClass, String name, Map<String, ?> attributes) {
        try {
            DN dn = new DN(name);
            Entry entry = new Entry(dn);
            Set<String> classes = new LinkedHashSet<>();
            classes.add(objectClass);
            ObjectClassDefinition definition = schema.getObjectClass(objectClass);
            if (definition != null) {
                for (ObjectClassDefinition superior : definition.getSuperiorClasses(schema, true)) {
                    classes.add(superior.getNameOrOID());
                }
            }
            entry.addAttribute("objectClass", classes.toArray(new String[0]));
            RDN rdn = dn.getRDN();
            for (int i = 0; i < rdn.getAttributeNames().length; i++) {
                entry.addAttribute(rdn.getAttributeNames()[i], rdn.getAttributeValues()[i]);
            }
            attributes.forEach((attribute, value) -> {
                for (String v : strings(value)) {
                    entry.addAttribute(attribute, v);
                }
            });
            server.add(entry);
        } catch (LDAPException e) {
            throw new AssertionError("Cannot add " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void modifyObject(String objectClass, String name, String attribute, Object... values) {
        existing(name);
        try {
            server.modify(name, new Modification(ModificationType.REPLACE, attribute,
                    strings(List.of(values)).toArray(new String[0])));
        } catch (LDAPException e) {
            throw new AssertionError("Cannot modify " + attribute + " of " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void renameObject(String objectClass, String name, String newName) {
        existing(name);
        try {
            DN newDn = new DN(newName);
            DN parent = newDn.getParent();
            server.modifyDN(name, newDn.getRDNString(), true, parent != null ? parent.toString() : null);
        } catch (LDAPException e) {
            throw new AssertionError("Cannot rename " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteObject(String objectClass, String name) {
        existing(name);
        try {
            server.delete(name);
        } catch (LDAPException e) {
            throw new AssertionError("Cannot delete " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, List<Object>> find(String objectClass, String name) {
        Entry entry = get(name);
        if (entry == null) {
            return null;
        }
        Map<String, List<Object>> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Attribute attribute : entry.getAttributes()) {
            if (!attribute.getBaseName().equalsIgnoreCase("entryUUID")) {
                out.put(attribute.getName(), new ArrayList<>(List.of((Object[]) attribute.getValues())));
            }
        }
        return out;
    }

    @Override
    public String identifier(String objectClass, String name) {
        return existing(name).getAttributeValue("entryUUID");
    }

    @Override
    public List<String> objectNames(String objectClass) {
        return search("(objectClass=" + objectClass + ")");
    }

    // ---- snapshots ----------------------------------------------------------------------------------------------

    @Override
    public Object capture() {
        return server.createSnapshot();
    }

    @Override
    public synchronized void restore(Object captured) {
        if (captured == null) {
            server.shutDown(true);
            listening = false;
            return;
        }
        if (!listening) {
            try {
                server.startListening();
            } catch (LDAPException e) {
                throw new IllegalStateException("Cannot restart the LDAP server of " + resourceOid + ": "
                        + e.getMessage(), e);
            }
            listening = true;
        }
        server.restoreSnapshot((InMemoryDirectoryServerSnapshot) captured);
    }

    @Override
    public java.io.Serializable persistentContent() {
        return server.createSnapshot();
    }

    @Override
    public void restorePersistent(java.io.Serializable content) {
        restore(content);
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No free port for the LDAP server", e);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Entry get(String dn) {
        try {
            return server.getEntry(dn, "*", "entryUUID");
        } catch (LDAPException e) {
            throw new AssertionError("Cannot read " + dn + ": " + e.getMessage(), e);
        }
    }

    private Entry existing(String dn) {
        Entry entry = get(dn);
        if (entry == null) {
            throw new AssertionError("No entry " + dn + " on the LDAP server of " + resourceOid);
        }
        return entry;
    }

    private List<Object> values(String dn, String attribute) {
        String[] values = existing(dn).getAttributeValues(attribute);
        return values == null ? new ArrayList<>() : new ArrayList<>(List.of((Object[]) values));
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?>) {
            ((Collection<?>) value).forEach(v -> out.add(String.valueOf(v)));
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    private String accountClass() {
        if (accountClasses.isEmpty()) {
            throw new IllegalStateException("Resource " + resourceOid + " has no account object type");
        }
        return accountClasses.get(0);
    }

    private String groupClass() {
        if (otherClasses.isEmpty()) {
            throw new IllegalStateException("Resource " + resourceOid + " has no non-account object type");
        }
        return otherClasses.get(0);
    }
}
