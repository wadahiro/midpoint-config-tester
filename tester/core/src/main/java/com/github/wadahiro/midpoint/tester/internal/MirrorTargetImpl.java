package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.tester.mirror.MirrorClass;
import com.github.wadahiro.midpoint.tester.mirror.MirrorObject;
import com.github.wadahiro.midpoint.tester.mirror.MirrorResource;
import com.github.wadahiro.midpoint.tester.mirror.MirrorStore;

/**
 * The content of a mirrored resource, seen with the names of the real resource: object classes as midPoint shows them
 * ({@code inetOrgPerson}), objects by their name (e.g. the DN). Accounts and groups are the first account / other
 * object class used by the resource's object types. Its content is not in the database: snapshots capture it, resets
 * restore it.
 */
final class MirrorTargetImpl implements ManagedTarget {

    private final MirrorResource resource;
    private List<String> accountClasses = List.of();
    private List<String> otherClasses = List.of();
    private SimulatedActivation simulatedActivation;

    /** Definitions and content at one moment. */
    private static final class Captured {
        final MirrorResource.Snapshot resource;
        final List<String> accountClasses;
        final List<String> otherClasses;
        final SimulatedActivation simulatedActivation;

        Captured(MirrorResource.Snapshot resource, List<String> accountClasses, List<String> otherClasses,
                SimulatedActivation simulatedActivation) {
            this.resource = resource;
            this.accountClasses = accountClasses;
            this.otherClasses = otherClasses;
            this.simulatedActivation = simulatedActivation;
        }
    }

    private MirrorTargetImpl(MirrorResource resource) {
        this.resource = resource;
    }

    static MirrorTargetImpl create(MirrorResourceXml definition) {
        MirrorTargetImpl target = new MirrorTargetImpl(MirrorStore.getOrCreate(definition.oid));
        target.update(definition);
        return target;
    }

    /** (Re)defines the object classes; the content is kept. */
    void update(MirrorResourceXml definition) {
        resource.define(definition.classes, definition.references, definition.referentialIntegrity,
                definition.caseInsensitiveNames);
        accountClasses = definition.accountClasses;
        otherClasses = definition.otherClasses;
        simulatedActivation = definition.simulatedActivation;
    }

    @Override
    public QName accountNamingAttribute() {
        String naming = resource.byName(accountClass()).namingAttribute;
        return naming == null ? new QName(Xml.NS_ICFS, "name") : new QName(Xml.NS_RI, naming);
    }

    @Override
    public String resourceOid() {
        return resource.instanceId;
    }

    // ---- shortcuts --------------------------------------------------------------------------------------------

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
        List<Object> values = values(accountClass(), account, attribute);
        if (values.size() > 1) {
            throw new AssertionError("Attribute " + attribute + " of " + account + " has several values: " + values);
        }
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public List<Object> attributeValues(String account, String attribute) {
        return values(accountClass(), account, attribute);
    }

    @Override
    public List<String> accountNames() {
        return objectNames(accountClass());
    }

    @Override
    public boolean enabled(String account) {
        if (simulatedActivation != null) {
            return simulatedActivation.enabled(this, account);
        }
        if (!resource.byName(accountClass()).activation) {
            throw new IllegalStateException("Resource " + resource.instanceId + " has no activation capability");
        }
        return !Boolean.FALSE.equals(existing(accountClass(), account).enabled);
    }

    @Override
    public void setEnabled(String account, boolean enabled) {
        if (simulatedActivation != null) {
            simulatedActivation.setEnabled(this, account, enabled);
            return;
        }
        if (!resource.byName(accountClass()).activation) {
            throw new IllegalStateException("Resource " + resource.instanceId + " has no activation capability");
        }
        existing(accountClass(), account).enabled = enabled;
    }

    @Override
    public String password(String account) {
        if (!resource.byName(accountClass()).password) {
            throw new IllegalStateException("Resource " + resource.instanceId + " has no password capability");
        }
        return existing(accountClass(), account).password;
    }

    @Override
    public void setPassword(String account, String password) {
        if (!resource.byName(accountClass()).password) {
            throw new IllegalStateException("Resource " + resource.instanceId + " has no password capability");
        }
        existing(accountClass(), account).password = password;
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
        return values(groupClass(), group, attribute);
    }

