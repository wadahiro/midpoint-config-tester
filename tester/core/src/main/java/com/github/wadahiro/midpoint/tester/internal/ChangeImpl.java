package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.github.wadahiro.midpoint.tester.Change;
import com.github.wadahiro.midpoint.tester.Run;

/** {@link Change} on {@link MidPointImpl}: item deltas collected into one modify delta. */
final class ChangeImpl<O extends ObjectType> implements Change {

    private enum Kind { REPLACE, ADD, DELETE }

    private final MidPointImpl mp;
    private final Class<O> type;
    private final String oid;
    private final String what;
    private final List<ItemDelta<?, ?>> modifications = new ArrayList<>();
    private ModelExecuteOptions options;

    ChangeImpl(MidPointImpl mp, Class<O> type, String oid, String what) {
        this.mp = mp;
        this.type = type;
        this.oid = oid;
        this.what = what;
    }

    @Override
    public Change replace(String path, Object... values) {
        return replace(path(path), values);
    }

    @Override
    public Change replace(ItemPath path, Object... values) {
        return item(Kind.REPLACE, path, values);
    }

    @Override
    public Change add(String path, Object... values) {
        return add(path(path), values);
    }

    @Override
    public Change add(ItemPath path, Object... values) {
        return item(Kind.ADD, path, values);
    }

    @Override
    public Change delete(String path, Object... values) {
        return delete(path(path), values);
    }

    @Override
    public Change delete(ItemPath path, Object... values) {
        return item(Kind.DELETE, path, values);
    }

    @Override
    public Change assign(String target) {
        return assign(target, null);
    }

    @Override
    public Change assign(String target, QName relation) {
        AbstractRoleType role = mp.existing(AbstractRoleType.class, target);
        AssignmentType assignment = new AssignmentType()
                .targetRef(new ObjectReferenceType()
                        .oid(role.getOid())
                        .type(ObjectTypes.getObjectType(role.getClass()).getTypeQName())
                        .relation(relation));
        // with its definition: 4.0 previews (approvals) fail on a value without one
        try {
            PrismContainerDefinition<AssignmentType> definition = prismContext().getSchemaRegistry()
                    .findContainerDefinitionByCompileTimeClass(AssignmentType.class);
            assignment.asPrismContainerValue().applyDefinition(definition, false);
        } catch (SchemaException e) {
            throw new IllegalStateException("Cannot apply the definition of an assignment", e);
        }
        return item(Kind.ADD, AssignmentHolderType.F_ASSIGNMENT, assignment);
    }

    @Override
    public Change unassign(String target) {
        return unassign(target, null);
    }

    @Override
    public Change unassign(String target, QName relation) {
        String targetOid = mp.existing(AbstractRoleType.class, target).getOid();
        O object = mp.existing(type, oid);
        if (!(object instanceof AssignmentHolderType)) {
            throw new IllegalArgumentException(type.getSimpleName() + " has no assignments");
        }
        QName wanted = relation != null ? relation : SchemaConstants.ORG_DEFAULT;
        List<Object> found = new ArrayList<>();
        for (AssignmentType assignment : ((AssignmentHolderType) object).getAssignment()) {
            ObjectReferenceType ref = assignment.getTargetRef();
            if (ref != null && targetOid.equals(ref.getOid()) && QNameUtil.match(wanted,
                    ref.getRelation() != null ? ref.getRelation() : SchemaConstants.ORG_DEFAULT)) {
                found.add(assignment.clone());
            }
        }
        if (found.isEmpty()) {
            throw new AssertionError(what + " has no assignment of " + target + " (relation " + wanted.getLocalPart()
                    + ")");
        }
        return item(Kind.DELETE, AssignmentHolderType.F_ASSIGNMENT, found.toArray());
    }

    @Override
    public Change options(ModelExecuteOptions options) {
        this.options = options;
        return this;
    }

    @Override
    public ObjectDelta<O> delta() {
        ObjectDelta<O> delta = prismContext().deltaFactory().object().createEmptyModifyDelta(type, oid);
        for (ItemDelta<?, ?> modification : modifications) {
            delta.addModification(modification.clone());
        }
        return delta;
    }

    @Override
    public Run execute() {
        return mp.execute(List.of(delta()), options, true);
    }

    @Override
    public Run tryExecute() {
        return mp.execute(List.of(delta()), options, false);
    }

    @Override
    public Run preview() {
        return mp.preview(delta());
    }

    private Change item(Kind kind, ItemPath path, Object... values) {
        ItemDefinition<?> definition = objectDefinition().findItemDefinition(path);
        if (definition == null) {
            throw new IllegalArgumentException("No item " + path + " in " + type.getSimpleName());
        }
        Object[] converted = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            converted[i] = convert(prismContext(), definition, values[i]);
        }
        try {
            var entry = prismContext().deltaFor(type).item(path, definition);
            ItemDelta<?, ?> delta;
            switch (kind) {
                case REPLACE:
                    delta = entry.replace(converted).asItemDelta();
                    break;
                case ADD:
                    delta = entry.add(converted).asItemDelta();
                    break;
                default:
                    delta = entry.delete(converted).asItemDelta();
            }
            modifications.add(delta);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot build the modification of " + path, e);
        }
        return this;
    }

    /** Plain strings for polystrings and enumerations (by XML value); other values as given. */
    private static Object convert(PrismContext prismContext, ItemDefinition<?> definition, Object value) {
        if (!(value instanceof String)) {
            return value;
        }
        if (PolyStringType.COMPLEX_TYPE.equals(definition.getTypeName())) {
            return PolyString.fromOrig((String) value);
        }
        Class<?> type = prismContext.getSchemaRegistry().determineClassForType(definition.getTypeName());
        if (type != null && type.isEnum()) {
            try {
                Method fromValue = type.getMethod("fromValue", String.class);
                return fromValue.invoke(null, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Not a value of " + type.getSimpleName() + ": " + value, e);
            }
        }
        return value;
    }

    /** A path given by element names, each matched against the definitions (which give the namespaces). */
    private ItemPath path(String path) {
        List<Object> segments = new ArrayList<>();
        PrismContainerDefinition<?> container = objectDefinition();
        for (String name : path.split("/")) {
            if (container == null) {
                throw new IllegalArgumentException("No item " + path + " in " + type.getSimpleName());
            }
            ItemDefinition<?> item = container.findItemDefinition(new ItemName(name));
            if (item == null) {
                throw new IllegalArgumentException("No item " + name + " of " + path + " in " + type.getSimpleName());
            }
            segments.add(item.getItemName());
            container = item instanceof PrismContainerDefinition ? (PrismContainerDefinition<?>) item : null;
        }
        return ItemPath.create(segments);
    }

    private PrismContainerDefinition<O> objectDefinition() {
        return prismContext().getSchemaRegistry().findObjectDefinitionByCompileTimeClass(type);
    }

    private PrismContext prismContext() {
        return mp.prismContext();
    }
}
