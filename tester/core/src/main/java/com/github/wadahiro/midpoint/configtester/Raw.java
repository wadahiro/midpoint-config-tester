package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceObjectTypeDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowKindType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.TargetSystem;
import com.github.wadahiro.midpoint.tester.internal.ItemValues;

/**
 * Writes past midPoint's model ({@link MidPointConfigTester#raw()}): to the repository as it is (no mappings, no
 * provisioning, no policies, no audit) and to the target systems. For the state a test starts from that midPoint's
 * processing would not make now: values an earlier version of the configuration or a migration left, an account that
 * is linked but gone from the target system, a value that drifted. A state made through the model would already run
 * the rule the test is about, and a broken rule would change it while it is made.
 *
 * <p>A linked account is three records: the object on the target system, its shadow in the repository, and the
 * focus's link to the shadow. {@link RawObject#link} writes all three; the others write one each, so that the
 * inconsistent states are made by combining them:</p>
 *
 * <pre>{@code
 * t.raw().user("jdoe").set("extension/token", "legacy-1");
 * t.raw().user("jdoe").link("Directory", "legacy-1", shadow -> shadow.tag("legacy"));
 * t.target("Directory").delete("legacy-1");                 // linked, but gone from the target system
 * t.raw().shadow("Directory", "orphan").add();              // a shadow nobody links
 * }</pre>
 *
 * <p>Every write reads back what it wrote and fails ({@link IllegalStateException}) if the repository or the target
 * system does not have it. What the test then depends on is checked by the container's precondition (Kotest).</p>
 */
public final class Raw {

    private final MidPointConfigTester tester;

    Raw(MidPointConfigTester tester) {
        this.tester = tester;
    }

    /** The existing user by name. */
    public RawObject user(String name) {
        return new RawObject(UserType.class, name);
    }

    /** The existing role by name. */
    public RawObject role(String name) {
        return new RawObject(com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType.class, name);
    }

    /** The existing org by name. */
    public RawObject org(String name) {
        return new RawObject(com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType.class, name);
    }

    /** The existing service by name. */
    public RawObject service(String name) {
        return new RawObject(com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType.class, name);
    }

    /** The existing object of the type by name (an archetype, a task...). */
    public RawObject object(Class<? extends ObjectType> type, String name) {
        return new RawObject(type, name);
    }

    /** The shadow on the resource with this name (the account's name on the target system), with no owner. */
    public RawShadow shadow(String resource, String name) {
        return new RawShadow(resource, name);
    }

    /** The shadow that {@link RawObject#link} and {@link RawShadow#add} write: its object type, tag, attributes. */
    public static final class NewShadow {

        private String kind;
        private String intent;
        private String tag;
        private final Map<String, List<Object>> attributes = new LinkedHashMap<>();

        NewShadow() {
        }

        /** The kind of the object type ({@code account}, {@code entitlement}); by default an account. */
        public NewShadow kind(String kind) {
            this.kind = kind;
            return this;
        }

        /** The intent of the object type; by default the kind's default one. */
        public NewShadow intent(String intent) {
            this.intent = intent;
            return this;
        }

        /** The shadow's tag (the object type's multiplicity tag), e.g. what the tag expression would compute. */
        public NewShadow tag(String tag) {
            this.tag = tag;
            return this;
        }

        /** An attribute of the object on the target system (by its name there), when it is added there. */
        public NewShadow attribute(String attribute, Object... values) {
            attributes.put(attribute, new ArrayList<>(Arrays.asList(values)));
            return this;
        }
    }

    /** An object of {@link Raw}: its items, and for a focus its links. */
    public final class RawObject {

        private final Class<? extends ObjectType> type;
        private final String name;

        private RawObject(Class<? extends ObjectType> type, String name) {
            this.type = type;
            this.name = name;
        }

        /**
         * Replaces the values of the item (the path as {@link User#hasItem}: {@code extension/token}, names without
         * namespace); no values empty it.
         */
        public RawObject set(String path, Object... values) {
            ObjectType object = existing();
            ItemValues.Located item = located(object.asPrismObject(), path);
            Object[] typed = Struct.values(api().prismContext(), path, item.definition, values);
            modify(object, built(() -> {
                var entry = api().prismContext().deltaFor(type).item(item.path);
                Object[] prism = ItemValues.forDelta(typed);
                return (prism instanceof com.evolveum.midpoint.prism.PrismValue[]
                        ? entry.replace((com.evolveum.midpoint.prism.PrismValue[]) prism) : entry.replace(typed))
                        .asItemDeltas();
            }));
            List<String> now = values(path);
            if (!Values.sameValues(Values.texts(typed), now)) {
                throw unlike("set " + path + " of " + name + " to " + Values.texts(typed), now);
            }
            return this;
        }

