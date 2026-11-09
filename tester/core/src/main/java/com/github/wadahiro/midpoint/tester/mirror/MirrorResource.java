package com.github.wadahiro.midpoint.tester.mirror;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * In-memory content of one mirrored resource, shared (statically, through {@link MirrorStore}) by the test code and the
 * connector, which midPoint loads from a separate bundle class loader. Not part of the connector bundle on purpose.
 *
 * Besides storing objects, it does what the target system does by itself: computed attributes (see
 * {@link MirrorClass#computed}), referential integrity of the references between its objects and, if asked, matching
 * names and references by name regardless of case (like LDAP DNs).
 */
public final class MirrorResource {

    public final String instanceId;
    private final Map<String, MirrorClass> classes = new LinkedHashMap<>();
    /** The references between objects (the associations). */
    private List<MirrorLink> references = List.of();
    /** Whether references are kept valid on rename and delete. */
    private boolean referentialIntegrity;
    /** Whether names, and references by name, match regardless of case. */
    private boolean caseInsensitiveNames;
    /** ConnId object class name -> uid -> object. */
    private final Map<String, Map<String, MirrorObject>> objects = new LinkedHashMap<>();
    /** Live synchronization tokens of all mirrors: never reused, so a token from a restored database stays valid. */
    private static final AtomicInteger TOKENS = new AtomicInteger();
    /** The stored objects at each token, which live synchronization compares the content with (not restored). */
    private final Map<Integer, Map<String, Map<String, MirrorObject>>> syncPoints = new LinkedHashMap<>();

    /** Definitions and content at one moment, see {@link #capture()}. */
    public static final class Snapshot {
        final Map<String, MirrorClass> classes;
        final List<MirrorLink> references;
        final boolean referentialIntegrity;
        final boolean caseInsensitiveNames;
        final Map<String, Map<String, MirrorObject>> objects;

        Snapshot(Map<String, MirrorClass> classes, List<MirrorLink> references, boolean referentialIntegrity,
                boolean caseInsensitiveNames, Map<String, Map<String, MirrorObject>> objects) {
            this.classes = classes;
            this.references = references;
            this.referentialIntegrity = referentialIntegrity;
            this.caseInsensitiveNames = caseInsensitiveNames;
            this.objects = objects;
        }
    }

    public MirrorResource(String instanceId) {
        this.instanceId = instanceId;
    }

    /**
     * Defines (or redefines, keeping the content) the object classes and the references between their objects, and
     * whether the target system keeps the references valid and matches names regardless of case.
     */
    public synchronized void define(List<MirrorClass> definitions, List<MirrorLink> references,
            boolean referentialIntegrity, boolean caseInsensitiveNames) {
        for (MirrorClass c : definitions) {
            classes.put(c.connIdName, c);
            objects.computeIfAbsent(c.connIdName, k -> new LinkedHashMap<>());
        }
        this.references = List.copyOf(references);
        this.referentialIntegrity = referentialIntegrity;
        this.caseInsensitiveNames = caseInsensitiveNames;
    }

    public synchronized boolean caseInsensitiveNames() {
        return caseInsensitiveNames;
    }

    /**
     * Whether values of the attribute are names, matched regardless of case if {@link #caseInsensitiveNames()}: the
     * references by name the class holds and its computed attributes that refer by name. (ConnId's {@code __NAME__},
     * and {@code __UID__} if it is the name, are names too.)
     */
    public synchronized boolean holdsNames(MirrorClass c, String attribute) {
        MirrorLink computed = c.computed.get(attribute);
        if (computed != null) {
            return !computed.byUid;
        }
        for (MirrorLink link : references) {
            if (link.holderClass.equals(c.connIdName) && link.holderAttribute.equals(attribute)) {
                return !link.byUid || !classes.get(link.valueClass).generatedUid();
            }
        }
        return false;
    }

    public synchronized List<MirrorClass> classes() {
        return new ArrayList<>(classes.values());
    }

    public synchronized MirrorClass byConnIdName(String connIdName) {
        MirrorClass c = classes.get(connIdName);
        if (c == null) {
            throw new IllegalArgumentException("Unknown object class " + connIdName + " on mirror " + instanceId);
        }
        return c;
    }

    /** By the name midPoint shows (or the ConnId name). */
    public synchronized MirrorClass byName(String name) {
        for (MirrorClass c : classes.values()) {
            if (c.midPointName.equals(name) || c.connIdName.equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown object class " + name + " on mirror " + instanceId
                + "; known: " + classes.keySet());
    }

    /** Adds an object; returns its uid. Fails if the name is taken. */
    public synchronized String add(MirrorClass c, MirrorObject object) {
        if (findByName(c, object.name) != null) {
            throw new IllegalStateException("Object " + object.name + " already exists");
        }
        object.uid = c.uidValue != null ? issuedUid(c, object)
                : c.generatedUid() ? UUID.randomUUID().toString() : object.name;
        if (objects.get(c.connIdName).containsKey(object.uid)) {
            throw new IllegalStateException("Object " + object.uid + " already exists");
        }
        objects.get(c.connIdName).put(object.uid, object);
        return object.uid;
    }

    /**
     * The values of the attribute the target system keeps, as its expression ({@link MirrorAttribute#storeExpression})
     * makes them of the values kept so far and what midPoint writes.
     */
    public List<Object> storedValues(MirrorClass c, MirrorAttribute attribute, String objectName, List<Object> values,
            List<Object> add, List<Object> remove, List<Object> replace) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("values", new ArrayList<>(values));
        variables.put("add", new ArrayList<>(add));
        variables.put("remove", new ArrayList<>(remove));
        variables.put("replace", replace != null ? new ArrayList<>(replace) : null);
        variables.put("name", objectName);
        Object kept = evaluate(attribute.storeExpression, variables, "The store expression of " + c.midPointName
                + "/" + attribute.name + " failed for " + objectName);
        List<Object> out = new ArrayList<>();
        if (kept instanceof java.util.Collection) {
            out.addAll((java.util.Collection<?>) kept);
        } else if (kept != null) {
            out.add(kept);
        }
        return out;
    }

    /** Evaluates the Groovy code with the variables. */
    private static Object evaluate(String code, Map<String, Object> variables, String failure) {
        try {
            // Groovy by reflection: midPoint's, on the test's class path (this class is not in the connector bundle)
            Class<?> bindingClass = Class.forName("groovy.lang.Binding");
            Object binding = bindingClass.getConstructor(Map.class).newInstance(variables);
            Class<?> shellClass = Class.forName("groovy.lang.GroovyShell");
            Object shell = shellClass.getConstructor(bindingClass).newInstance(binding);
            return shellClass.getMethod("evaluate", String.class).invoke(shell, code);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // not IllegalStateException: the connector reports that as an existing object
            throw new IllegalArgumentException(failure + ": " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Groovy is not on the class path", e);
        }
    }

    /** The identifier the class's expression makes for the object being created. */
    private static String issuedUid(MirrorClass c, MirrorObject object) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("name", object.name);
        variables.put("attributes", new LinkedHashMap<>(object.attributes));
        variables.put("now", java.time.Instant.now());
        Object uid = evaluate(c.uidValue, variables, "The identifier expression of " + c.midPointName
                + " failed for " + object.name);
        if (uid == null || uid.toString().isEmpty()) {
            throw new IllegalArgumentException("The identifier expression of " + c.midPointName
                    + " made no identifier for "
                    + object.name);
        }
        return uid.toString();
    }

    public synchronized MirrorObject findByUid(MirrorClass c, String uid) {
        if (!c.generatedUid()) {
            return findByName(c, uid);
        }
        return objects.get(c.connIdName).get(uid);
    }

    public synchronized MirrorObject findByName(MirrorClass c, String name) {
        for (MirrorObject o : objects.get(c.connIdName).values()) {
            if (sameName(o.name, name)) {
                return o;
            }
        }
        return null;
    }

    /** Renames; if the uid is the name, the uid changes too (returned). References to it follow the rename. */
    public synchronized String rename(MirrorClass c, MirrorObject object, String newName) {
        MirrorObject existing = findByName(c, newName);
        if (existing != null && existing != object) {
            throw new IllegalStateException("Object " + newName + " already exists");
        }
        MirrorObject before = object.copy();
        object.name = newName;
        if (!c.generatedUid()) {
            objects.get(c.connIdName).remove(object.uid);
            object.uid = newName;
            objects.get(c.connIdName).put(object.uid, object);
        }
        for (MirrorLink link : linksTo(c)) {
            String oldReference = link.reference(before);
            String newReference = link.reference(object);
            if (!oldReference.equals(newReference)) {
                forEachReference(link, oldReference, values -> values.replaceAll(
                        v -> refersTo(link, oldReference, v) ? newReference : v));
            }
        }
        return object.uid;
    }

    /** Deletes; references to it are removed. */
    public synchronized boolean delete(MirrorClass c, String uid) {
        MirrorObject object = objects.get(c.connIdName).remove(uid);
        if (object == null) {
            return false;
        }
        for (MirrorLink link : linksTo(c)) {
            String reference = link.reference(object);
            forEachReference(link, reference, values -> values.removeIf(v -> refersTo(link, reference, v)));
        }
        return true;
    }

    public synchronized List<MirrorObject> list(MirrorClass c) {
        return new ArrayList<>(objects.get(c.connIdName).values());
    }

    /**
     * The attributes of an object as the target system shows them: the stored ones plus the computed ones (the
     * holders of the link that refer to this object, referred to the same way).
     */
    public synchronized Map<String, List<Object>> attributes(MirrorClass c, MirrorObject object) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        object.attributes.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        c.computed.forEach((attribute, link) -> {
            String reference = link.reference(object);
            List<Object> values = new ArrayList<>();
            for (MirrorObject holder : objects.getOrDefault(link.holderClass, Map.of()).values()) {
                for (Object v : holder.attributes.getOrDefault(link.holderAttribute, List.of())) {
                    if (refersTo(link, reference, v)) {
                        values.add(link.reference(holder));
                        break;
                    }
                }
            }
            if (values.isEmpty()) {
                out.remove(attribute);
            } else {
                out.put(attribute, values);
            }
        });
        return out;
    }

    /** Deep copy of the definitions and content, for snapshots. */
    public synchronized Snapshot capture() {
        Map<String, Map<String, MirrorObject>> copy = new LinkedHashMap<>();
        objects.forEach((c, byUid) -> {
            Map<String, MirrorObject> objectsCopy = new LinkedHashMap<>();
            byUid.forEach((uid, o) -> objectsCopy.put(uid, o.copy()));
            copy.put(c, objectsCopy);
        });
        return new Snapshot(new LinkedHashMap<>(classes), references, referentialIntegrity, caseInsensitiveNames,
                copy);
    }

    /**
     * The objects (not the definitions) as a serializable value, with the live synchronization token counter, for the
     * deployment cache; fails when a value is not serializable.
     */
    public synchronized java.io.Serializable persistentObjects() {
        java.util.HashMap<String, Object> out = new java.util.HashMap<>();
        LinkedHashMap<String, LinkedHashMap<String, MirrorObject>> copy = new LinkedHashMap<>();
        objects.forEach((c, byUid) -> {
            LinkedHashMap<String, MirrorObject> objectsCopy = new LinkedHashMap<>();
            byUid.forEach((uid, o) -> objectsCopy.put(uid, o.copy()));
            copy.put(c, objectsCopy);
        });
        out.put("objects", copy);
        out.put("tokens", TOKENS.get());
        return out;
    }

    /** Back to {@link #persistentObjects()} of the same definitions (in another JVM). */
    @SuppressWarnings("unchecked")
    public synchronized void restorePersistentObjects(java.io.Serializable state) {
        java.util.Map<String, Object> in = (java.util.Map<String, Object>) state;
        Map<String, Map<String, MirrorObject>> saved = (Map<String, Map<String, MirrorObject>>) in.get("objects");
        objects.values().forEach(Map::clear);
        saved.forEach((c, byUid) -> {
            Map<String, MirrorObject> restored = objects.computeIfAbsent(c, k -> new LinkedHashMap<>());
            byUid.forEach((uid, o) -> restored.put(uid, o.copy()));
        });
        // a token in the restored database must not be given out again
        TOKENS.accumulateAndGet((Integer) in.get("tokens"), Math::max);
    }

    /** Back to a snapshot; null means no content (the definitions stay). */
    public synchronized void restore(Snapshot snapshot) {
        if (snapshot == null) {
            objects.values().forEach(Map::clear);
            return;
        }
        classes.clear();
        classes.putAll(snapshot.classes);
        references = snapshot.references;
        referentialIntegrity = snapshot.referentialIntegrity;
        caseInsensitiveNames = snapshot.caseInsensitiveNames;
        objects.clear();
        snapshot.objects.forEach((c, byUid) -> {
            Map<String, MirrorObject> restored = new LinkedHashMap<>();
            byUid.forEach((uid, o) -> restored.put(uid, o.copy()));
            objects.put(c, restored);
        });
    }

    /** A change live synchronization reports: the object as it is now, or (a deletion) as it was. */
    public static final class Change {
        public final MirrorClass objectClass;
        public final MirrorObject object;
        public final boolean created;
        public final boolean deleted;

        Change(MirrorClass objectClass, MirrorObject object, boolean created, boolean deleted) {
            this.objectClass = objectClass;
            this.object = object;
            this.created = created;
            this.deleted = deleted;
        }
    }

    /** A new live synchronization token: the stored objects at this moment. */
    public synchronized int syncPoint() {
        int token = TOKENS.incrementAndGet();
        Map<String, Map<String, MirrorObject>> copy = new LinkedHashMap<>();
        objects.forEach((c, byUid) -> {
            Map<String, MirrorObject> objectsCopy = new LinkedHashMap<>();
            byUid.forEach((uid, o) -> objectsCopy.put(uid, o.copy()));
            copy.put(c, objectsCopy);
        });
        syncPoints.put(token, copy);
        return token;
    }

    /**
     * The changes of the class's stored objects since the token, as the CSV connector finds them comparing the file
     * with its copy: objects added, objects whose name, status, password or stored attributes differ, then objects
     * deleted (a rename that changes the uid is a deletion and an addition). Null if the token is unknown.
     */
    public synchronized List<Change> changesSince(int token, MirrorClass c) {
        Map<String, Map<String, MirrorObject>> point = syncPoints.get(token);
        if (point == null) {
            return null;
        }
        Map<String, MirrorObject> before = point.getOrDefault(c.connIdName, Map.of());
        Map<String, MirrorObject> now = objects.getOrDefault(c.connIdName, Map.of());
        List<Change> changes = new ArrayList<>();
        now.forEach((uid, o) -> {
            MirrorObject old = before.get(uid);
            if (old == null || !old.sameContent(o)) {
                changes.add(new Change(c, o.copy(), old == null, false));
            }
        });
        before.forEach((uid, o) -> {
            if (!now.containsKey(uid)) {
                changes.add(new Change(c, o.copy(), false, true));
            }
        });
        return changes;
    }

    /** Whether two names are the same name. */
    public synchronized boolean sameName(String a, Object b) {
        return caseInsensitiveNames ? a.equalsIgnoreCase(String.valueOf(b)) : a.equals(String.valueOf(b));
    }

    /** Whether a value of the link's holder attribute is the reference. */
    private boolean refersTo(MirrorLink link, String reference, Object value) {
        boolean byName = !link.byUid || !classes.get(link.valueClass).generatedUid();
        return byName ? sameName(reference, value) : reference.equals(String.valueOf(value));
    }

    /** The links kept valid when objects of the class are renamed or deleted. */
    private List<MirrorLink> linksTo(MirrorClass c) {
        List<MirrorLink> out = new ArrayList<>();
        if (!referentialIntegrity) {
            return out;
        }
        for (MirrorLink link : references) {
            if (link.valueClass.equals(c.connIdName)) {
                out.add(link);
            }
        }
        return out;
    }

    /** Calls {@code change} on every holder attribute of the link that contains the reference. */
    private void forEachReference(MirrorLink link, String reference, Consumer<List<Object>> change) {
        for (MirrorObject holder : objects.getOrDefault(link.holderClass, Map.of()).values()) {
            List<Object> values = holder.attributes.get(link.holderAttribute);
            if (values != null && values.stream().anyMatch(v -> refersTo(link, reference, v))) {
                change.accept(values);
                if (values.isEmpty()) {
                    holder.attributes.remove(link.holderAttribute);
                }
            }
        }
    }
}
