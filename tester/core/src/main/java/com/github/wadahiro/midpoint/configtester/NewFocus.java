package com.github.wadahiro.midpoint.configtester;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.tester.internal.ItemValues;

/**
 * A focus to add through the model ({@link User#add(Consumer)}, {@link Role#add(Consumer)}...): its name, and what the
 * test gives it besides; the deployment's object templates and policies do the rest when it is added. A focus that
 * midPoint names ({@link MidPointConfigTester#addOrg}...) has no name here, as one an administrator saves in the GUI
 * with the name left empty: {@code set("name", ...)} gives a name the object template replaces. Items by path as
 * {@link Focus#hasItem} names them (extension items too) or by the generated name, their values read as the item's
 * type ({@code "5"} for an int, an {@link Instant} or {@code "2030-04-01T09:00:00+09:00"} for a date).
 *
 * @param <B> the class itself, which the words return
 * @param <O> the object's type
 */
public abstract class NewFocus<B extends NewFocus<B, O>, O extends FocusType> {

    final MidPointConfigTester tester;
    final Class<O> type;
    /** The focus's name; null for a focus midPoint names. */
    final String name;
    private final PrismObject<O> object;
    private final List<Consumer<? super O>> edits = new ArrayList<>();

    NewFocus(MidPointConfigTester tester, Class<O> type, String name) {
        this.tester = tester;
        this.type = type;
        this.name = name;
        // from the definition: a bean made with new has no prism context on 4.0
        PrismObjectDefinition<O> definition = prismContext().getSchemaRegistry()
                .findObjectDefinitionByCompileTimeClass(type);
        try {
            object = definition.instantiate();
        } catch (SchemaException e) {
            throw new IllegalStateException("Cannot make a " + type.getSimpleName(), e);
        }
        if (name != null) {
            if (OID.matcher(name).matches()) {
                throw new IllegalArgumentException(name + " is an OID, not a name: a focus midPoint names is added by "
                        + "t.add" + type.getSimpleName().replace("Type", "") + "(...), with oid(...) for a fixed OID");
            }
            object.asObjectable().setName(new PolyStringType(name));
        }
    }

    private static final java.util.regex.Pattern OID = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** The focus as the messages name it. */
    String what() {
        return name != null ? name : "the new " + type.getSimpleName().replace("Type", "").toLowerCase(
                java.util.Locale.ROOT);
    }

    abstract B self();

    /** The item's values (no values: none). */
    public B set(String path, Object... values) {
        ItemValues.Located item = ItemValues.locate(object.getDefinition(), path, type.getSimpleName());
        if (item.throughMultiValuedContainer) {
            throw new IllegalArgumentException(path + " is in a multi-valued container: set the whole value");
        }
        Object[] typed = Struct.values(prismContext(), path, item.definition, values);
        apply(path, () -> {
            var entry = prismContext().deltaFor(type).item(item.path, item.definition);
            Object[] prism = ItemValues.forDelta(typed);
            return (prism instanceof com.evolveum.midpoint.prism.PrismValue[]
                    ? entry.replace((com.evolveum.midpoint.prism.PrismValue[]) prism) : entry.replace(typed))
                    .asItemDeltas();
        });
        return self();
    }

    /** {@link #set(String, Object...)} by the generated item ({@code UserExtension.SITE}). */
    public B set(ItemRef item, Object... values) {
        return set(path(item), values);
    }

    /** An assignment of the role, org, service or archetype of this name. */
    public B assign(String target) {
        return assign(target, null);
    }

    /** An assignment with this relation ({@code SchemaConstants.ORG_MANAGER}). */
    public B assign(String target, QName relation) {
        AbstractRoleType role = tester.deployment().api().find(AbstractRoleType.class, target);
        if (role == null) {
            throw new IllegalArgumentException("No role, org, service or archetype " + target + " to assign to "
                    + what());
        }
        AssignmentType assignment = new AssignmentType()
                .targetRef(new ObjectReferenceType().oid(role.getOid())
                        .type(ObjectTypes.getObjectType(role.getClass()).getTypeQName()).relation(relation));
        try {
            assignment.asPrismContainerValue().applyDefinition(prismContext().getSchemaRegistry()
                    .findContainerDefinitionByCompileTimeClass(AssignmentType.class), false);
        } catch (SchemaException e) {
            throw new IllegalStateException("Cannot apply the definition of an assignment", e);
        }
        apply("assignment", () -> prismContext().deltaFor(type).item(FocusType.F_ASSIGNMENT).add(assignment)
                .asItemDeltas());
        return self();
    }

    public B assign(AssignableRef target) {
        return assign(target.on(tester.line()));
    }

    public B assign(AssignableRef target, QName relation) {
        return assign(target.on(tester.line()), relation);
    }

    public B lifecycleState(String state) {
        return set("lifecycleState", state);
    }

    /** The administrative status disabled. */
    public B disabled() {
        return set("activation/administrativeStatus", "disabled");
    }

    public B validFrom(Instant from) {
        return set("activation/validFrom", from);
    }

    public B validTo(Instant to) {
        return set("activation/validTo", to);
    }

    /** The OID the object is added with; by default a new one. */
    public B oid(String oid) {
        object.setOid(oid);
        return self();
    }

    /** The object as it will be added, for what the words do not cover (an inducement, an authorization). */
    public B object(Consumer<? super O> edit) {
        edits.add(edit);
        return self();
    }

    // ---- for the focus ----------------------------------------------------------------------------------------

    O build() {
        O built = object.asObjectable();
        for (Consumer<? super O> edit : edits) {
            edit.accept(built);
        }
        return built;
    }

    String path(ItemRef item) {
        if (!type.getSimpleName().equals(item.ownerType())) {
            throw new IllegalArgumentException(item + " is not an item of " + type.getSimpleName());
        }
        return item.on(tester.line());
    }

    PrismContext prismContext() {
        return tester.deployment().api().prismContext();
    }

    /** The item deltas a builder makes. */
    interface Deltas {
        java.util.Collection<? extends ItemDelta<?, ?>> build() throws SchemaException;
    }

    /** Applies the deltas to the object to add; a schema error is a mistake of the test. */
    private void apply(String path, Deltas deltas) {
        try {
            for (ItemDelta<?, ?> delta : deltas.build()) {
                delta.applyTo(object);
            }
        } catch (SchemaException e) {
            throw new IllegalArgumentException(path + " of " + what() + ": " + e.getMessage(), e);
        }
    }
}