        /** Adds the values to the item, keeping the ones it has. */
        public RawObject addValues(String path, Object... values) {
            ObjectType object = existing();
            ItemValues.Located item = located(object.asPrismObject(), path);
            Object[] typed = Struct.values(api().prismContext(), path, item.definition, values);
            modify(object, built(() -> {
                var entry = api().prismContext().deltaFor(type).item(item.path);
                Object[] prism = ItemValues.forDelta(typed);
                return (prism instanceof com.evolveum.midpoint.prism.PrismValue[]
                        ? entry.add((com.evolveum.midpoint.prism.PrismValue[]) prism) : entry.add(typed))
                        .asItemDeltas();
            }));
            List<String> now = values(path);
            if (!now.containsAll(Values.texts(typed))) {
                throw unlike("add " + Values.texts(typed) + " to " + path + " of " + name, now);
            }
            return this;
        }

        /** Removes the values from the item, keeping the others. */
        public RawObject removeValues(String path, Object... values) {
            ObjectType object = existing();
            ItemValues.Located item = located(object.asPrismObject(), path);
            Object[] typed = Struct.byIds(path, item.definition, object.asPrismObject().findItem(item.path),
                    Struct.values(api().prismContext(), path, item.definition, values));
            List<String> removed = Values.texts(Struct.values(api().prismContext(), path, item.definition, values));
            modify(object, built(() -> {
                var entry = api().prismContext().deltaFor(type).item(item.path);
                Object[] prism = ItemValues.forDelta(typed);
                return (prism instanceof com.evolveum.midpoint.prism.PrismValue[]
                        ? entry.delete((com.evolveum.midpoint.prism.PrismValue[]) prism) : entry.delete(typed))
                        .asItemDeltas();
            }));
            List<String> now = values(path);
            if (removed.stream().anyMatch(now::contains)) {
                throw unlike("remove " + removed + " from " + path + " of " + name, now);
            }
            return this;
        }

        /**
         * Links an account on the resource (by its name there) to this focus: the object on the target system (added
         * with the shadow's attributes if it is not there), its shadow (added if the resource has no live one of that
         * name) and the link.
         */
        public RawObject link(String resource, String account) {
            return link(resource, account, shadow -> { });
        }

        /** {@link #link(String, String)} with the shadow's object type, tag and attributes. */
        public RawObject link(String resource, String account, Consumer<NewShadow> shadow) {
            FocusType focus = existingFocus();
            NewShadow spec = new NewShadow();
            shadow.accept(spec);
            Target target = target(resource, spec);
            if (target.system.find(target.objectClass.getLocalPart(), account) == null) {
                target.system.addObject(target.objectClass.getLocalPart(), account, spec.attributes);
            }
            String oid = live(resource, account);
            if (oid == null) {
                oid = addShadow(resource, account, spec, target, true);
            }
            String shadowOid = oid;
            if (focus.getLinkRef().stream().noneMatch(link -> shadowOid.equals(link.getOid()))) {
                modify(focus, built(() -> api().prismContext().deltaFor(type).item(FocusType.F_LINK_REF)
                        .add(new ObjectReferenceType().oid(shadowOid).type(ShadowType.COMPLEX_TYPE))
                        .asItemDeltas()));
            }
            if (target.system.find(target.objectClass.getLocalPart(), account) == null) {
                throw new IllegalStateException("raw: link " + account + " on " + resource + " to " + name
                        + ", but the target system has no " + account);
            }
            if (!links(shadowOid)) {
                throw new IllegalStateException("raw: link " + account + " on " + resource + " to " + name
                        + ", but " + name + " does not link its shadow " + shadowOid);
            }
            return this;
        }

