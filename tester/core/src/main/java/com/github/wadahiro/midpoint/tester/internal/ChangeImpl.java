package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.prism.xml.XmlTypeConverter;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
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
        return assign(target, relation, null, null);
    }

    @Override
    public Change assign(String target, QName relation, java.time.Instant validFrom, java.time.Instant validTo) {
        AbstractRoleType role = mp.existing(AbstractRoleType.class, target);
        AssignmentType assignment = new AssignmentType()
                .targetRef(new ObjectReferenceType()
                        .oid(role.getOid())
                        .type(ObjectTypes.getObjectType(role.getClass()).getTypeQName())
                        .relation(relation));
        if (validFrom != null || validTo != null) {
            ActivationType activation = new ActivationType();
            if (validFrom != null) {
                activation.setValidFrom(XmlTypeConverter.createXMLGregorianCalendar(validFrom.toEpochMilli()));
            }
            if (validTo != null) {
                activation.setValidTo(XmlTypeConverter.createXMLGregorianCalendar(validTo.toEpochMilli()));
            }
            assignment.setActivation(activation);
        }
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
        Object[] converted = ItemValues.typed(prismContext(), path.toString(), definition, values);
        try {
            var entry = prismContext().deltaFor(type).item(path, definition);
            ItemDelta<?, ?> delta;
            Object[] forDelta = ItemValues.forDelta(converted);
            boolean prism = forDelta instanceof com.evolveum.midpoint.prism.PrismValue[];
            com.evolveum.midpoint.prism.PrismValue[] prismValues = prism
                    ? (com.evolveum.midpoint.prism.PrismValue[]) forDelta : null;
            switch (kind) {
                case REPLACE:
                    delta = (prism ? entry.replace(prismValues) : entry.replace(converted)).asItemDelta();
                    break;
                case ADD:
                    delta = (prism ? entry.add(prismValues) : entry.add(converted)).asItemDelta();
                    break;
                default:
                    delta = (prism ? entry.delete(prismValues) : entry.delete(converted)).asItemDelta();
            }
            modifications.add(delta);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot build the modification of " + path, e);
        }
        return this;
    }

    /** A path given by element names, each matched against the definitions (which give the namespaces). */
    private ItemPath path(String path) {
        return ItemValues.locate(objectDefinition(), path, type.getSimpleName()).path;
    }

    private PrismContainerDefinition<O> objectDefinition() {
        return prismContext().getSchemaRegistry().findObjectDefinitionByCompileTimeClass(type);
    }

    private PrismContext prismContext() {
        return mp.prismContext();
    }
}
