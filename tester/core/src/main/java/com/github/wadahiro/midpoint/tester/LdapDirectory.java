package com.github.wadahiro.midpoint.tester;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * The in-memory LDAP server (UnboundID LDAP SDK) the real LDAP connector of a resource talks to
 * ({@link MidPoint#ldap(String)}, for a resource imported with {@link ResourceOptions#realConnector()}). It serves the
 * resource's {@code baseContext} with the SDK's standard LDAP schema; the base entry is created, everything below it
 * comes from the test. It checks entries against the schema as a directory server does (object classes, required
 * and allowed attributes, syntaxes) and generates operational attributes ({@code entryUUID}, timestamps). Resets
 * restore its content.
 */
public interface LdapDirectory {

    /** The loopback address and port the server listens on. */
    String host();

    int port();

    String baseDn();

    /** The resource's {@code bindDn}, accepted with {@link #bindPassword()}. */
    String bindDn();

    String bindPassword();

    /** Applies LDIF records: entries to add, or change records ({@code changetype: modify}, {@code delete}...). */
    void loadLdif(String ldif);

    void loadLdif(File file);

    /** The entry's attributes (user attributes, then {@code entryUUID}), attribute names as stored; null if none. */
    Map<String, List<String>> entry(String dn);

    /** The DNs of the entries under the base that match the filter, e.g. {@code (objectClass=groupOfNames)}. */
    List<String> search(String filter);

    /** All entries as LDIF (for diagnostics). */
    String ldif();
}