        /** Removes the link to the account on the resource; its shadow and the object on the target system stay. */
        public RawObject unlink(String resource, String account) {
            FocusType focus = existingFocus();
            String resourceOid = resourceOid(resource);
            List<String> oids = new ArrayList<>();
            for (ObjectReferenceType link : focus.getLinkRef()) {
                ShadowType shadow = api().find(ShadowType.class, link.getOid());
                if (shadow != null && shadow.getResourceRef() != null
                        && resourceOid.equals(shadow.getResourceRef().getOid())
                        && account.equals(shadow.getName().getOrig())) {
                    oids.add(link.getOid());
                }
            }
            if (oids.isEmpty()) {
                throw new IllegalStateException("raw: " + name + " links no " + account + " on " + resource);
            }
            List<ItemDelta<?, ?>> deltas = new ArrayList<>();
            for (String oid : oids) {
                deltas.addAll(built(() -> api().prismContext().deltaFor(type).item(FocusType.F_LINK_REF)
                        .delete(new ObjectReferenceType().oid(oid).type(ShadowType.COMPLEX_TYPE)).asItemDeltas()));
            }
            modify(focus, deltas);
            for (String oid : oids) {
                if (links(oid)) {
                    throw new IllegalStateException("raw: unlink " + account + " on " + resource + " from " + name
                            + ", but " + name + " still links " + oid);
                }
            }
            return this;
        }

        private boolean links(String shadowOid) {
            return existingFocus().getLinkRef().stream().anyMatch(link -> shadowOid.equals(link.getOid()));
        }

        private List<String> values(String path) {
            return Values.texts(realValues(existing().asPrismObject(), path));
        }

        private void modify(ObjectType object, Collection<? extends ItemDelta<?, ?>> deltas) {
            @SuppressWarnings("unchecked")
            Class<ObjectType> objectType = (Class<ObjectType>) type;
            api().modifyRaw(objectType, object.getOid(), deltas);
        }

        private ObjectType existing() {
            ObjectType object = api().find(type, name);
            if (object == null) {
                throw new IllegalArgumentException("raw: no " + type.getSimpleName() + " " + name);
            }
            return object;
        }

        private FocusType existingFocus() {
            if (!FocusType.class.isAssignableFrom(type)) {
                throw new IllegalArgumentException("raw: a " + type.getSimpleName() + " links no accounts");
            }
            return (FocusType) existing();
        }

        private IllegalStateException unlike(String what, List<String> now) {
            return new IllegalStateException("raw: " + what + ", but the repository has " + now);
        }
    }

    /** A shadow of {@link Raw}, by its resource and name. */
    public final class RawShadow {

        private final String resource;
        private final String name;

        private RawShadow(String resource, String name) {
            this.resource = resource;
            this.name = name;
        }

        /**
         * Adds the shadow, with no owner: of the object on the target system with this name (its identifier), else of
         * none (the name as the identifier). The target system is not changed.
         */
        public RawShadow add() {
            return add(shadow -> { });
        }

        /** {@link #add()} with the shadow's object type and tag. */
        public RawShadow add(Consumer<NewShadow> shadow) {
            NewShadow spec = new NewShadow();
            shadow.accept(spec);
            addShadow(resource, name, spec, target(resource, spec), false);
            return this;
        }

        /** Deletes the resource's shadows with this name; the links to them and the target system's object stay. */
        public RawShadow delete() {
            List<ShadowType> shadows = api().shadows(resource, name);
            if (shadows.isEmpty()) {
                throw new IllegalStateException("raw: no shadow " + name + " on " + resource);
            }
            for (ShadowType shadow : shadows) {
                api().deleteRaw(ShadowType.class, shadow.getOid());
            }
            if (!api().shadows(resource, name).isEmpty()) {
                throw new IllegalStateException("raw: delete the shadow " + name + " on " + resource
                        + ", but the repository still has it");
            }
            return this;
        }
    }

    // ---- shadows ------------------------------------------------------------------------------------------------

    /** The target system of a resource and the object class of the shadow's object type. */
    private static final class Target {
        final TargetSystem system;
        final ResourceObjectTypeDefinitionType type;
        final QName objectClass;

        Target(TargetSystem system, ResourceObjectTypeDefinitionType type, QName objectClass) {
            this.system = system;
            this.type = type;
            this.objectClass = objectClass;
        }
    }