    @Override
    public List<String> groupNames() {
        return objectNames(groupClass());
    }

    // ---- any object class -------------------------------------------------------------------------------------

    @Override
    public void addObject(String objectClass, String name, Map<String, ?> attributes) {
        MirrorClass c = resource.byName(objectClass);
        MirrorObject object = new MirrorObject();
        object.name = name;
        if (c.activation) {
            object.enabled = true;
        }
        attributes.forEach((k, v) -> {
            rejectComputed(c, k);
            object.attributes.put(k, asList(v));
        });
        resource.add(c, object);
    }

    @Override
    public void modifyObject(String objectClass, String name, String attribute, Object... values) {
        rejectComputed(resource.byName(objectClass), attribute);
        MirrorObject object = existing(objectClass, name);
        if (values.length == 0) {
            object.attributes.remove(attribute);
        } else {
            object.attributes.put(attribute, new ArrayList<>(List.of(values)));
        }
    }

    @Override
    public void renameObject(String objectClass, String name, String newName) {
        resource.rename(resource.byName(objectClass), existing(objectClass, name), newName);
    }

    @Override
    public void deleteObject(String objectClass, String name) {
        MirrorClass c = resource.byName(objectClass);
        resource.delete(c, existing(objectClass, name).uid);
    }

    @Override
    public Map<String, List<Object>> find(String objectClass, String name) {
        MirrorClass c = resource.byName(objectClass);
        MirrorObject object = resource.findByName(c, name);
        return object == null ? null : new TreeMap<>(resource.attributes(c, object));
    }

    @Override
    public String identifier(String objectClass, String name) {
        return existing(objectClass, name).uid;
    }

    @Override
    public List<String> objectNames(String objectClass) {
        List<String> names = new ArrayList<>();
        for (MirrorObject object : resource.list(resource.byName(objectClass))) {
            names.add(object.name);
        }
        names.sort(null);
        return names;
    }

    // ---- snapshots ----------------------------------------------------------------------------------------------

    @Override
    public Object capture() {
        return new Captured(resource.capture(), accountClasses, otherClasses, simulatedActivation);
    }

    @Override
    public java.io.Serializable persistentContent() {
        return resource.persistentObjects();
    }

    @Override
    public void restorePersistent(java.io.Serializable content) {
        resource.restorePersistentObjects(content);
    }

    /** Restores captured definitions and content; null means no content. */
    @Override
    public void restore(Object captured) {
        if (captured == null) {
            resource.restore(null);
            return;
        }
        Captured c = (Captured) captured;
        resource.restore(c.resource);
        accountClasses = c.accountClasses;
        otherClasses = c.otherClasses;
        simulatedActivation = c.simulatedActivation;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private List<Object> values(String objectClass, String name, String attribute) {
        MirrorClass c = resource.byName(objectClass);
        return new ArrayList<>(resource.attributes(c, existing(objectClass, name)).getOrDefault(attribute, List.of()));
    }

    private MirrorObject existing(String objectClass, String name) {
        MirrorObject object = resource.findByName(resource.byName(objectClass), name);
        if (object == null) {
            throw new AssertionError("No " + objectClass + " " + name + " on mirror " + resource.instanceId
                    + "; existing: " + objectNames(objectClass));
        }
        return object;
    }

    private static void rejectComputed(MirrorClass c, String attribute) {
        if (c.computed.containsKey(attribute)) {
            throw new IllegalArgumentException("Attribute " + attribute + " of " + c.midPointName
                    + " is computed by the target system; change the " + c.computed.get(attribute).holderAttribute
                    + " of the " + c.computed.get(attribute).holderClass + " objects instead");
        }
    }

    private String accountClass() {
        if (accountClasses.isEmpty()) {
            throw new IllegalStateException("Resource " + resource.instanceId + " has no account object type");
        }
        return accountClasses.get(0);
    }

    private String groupClass() {
        if (otherClasses.isEmpty()) {
            throw new IllegalStateException("Resource " + resource.instanceId + " has no non-account object type");
        }
        return otherClasses.get(0);
    }

    private static List<Object> asList(Object value) {
        List<Object> list = new ArrayList<>();
        if (value instanceof Collection<?>) {
            list.addAll((Collection<?>) value);
        } else {
            list.add(value);
        }
        return list;
    }
}
