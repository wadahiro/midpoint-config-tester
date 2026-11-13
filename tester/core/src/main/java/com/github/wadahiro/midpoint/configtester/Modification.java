package com.github.wadahiro.midpoint.configtester;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CredentialsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PasswordType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.tester.Change;
import com.github.wadahiro.midpoint.tester.internal.ItemValues;

/**
 * A modification of a focus through the model ({@link Focus#modify}), as an administrator saves a form in the GUI:
 * the items and assignments it changes, in one delta. Items by path as {@link Focus#hasItem} names them or by the
 * generated name, their values read as the items' types; a structured item's values as {@link Struct}s:
 *
 * <pre>{@code
 * t.user("jdoe").modify(m -> m.set("telephoneNumber", "+1 555").addValues("extension/tags", "vip")
 *         .assign("IT").unassign("Sales"));
 * t.user("jdoe").modify(m -> m.removeValues("extension/address", Struct.of("street", "Side", "city", "Brno")));
 * t.user("jdoe").modify(m -> m.set("costCenter", "IT").reconcile());
 * }</pre>
 */
public final class Modification {

    private final Focus<?> focus;
    private final Change change;
    private boolean reconcile;

    Modification(Focus<?> focus) {
        this.focus = focus;
        focus.existing();
        this.change = focus.tester.deployment().api().change(focus.type, focus.name);
    }

    /** Replaces the item's values (no values: empties it). */
    public Modification set(String path, Object... values) {
        ItemValues.Located item = located(path);
        change.replace(item.path, Struct.values(prismContext(), path, item.definition, values));
        return this;
    }

    public Modification set(ItemRef item, Object... values) {
        return set(path(item), values);
    }

    /** Adds the values to the item, keeping the ones it has. */
    public Modification addValues(String path, Object... values) {
        ItemValues.Located item = located(path);
        change.add(item.path, Struct.values(prismContext(), path, item.definition, values));
        return this;
    }

    public Modification addValues(ItemRef item, Object... values) {
        return addValues(path(item), values);
    }

    /**
     * Removes the values from the item, keeping the others; a structured value is the one the item has with exactly
     * these fields (none: it fails, listing the values it has).
     */
    public Modification removeValues(String path, Object... values) {
        ItemValues.Located item = located(path);
        Object[] typed = Struct.values(prismContext(), path, item.definition, values);
        typed = Struct.byIds(path, item.definition, focus.existing().asPrismObject().findItem(item.path), typed);
        change.delete(item.path, typed);
        return this;
    }

    public Modification removeValues(ItemRef item, Object... values) {
        return removeValues(path(item), values);
    }

    /** Assigns the role, org, service or archetype of this name. */
    public Modification assign(String target) {
        return assign(target, null);
    }

    public Modification assign(String target, QName relation) {
        change.assign(existingTarget(target), relation);
        return this;
    }

    public Modification assign(AssignableRef target) {
        return assign(target.on(focus.tester.line()));
    }

    public Modification assign(AssignableRef target, QName relation) {
        return assign(target.on(focus.tester.line()), relation);
    }

    /** Removes the assignments of the target with the default relation; fails if there is none. */
    public Modification unassign(String target) {
        return unassign(target, null);
    }

    public Modification unassign(String target, QName relation) {
        change.unassign(existingTarget(target), relation);
        return this;
    }

    public Modification unassign(AssignableRef target) {
        return unassign(target.on(focus.tester.line()));
    }

    public Modification unassign(AssignableRef target, QName relation) {
        return unassign(target.on(focus.tester.line()), relation);
    }

    /**
     * The user's new password, as clear text; midPoint checks it against the password policy (a value that violates
     * it fails: {@link MidPointConfigTester#expectFailure}).
     */
    public Modification password(String password) {
        if (focus.type != UserType.class) {
            throw new IllegalArgumentException("A password is a user's: " + focus + " has none");
        }
        com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType value =
                new com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType();
        value.setClearValue(password);
        change.replace(ItemPath.create(UserType.F_CREDENTIALS, CredentialsType.F_PASSWORD, PasswordType.F_VALUE),
                value);
        return this;
    }

    /**
     * midPoint reconciles the focus's projections with the change (the model's reconcile option): what the target
     * systems have is read and set as the mappings say. Alone, {@code modify(Modification::reconcile)} reconciles the
     * focus.
     */
    public Modification reconcile() {
        reconcile = true;
        return this;
    }

    // ---- for the focus ----------------------------------------------------------------------------------------

    void execute() {
        if (reconcile) {
            change.options(ModelExecuteOptions.createReconcile());
        }
        change.execute();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private ItemValues.Located located(String path) {
        ItemValues.Located item = ItemValues.locate(prismContext().getSchemaRegistry()
                .findObjectDefinitionByCompileTimeClass(focus.type), path, focus.type.getSimpleName());
        if (item.throughMultiValuedContainer) {
            throw new IllegalArgumentException(path + " is in a multi-valued container: set the whole value "
                    + "(Struct.of(...)), not a field");
        }
        return item;
    }

    private String existingTarget(String target) {
        if (focus.tester.deployment().api().find(AbstractRoleType.class, target) == null) {
            throw new IllegalArgumentException("No role, org, service or archetype " + target + " to (un)assign");
        }
        return target;
    }

    private String path(ItemRef item) {
        if (!focus.type.getSimpleName().equals(item.ownerType())) {
            throw new IllegalArgumentException(item + " is not an item of " + focus.type.getSimpleName());
        }
        return item.on(focus.tester.line());
    }

    private PrismContext prismContext() {
        return focus.tester.deployment().api().prismContext();
    }
}