    private Target target(String resource, NewShadow spec) {
        ResourceType object = api().find(ResourceType.class, resourceOid(resource));
        ResourceObjectTypeDefinitionType type = objectType(object, spec);
        QName objectClass = type.getObjectClass();
        if (objectClass == null) {
            throw new IllegalArgumentException("raw: the object type " + type.getKind() + "/" + type.getIntent()
                    + " of " + resource + " names no object class");
        }
        return new Target(api().target(resource), type, objectClass);
    }

    /** The resource's object type of the kind (account by default) and intent (the kind's default one by default). */
    private static ResourceObjectTypeDefinitionType objectType(ResourceType resource, NewShadow spec) {
        ShadowKindType kind = spec.kind != null ? ShadowKindType.fromValue(spec.kind) : ShadowKindType.ACCOUNT;
        List<ResourceObjectTypeDefinitionType> ofKind = new ArrayList<>();
        if (resource.getSchemaHandling() != null) {
            for (ResourceObjectTypeDefinitionType type : resource.getSchemaHandling().getObjectType()) {
                if ((type.getKind() != null ? type.getKind() : ShadowKindType.ACCOUNT) == kind) {
                    ofKind.add(type);
                }
            }
        }
        for (ResourceObjectTypeDefinitionType type : ofKind) {
            if (spec.intent != null ? spec.intent.equals(intent(type))
                    : Boolean.TRUE.equals(type.isDefault()) || ofKind.size() == 1 || "default".equals(intent(type))) {
                return type;
            }
        }
        throw new IllegalArgumentException("raw: " + resource.getName().getOrig() + " has no object type "
                + kind.value() + "/" + (spec.intent != null ? spec.intent : "(default)") + "; it has "
                + ofKind.stream().map(Raw::intent).collect(java.util.stream.Collectors.toList()));
    }

    private static String intent(ResourceObjectTypeDefinitionType type) {
        return type.getIntent() != null ? type.getIntent() : "default";
    }

    /** The OID of the resource's live shadow with this name, or null. */
    private String live(String resource, String name) {
        for (ShadowType shadow : api().shadows(resource, name)) {
            if (!Boolean.TRUE.equals(shadow.isDead())) {
                return shadow.getOid();
            }
        }
        return null;
    }

    /**
     * Adds a shadow of the object type, named as the object on the target system, with its identifiers: the primary
     * one the target system's identifier of the object (the name if there is none), the secondary ones the name.
     */
    /**
     * Adds a shadow of the resource's object type; a linked one as midPoint keeps a projection it links (iteration 0,
     * an empty iteration token, the situation linked), so that its next recompute finds nothing of its own to add.
     */
    private String addShadow(String resource, String name, NewShadow spec, Target target, boolean linked) {
        MidPoint api = api();
        PrismObject<ShadowType> shadow;
        try {
            shadow = api.prismContext().getSchemaRegistry().findObjectDefinitionByCompileTimeClass(ShadowType.class)
                    .instantiate();
            shadow.findOrCreateContainer(ShadowType.F_ATTRIBUTES);
        } catch (SchemaException e) {
            throw new IllegalStateException("raw: cannot make a shadow", e);
        }
        shadow.setOid(UUID.randomUUID().toString());
        ShadowType bean = shadow.asObjectable();
        bean.setName(new PolyStringType(name));
        bean.setResourceRef(new ObjectReferenceType().oid(resourceOid(resource)).type(ResourceType.COMPLEX_TYPE));
        bean.setObjectClass(target.objectClass);
        bean.setKind(target.type.getKind() != null ? target.type.getKind() : ShadowKindType.ACCOUNT);
        bean.setIntent(intent(target.type));
        bean.setTag(spec.tag);
        if (linked) {
            bean.setIteration(0);
            bean.setIterationToken("");
            bean.setSynchronizationSituation(
                    com.evolveum.midpoint.xml.ns._public.common.common_3.SynchronizationSituationType.LINKED);
        }
        String identifier = target.system.find(target.objectClass.getLocalPart(), name) != null
                ? target.system.identifier(target.objectClass.getLocalPart(), name) : name;
        bean.setPrimaryIdentifierValue(identifier);
        api.applyDefinitions(shadow);
        PrismContainer<?> attributes = shadow.findContainer(ShadowType.F_ATTRIBUTES);
        PrismContainerDefinition<?> definition = attributes.getDefinition();
        for (ItemDefinition<?> attribute : identifiers(definition, "getPrimaryIdentifiers")) {
            put(attributes.getValue(), attribute, identifier);
        }
        for (ItemDefinition<?> attribute : identifiers(definition, "getSecondaryIdentifiers")) {
            if (attributes.getValue().findItem(attribute.getItemName()) == null) {
                put(attributes.getValue(), attribute, name);
            }
        }
        api.addRaw(shadow);
        if (api.find(ShadowType.class, shadow.getOid()) == null) {
            throw new IllegalStateException("raw: add the shadow " + name + " on " + resource
                    + ", but the repository has no " + shadow.getOid());
        }
        return shadow.getOid();
    }

