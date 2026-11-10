package com.github.wadahiro.midpoint.tester;

import java.util.List;
import java.util.Map;

/**
 * The in-memory target system behind a resource imported with {@link MidPoint#importResource}, seen with the names
 * of the real resource.
 *
 * <ul>
 *   <li>Objects are identified by their name: the value of the resource's naming attribute ({@code icfs:name}, or e.g.
 *       the full DN on LDAP).</li>
 *   <li>Attribute names are the local names of the resource attributes ({@code ri:mail} is {@code "mail"}); the naming
 *       attribute and a generated identifier are not attributes here.</li>
 *   <li>Values may be given as single objects or collections.</li>
 *   <li>Changes made here are not seen by midPoint until it reads the object (e.g. {@link MidPoint#importAccount}).
 *       They behave as on the real target system as far as {@link ResourceOptions} say: renames and deletes keep
 *       references valid with {@link ResourceOptions#referentialIntegrity()}, computed attributes
 *       ({@link ResourceOptions#computed}) cannot be written.</li>
 * </ul>
 *
 * Accounts are the object class of the first account object type of the resource, groups that of the first other
 * object type; any object class is addressed by the name the resource uses (e.g. {@code "groupOfNames"}, as in its
 * {@code schemaHandling}).
 */
public interface TargetSystem {

    String resourceOid();

    // ---- accounts --------------------------------------------------------------------------------------------

    void addAccount(String name, Map<String, ?> attributes);

    /** Replaces the values of an account attribute. */
    void modifyAccount(String name, String attribute, Object... values);

    /** Renames the account on the target system (e.g. a new DN), as an administrator there would. */
    void renameAccount(String name, String newName);

    void deleteAccount(String name);

    /** All attributes of the account, or null if there is no such account. */
    Map<String, List<Object>> account(String name);

    /** The single value of an account attribute (null if none); fails if the account does not exist. */
    Object attribute(String account, String attribute);

    /** All values of an account attribute (e.g. a computed {@code memberOf}); fails if the account does not exist. */
    List<Object> attributeValues(String account, String attribute);

    List<String> accountNames();

    /**
     * Whether the account is enabled: its native status (ConnId's {@code __ENABLE__}), or, on a resource with a
     * simulated status ({@code capabilities/configured/activation/status/attribute}, e.g. a CSV column), the value of
     * that attribute read as midPoint reads it (enable and disable values). Fails if the resource has neither.
     */
    boolean enabled(String account);

    /**
     * Enables or disables the account as an administrator of the target system would (a simulated status: the first
     * enable or disable value); fails if the resource has no activation capability.
     */
    void setEnabled(String account, boolean enabled);

    /** The account's password in clear text (null if none); fails if the resource has no password capability. */
    String password(String account);

    /**
     * Sets the account's password on the target system as its administrator would (null removes it), e.g. for an
     * inbound of a readable password; fails if the resource has no password capability.
     */
    void setPassword(String account, String password);

    // ---- groups (the first non-account object class of the resource) -----------------------------------------

    void addGroup(String name, Map<String, ?> attributes);

    void modifyGroup(String name, String attribute, Object... values);

    void deleteGroup(String name);

    /** All attributes of the group, or null if there is no such group. */
    Map<String, List<Object>> group(String name);

    /** All values of an attribute of the group (e.g. {@code member}); fails if the group does not exist. */
    List<Object> groupValues(String group, String attribute);

    List<String> groupNames();

    // ---- any object class of the resource --------------------------------------------------------------------

    void addObject(String objectClass, String name, Map<String, ?> attributes);

    void modifyObject(String objectClass, String name, String attribute, Object... values);

    void renameObject(String objectClass, String name, String newName);

    void deleteObject(String objectClass, String name);

    Map<String, List<Object>> find(String objectClass, String name);

    /**
     * The object's primary identifier ({@code __UID__}): generated (e.g. SCIM's {@code id}, LDAP's {@code entryUUID})
     * or, if the resource has none, the name. Needed to set references by identifier, e.g. SCIM {@code members}.
     */
    String identifier(String objectClass, String name);

    List<String> objectNames(String objectClass);
}
