package com.github.wadahiro.midpoint.tester.mirror.connector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder;
import org.identityconnectors.framework.common.objects.AttributeValueCompleteness;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationalAttributeInfos;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.ResultsHandler;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.objects.SchemaBuilder;
import org.identityconnectors.framework.common.objects.SearchResult;
import org.identityconnectors.framework.common.objects.SyncDeltaBuilder;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.identityconnectors.framework.common.objects.SyncResultsHandler;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.AndFilter;
import org.identityconnectors.framework.common.objects.filter.AttributeFilter;
import org.identityconnectors.framework.common.objects.filter.ContainsAllValuesFilter;
import org.identityconnectors.framework.common.objects.filter.ContainsFilter;
import org.identityconnectors.framework.common.objects.filter.EndsWithFilter;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterBuilder;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.common.objects.filter.NotFilter;
import org.identityconnectors.framework.common.objects.filter.OrFilter;
import org.identityconnectors.framework.common.objects.filter.StartsWithFilter;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.SearchResultsHandler;
import org.identityconnectors.framework.spi.SyncTokenResultsHandler;
import org.identityconnectors.framework.spi.operations.CreateOp;
import org.identityconnectors.framework.spi.operations.DeleteOp;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.SyncOp;
import org.identityconnectors.framework.spi.operations.TestOp;
import org.identityconnectors.framework.spi.operations.UpdateDeltaOp;

import com.github.wadahiro.midpoint.tester.mirror.MirrorAttribute;
import com.github.wadahiro.midpoint.tester.mirror.MirrorClass;
import com.github.wadahiro.midpoint.tester.mirror.MirrorObject;
import com.github.wadahiro.midpoint.tester.mirror.MirrorResource;
import com.github.wadahiro.midpoint.tester.mirror.MirrorStore;

/**
 * In-memory ConnId connector that reproduces the schema of a real resource (object class names, the native names of
 * {@code __NAME__} / {@code __UID__}, attribute types and multiplicity, activation (with validity dates) and password
 * support), so midPoint sees the same resource as in production and needs no change of its configuration.
 *
 * Search filters are evaluated with ConnId's own {@link Filter#accept(ConnectorObject)}, also on computed attributes;
 * names compare regardless of case if the resource says so ({@link MirrorResource#caseInsensitiveNames()}).
 *
 * Live synchronization works as the CSV connector's: a token stands for the content at that moment, and a run reports
 * what differs from it ({@link MirrorResource#changesSince}), changes made by midPoint and by the test alike.
 */