    private static void put(PrismContainerValue<?> attributes, ItemDefinition<?> definition, String value) {
        @SuppressWarnings("unchecked")
        PrismProperty<Object> property = ((PrismPropertyDefinition<Object>) definition).instantiate();
        property.setRealValue(value);
        try {
            attributes.add(property);
        } catch (SchemaException e) {
            throw new IllegalStateException("raw: cannot set " + definition.getItemName() + " of a shadow", e);
        }
    }

    /**
     * The identifier attributes of the shadow's attributes ({@code getPrimaryIdentifiers}, {@code
     * getSecondaryIdentifiers}): on the attributes' definition, or on the object class or object type behind it (the
     * lines differ).
     */
    private static Collection<ItemDefinition<?>> identifiers(Object definition, String getter) {
        for (Object on : new Object[] { definition, call(definition, "getComplexTypeDefinition"),
                call(definition, "getResourceObjectDefinition") }) {
            Object found = call(on, getter);
            if (found instanceof Collection) {
                List<ItemDefinition<?>> out = new ArrayList<>();
                for (Object attribute : (Collection<?>) found) {
                    out.add((ItemDefinition<?>) attribute);
                }
                return out;
            }
        }
        throw new IllegalStateException("raw: cannot find the identifiers of the shadow (" + definition + ")");
    }

    private static Object call(Object on, String method) {
        if (on == null) {
            return null;
        }
        try {
            return on.getClass().getMethod(method).invoke(on);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ---- items ------------------------------------------------------------------------------------------------

    /** The real values at the path, through every value of a multi-valued container on the way, as {@link User}. */
    private static List<Object> realValues(PrismObject<?> object, String path) {
        List<PrismContainerValue<?>> containers = new ArrayList<>();
        containers.add(object.getValue());
        String[] segments = path.split("/");
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            List<PrismContainerValue<?>> next = new ArrayList<>();
            for (PrismContainerValue<?> container : containers) {
                Item<?, ?> item = container.findItem(ItemPath.create(new QName(segments[i])));
                if (item == null) {
                    continue;
                }
                if (i == segments.length - 1) {
                    out.addAll(item instanceof com.evolveum.midpoint.prism.PrismContainer
                            && ((com.evolveum.midpoint.prism.PrismContainer<?>) item).getCompileTimeClass() == null
                            ? item.getValues() : item.getRealValues());
                } else {
                    for (Object value : item.getValues()) {
                        if (value instanceof PrismContainerValue) {
                            next.add((PrismContainerValue<?>) value);
                        }
                    }
                }
            }
            containers = next;
        }
        return out;
    }

    /** The item at the path of local names, in the object's definitions. */
    private static ItemValues.Located located(PrismObject<?> object, String path) {
        ItemValues.Located item = ItemValues.locate(object.getDefinition(), path,
                "raw: a " + object.getCompileTimeClass().getSimpleName());
        if (item.throughMultiValuedContainer) {
            throw new IllegalArgumentException("raw: " + path + " is in a multi-valued container: set the whole value "
                    + "(Struct.of(...)), not a field");
        }
        return item;
    }


    /** Builds deltas; a schema error is a mistake of the test. */
    private static List<ItemDelta<?, ?>> built(DeltaBuilder builder) {
        try {
            return new ArrayList<>(builder.build());
        } catch (SchemaException e) {
            throw new IllegalArgumentException("raw: " + e.getMessage(), e);
        }
    }

    private interface DeltaBuilder {
        Collection<? extends ItemDelta<?, ?>> build() throws SchemaException;
    }

    private String resourceOid(String resource) {
        String oid = api().target(resource).resourceOid();
        return Objects.requireNonNull(oid, resource);
    }

    private MidPoint api() {
        return tester.deployment().api();
    }
}