@ConnectorClass(displayNameKey = "mirror.connector.display", configurationClass = MirrorConfiguration.class)
public class MirrorConnector
        implements Connector, SchemaOp, TestOp, CreateOp, UpdateDeltaOp, DeleteOp, SearchOp<Filter>, SyncOp {

    private MirrorConfiguration configuration;
    private MirrorResource resource;

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void init(Configuration configuration) {
        this.configuration = (MirrorConfiguration) configuration;
        this.resource = MirrorStore.get(this.configuration.getInstanceId());
        if (resource == null) {
            throw new ConfigurationException("No mirror resource " + this.configuration.getInstanceId());
        }
    }

    @Override
    public void dispose() {
        resource = null;
    }

    @Override
    public void test() {
        // nothing to connect to
    }

    @Override
    public Schema schema() {
        SchemaBuilder schema = new SchemaBuilder(MirrorConnector.class);
        for (MirrorClass c : resource.classes()) {
            ObjectClassInfoBuilder oc = new ObjectClassInfoBuilder();
            oc.setType(c.connIdName);
            AttributeInfoBuilder name = new AttributeInfoBuilder(Name.NAME, String.class);
            name.setRequired(true);
            if (c.namingAttribute != null) {
                name.setNativeName(c.namingAttribute);
            }
            oc.addAttributeInfo(name.build());
            if (c.declaresUid()) {
                AttributeInfoBuilder uid = new AttributeInfoBuilder(Uid.NAME, String.class);
                if (c.uidAttribute != null) {
                    uid.setNativeName(c.uidAttribute);
                }
                uid.setCreateable(false);
                uid.setUpdateable(false);
                oc.addAttributeInfo(uid.build());
            }
            for (MirrorAttribute a : c.attributes.values()) {
                AttributeInfoBuilder attr = new AttributeInfoBuilder(a.name, a.type);
                attr.setMultiValued(a.multiValued);
                attr.setReadable(a.readable);
                attr.setCreateable(a.creatable);
                attr.setUpdateable(a.updatable);
                oc.addAttributeInfo(attr.build());
            }
            if (c.activation) {
                oc.addAttributeInfo(OperationalAttributeInfos.ENABLE);
            }
            // stored (as the connector receives them: milliseconds) and returned like other attributes
            if (c.validFrom) {
                oc.addAttributeInfo(OperationalAttributeInfos.ENABLE_DATE);
            }
            if (c.validTo) {
                oc.addAttributeInfo(OperationalAttributeInfos.DISABLE_DATE);
            }
            if (c.password && c.passwordReading == MirrorClass.PasswordReading.UNREADABLE) {
                oc.addAttributeInfo(OperationalAttributeInfos.PASSWORD);
            } else if (c.password) {
                AttributeInfoBuilder password = new AttributeInfoBuilder(OperationalAttributes.PASSWORD_NAME,
                        GuardedString.class);
                password.setReadable(true);
                password.setReturnedByDefault(c.passwordReturnedByDefault);
                oc.addAttributeInfo(password.build());
            }
            schema.defineObjectClass(oc.build());
        }
        return schema.build();
    }

    @Override
    public Uid create(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions options) {
        MirrorClass c = resource.byConnIdName(objectClass.getObjectClassValue());
        MirrorObject object = new MirrorObject();
        for (Attribute attribute : attributes) {
            if (attribute.is(Name.NAME)) {
                object.name = String.valueOf(single(attribute.getValue()));
            } else if (attribute.is(OperationalAttributes.ENABLE_NAME)) {
                object.enabled = (Boolean) single(attribute.getValue());
            } else if (attribute.is(OperationalAttributes.PASSWORD_NAME)) {
                object.password = clear(single(attribute.getValue()));
            } else if (!attribute.is(Uid.NAME) && attribute.getValue() != null && !attribute.getValue().isEmpty()) {
                rejectComputed(c, attribute.getName());
                object.attributes.put(attribute.getName(), oneByKey(c.attributes.get(attribute.getName()),
                        attribute.getValue()));
            }
        }
        if (object.name == null) {
            throw new InvalidAttributeValueException("__NAME__ is required");
        }
        for (MirrorAttribute a : c.attributes.values()) {
            if (a.storeExpression != null && object.attributes.containsKey(a.name)) {
                List<Object> kept = resource.storedValues(c, a, object.name, List.of(), object.attributes.get(a.name),
                        List.of(), null);
                if (kept.isEmpty()) {
                    object.attributes.remove(a.name);
                } else {
                    object.attributes.put(a.name, kept);
                }
            }
        }
        if (c.activation && object.enabled == null) {
            object.enabled = true;
        }
        try {
            return new Uid(resource.add(c, object));
        } catch (IllegalStateException e) {
            throw new AlreadyExistsException(e.getMessage());
        }
    }

    @Override
    public Set<AttributeDelta> updateDelta(ObjectClass objectClass, Uid uid, Set<AttributeDelta> deltas,
            OperationOptions options) {
        MirrorClass c = resource.byConnIdName(objectClass.getObjectClassValue());
        MirrorObject object = resource.findByUid(c, uid.getUidValue());
        if (object == null) {
            throw new UnknownUidException(uid, objectClass);
        }
        Set<AttributeDelta> sideEffects = new HashSet<>();
        for (AttributeDelta delta : deltas) {
            if (delta.is(Name.NAME)) {
                String oldUid = object.uid;
                String newUid;
                try {
                    newUid = resource.rename(c, object, String.valueOf(single(delta.getValuesToReplace())));
                } catch (IllegalStateException e) {
                    throw new AlreadyExistsException(e.getMessage());
                }
                if (!newUid.equals(oldUid)) {
                    sideEffects.add(AttributeDeltaBuilder.build(Uid.NAME, newUid));
                }
            } else if (delta.is(OperationalAttributes.ENABLE_NAME)) {
                object.enabled = (Boolean) single(delta.getValuesToReplace());
            } else if (delta.is(OperationalAttributes.PASSWORD_NAME)) {
                object.password = clear(single(delta.getValuesToReplace()));
            } else {
                rejectComputed(c, delta.getName());
                MirrorAttribute definition = c.attributes.get(delta.getName());
                if (definition != null && definition.storeExpression != null) {
                    store(c, definition, object, delta);
                } else {
                    apply(object.attributes, delta,
                            resource.caseInsensitiveNames() && resource.holdsNames(c, delta.getName()), definition);
                }
            }
        }
        return sideEffects;
    }

    @Override
    public void delete(ObjectClass objectClass, Uid uid, OperationOptions options) {
        MirrorClass c = resource.byConnIdName(objectClass.getObjectClassValue());
        if (!resource.delete(c, uid.getUidValue())) {
            throw new UnknownUidException(uid, objectClass);
        }
    }

    @Override
    public FilterTranslator<Filter> createFilterTranslator(ObjectClass objectClass, OperationOptions options) {
        return filter -> filter == null ? Collections.emptyList() : Collections.singletonList(filter);
    }

    @Override
    public void executeQuery(ObjectClass objectClass, Filter query, ResultsHandler handler, OperationOptions options) {
        MirrorClass c = resource.byConnIdName(objectClass.getObjectClassValue());
        Set<String> names = resource.caseInsensitiveNames() ? names(c) : Set.of();
        Filter filter = query == null ? null : lowerCase(query, names);
        for (MirrorObject object : resource.list(c)) {
            ConnectorObject connectorObject = toConnectorObject(resource, c, object, options);
            if (filter == null || filter.accept(lowerCase(connectorObject, names))) {
                if (!handler.handle(connectorObject)) {
                    break;
                }
            }
        }
        if (handler instanceof SearchResultsHandler) {
            ((SearchResultsHandler) handler).handleResult(new SearchResult());
        }
    }

    @Override
    public SyncToken getLatestSyncToken(ObjectClass objectClass) {
        return new SyncToken(resource.syncPoint());
    }

    @Override
    public void sync(ObjectClass objectClass, SyncToken token, SyncResultsHandler handler, OperationOptions options) {
        Integer from = tokenValue(token);
        SyncToken next = new SyncToken(resource.syncPoint());
        List<MirrorResource.Change> changes = new ArrayList<>();
        // without a token (or with one this mirror does not know), only the new token: synchronizing from now on
        if (from != null) {
            for (MirrorClass c : objectClass == null || objectClass.is(ObjectClass.ALL_NAME) ? resource.classes()
                    : List.of(resource.byConnIdName(objectClass.getObjectClassValue()))) {
                List<MirrorResource.Change> since = resource.changesSince(from, c);
                if (since == null) {
                    changes.clear();
                    break;
                }
                changes.addAll(since);
            }
        }
        for (MirrorResource.Change change : changes) {
            SyncDeltaBuilder delta = new SyncDeltaBuilder();
            delta.setToken(next);
            delta.setObjectClass(new ObjectClass(change.objectClass.connIdName));
            if (change.deleted) {
                delta.setDeltaType(SyncDeltaType.DELETE);
                delta.setUid(new Uid(change.object.uid));
            } else {
                delta.setDeltaType(change.created ? SyncDeltaType.CREATE : SyncDeltaType.UPDATE);
                delta.setObject(toConnectorObject(resource, change.objectClass, change.object, options));
            }
            if (!handler.handle(delta.build())) {
                return;
            }
        }
        if (handler instanceof SyncTokenResultsHandler) {
            ((SyncTokenResultsHandler) handler).handleResult(next);
        }
    }

    private static Integer tokenValue(SyncToken token) {
        Object value = token != null ? token.getValue() : null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && ((String) value).matches("[0-9]+")) {
            return Integer.valueOf((String) value);
        }
        return null;
    }

    private static ConnectorObject toConnectorObject(MirrorResource resource, MirrorClass c, MirrorObject object,
            OperationOptions options) {
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(new ObjectClass(c.connIdName));
        builder.setUid(object.uid);
        builder.setName(object.name);
        for (Map.Entry<String, List<Object>> e : resource.attributes(c, object).entrySet()) {
            MirrorAttribute definition = c.attributes.get(e.getKey());
            if (definition == null || definition.readable) {
                builder.addAttribute(AttributeBuilder.build(e.getKey(), e.getValue()));
            }
        }
        if (c.activation) {
            builder.addAttribute(AttributeBuilder.buildEnabled(!Boolean.FALSE.equals(object.enabled)));
        }
        if (object.password != null && c.passwordReading != MirrorClass.PasswordReading.UNREADABLE
                && passwordRequested(c, options)) {
            if (c.passwordReading == MirrorClass.PasswordReading.READABLE) {
                builder.addAttribute(AttributeBuilder.buildPassword(object.password.toCharArray()));
            } else {
                AttributeBuilder incomplete = new AttributeBuilder();
                incomplete.setName(OperationalAttributes.PASSWORD_NAME);
                incomplete.setAttributeValueCompleteness(AttributeValueCompleteness.INCOMPLETE);
                builder.addAttribute(incomplete.build());
            }
        }
        return builder.build();
    }

    /** A readable password is returned when asked for, or by default if the connector returns it by default. */
    private static boolean passwordRequested(MirrorClass c, OperationOptions options) {
        String[] attributesToGet = options != null ? options.getAttributesToGet() : null;
        return attributesToGet == null ? c.passwordReturnedByDefault
                : List.of(attributesToGet).contains(OperationalAttributes.PASSWORD_NAME);
    }

    /** The attributes whose values are names of the class (empty if names are case sensitive). */
    private Set<String> names(MirrorClass c) {
        Set<String> names = new HashSet<>();
        names.add(Name.NAME);
        if (!c.generatedUid()) {
            names.add(Uid.NAME);
        }
        for (String attribute : c.attributes.keySet()) {
            if (resource.holdsNames(c, attribute)) {
                names.add(attribute);
            }
        }
        return names;
    }

    /** The filter with the values of the name attributes in lower case. */
    private static Filter lowerCase(Filter filter, Set<String> names) {
        if (names.isEmpty()) {
            return filter;
        }
        if (filter instanceof AndFilter || filter instanceof OrFilter) {
            List<Filter> parts = new ArrayList<>();
            for (Filter part : (filter instanceof AndFilter ? ((AndFilter) filter).getFilters()
                    : ((OrFilter) filter).getFilters())) {
                parts.add(lowerCase(part, names));
            }
            return filter instanceof AndFilter ? FilterBuilder.and(parts) : FilterBuilder.or(parts);
        }
        if (filter instanceof NotFilter) {
            return FilterBuilder.not(lowerCase(((NotFilter) filter).getFilter(), names));
        }
        if (!(filter instanceof AttributeFilter) || !names.contains(((AttributeFilter) filter).getName())) {
            return filter;
        }
        Attribute attribute = lowerCase(((AttributeFilter) filter).getAttribute());
        if (filter instanceof EqualsFilter) {
            return FilterBuilder.equalTo(attribute);
        } else if (filter instanceof ContainsAllValuesFilter) {
            return FilterBuilder.containsAllValues(attribute);
        } else if (filter instanceof ContainsFilter) {
            return FilterBuilder.contains(attribute);
        } else if (filter instanceof StartsWithFilter) {
            return FilterBuilder.startsWith(attribute);
        } else if (filter instanceof EndsWithFilter) {
            return FilterBuilder.endsWith(attribute);
        }
        return filter;
    }

    /** The object with the values of the name attributes in lower case. */
    private static ConnectorObject lowerCase(ConnectorObject object, Set<String> names) {
        if (names.isEmpty()) {
            return object;
        }
        ConnectorObjectBuilder builder = new ConnectorObjectBuilder();
        builder.setObjectClass(object.getObjectClass());
        builder.setUid(names.contains(Uid.NAME) ? lower(object.getUid().getUidValue()) : object.getUid().getUidValue());
        builder.setName(lower(object.getName().getNameValue()));
        for (Attribute attribute : object.getAttributes()) {
            if (!attribute.is(Uid.NAME) && !attribute.is(Name.NAME)) {
                builder.addAttribute(names.contains(attribute.getName()) ? lowerCase(attribute) : attribute);
            }
        }
        return builder.build();
    }

    private static Attribute lowerCase(Attribute attribute) {
        List<Object> values = new ArrayList<>();
        for (Object value : attribute.getValue() == null ? List.of() : attribute.getValue()) {
            values.add(value instanceof String ? lower((String) value) : value);
        }
        return AttributeBuilder.build(attribute.getName(), values);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** Computed attributes are read-only, as on the real target system. */
    private static void rejectComputed(MirrorClass c, String attribute) {
        if (c.computed.containsKey(attribute)) {
            throw new InvalidAttributeValueException("Attribute " + attribute + " of " + c.midPointName
                    + " is computed by the target system and cannot be written");
        }
    }

    /**
     * Applies the delta; values of {@code names} attributes compare regardless of case. An attribute with keys
     * ({@link MirrorAttribute#keySeparator}) keeps one value per key, as a map: removing a value removes its key, adding
     * one replaces the value of its key.
     */
    private static void apply(Map<String, List<Object>> attributes, AttributeDelta delta, boolean names,
            MirrorAttribute definition) {
        List<Object> values = attributes.computeIfAbsent(delta.getName(), k -> new ArrayList<>());
        if (delta.getValuesToReplace() != null) {
            values.clear();
            values.addAll(oneByKey(definition, delta.getValuesToReplace()));
        } else {
            if (delta.getValuesToRemove() != null) {
                for (Object value : delta.getValuesToRemove()) {
                    values.removeIf(v -> same(v, value, names) || sameKey(definition, v, value));
                }
            }
            if (delta.getValuesToAdd() != null) {
                for (Object value : delta.getValuesToAdd()) {
                    values.removeIf(v -> sameKey(definition, v, value));
                    if (values.stream().noneMatch(v -> same(v, value, names))) {
                        values.add(value);
                    }
                }
            }
        }
        if (values.isEmpty()) {
            attributes.remove(delta.getName());
        }
    }

    /** Keeps the values the attribute's expression makes of the delta ({@link MirrorAttribute#storeExpression}). */
    private void store(MirrorClass c, MirrorAttribute definition, MirrorObject object, AttributeDelta delta) {
        List<Object> values = object.attributes.getOrDefault(delta.getName(), List.of());
        List<Object> kept = resource.storedValues(c, definition, object.name, values,
                delta.getValuesToAdd() != null ? delta.getValuesToAdd() : List.of(),
                delta.getValuesToRemove() != null ? delta.getValuesToRemove() : List.of(), delta.getValuesToReplace());
        if (kept.isEmpty()) {
            object.attributes.remove(delta.getName());
        } else {
            object.attributes.put(delta.getName(), kept);
        }
    }

    private static boolean sameKey(MirrorAttribute definition, Object a, Object b) {
        if (definition == null || definition.keySeparator == null) {
            return false;
        }
        String key = definition.keyOf(a);
        return key != null && key.equals(definition.keyOf(b));
    }

    /** The values, the last of each key kept for an attribute with keys. */
    private static List<Object> oneByKey(MirrorAttribute definition, List<Object> values) {
        List<Object> kept = new ArrayList<>();
        for (Object value : values) {
            kept.removeIf(v -> sameKey(definition, v, value));
            kept.add(value);
        }
        return kept;
    }

    private static boolean same(Object a, Object b, boolean ignoreCase) {
        return ignoreCase && a instanceof String && b instanceof String
                ? ((String) a).equalsIgnoreCase((String) b) : a.equals(b);
    }

    private static Object single(List<Object> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String clear(Object value) {
        if (value instanceof GuardedString) {
            StringBuilder clear = new StringBuilder();
            ((GuardedString) value).access(chars -> clear.append(chars));
            return clear.toString();
        }
        return value == null ? null : String.valueOf(value);
    }
}
