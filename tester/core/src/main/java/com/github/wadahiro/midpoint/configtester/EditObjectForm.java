package com.github.wadahiro.midpoint.configtester;

import java.util.Map;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * The GUI's form of an existing object ({@link MidPointConfigTester#editForm}, as a user: {@link Actor#editForm}): its
 * details page opened by its OID, as the GUI opens an object from a list. The fields show the stored values, and a
 * field's validator sees them in {@code object} (not the value being typed); saving executes the changes of the
 * fields typed into. What the page does is {@link ObjectForm}'s.
 *
 * <pre>{@code
 * t.editForm(OrgType::class.java, "cat-1").validate("costCenter", "CC2").fails("The cost center is set already.")
 * t.actor("site-admin").editForm(OrgType::class.java, "cat-1").set("displayName", "Category 1").submit().saves()
 * t.actor("site-admin").editForm(Orgs.SITE_1).set("extension/tags", listOf("a", "b")).submit().saves()
 * t.actor("site-admin").editForm(Orgs.SITE_1).add("extension/tags", "c").remove("extension/tags", "a").submit().saves()
 * t.actor(Users.HELPDESK).editForm(Orgs.SITE_1).tabs().hasTab("Members").hasNoTab("Governance")
 * }</pre>
 */
public final class EditObjectForm extends ObjectForm {

    private final String oid;
    private final String name;

    EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String nameOrOid, String user) {
        this(tester, type, find(tester, type, nameOrOid), user, Map.of());
    }

    private EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, ObjectType object,
            String user, Map<String, Object> items) {
        this(tester, type, object.getOid(), object.getName() != null ? object.getName().getOrig() : object.getOid(),
                user, items);
    }

    private EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String oid, String name,
            String user, Map<String, Object> items) {
        super(tester, type, user, items);
        this.oid = oid;
        this.name = name;
    }

    private static ObjectType find(MidPointConfigTester tester, Class<? extends ObjectType> type, String nameOrOid) {
        ObjectType object = tester.deployment().api().find(type, nameOrOid);
        if (object == null) {
            throw new IllegalArgumentException("No " + type.getSimpleName() + " " + nameOrOid);
        }
        return object;
    }

    /**
     * A value typed into the item's field first, as the user changes the form before the item validated or saving. A
     * collection is the values of a multi-valued item, exactly these: the rows of the values not in it removed, the
     * missing ones typed into an empty row or a row the item's add button adds, the others kept
     * ({@code set("extension/tags", listOf("a", "b"))}); an empty collection removes every value.
     * The value as the item's type has it, typed as the field shows it: a number or its text, a date
     * ({@code Instant}, {@code ZonedDateTime}, {@code XMLGregorianCalendar}, its ISO text; to the minute), a
     * duration, a drop-down's choice (a boolean, an enumeration, an object type's QName), a lookup table's key or
     * label; a reference or a password fails, naming what to use instead
     * (docs/guide/gui-and-authorizations.md).
     */
    public EditObjectForm set(String item, Object value) {
        return new EditObjectForm(tester, type, oid, name, user, with(items, item, value));
    }

    /**
     * A value added to a multi-valued item, as the user does it: typed into an empty row, else into a row the item's
     * add button adds; a value the item has already is kept as it is. With {@link #remove} and other {@code add}s of
     * the item: its values removed first, then those added.
     */
    public EditObjectForm add(String item, Object value) {
        return new EditObjectForm(tester, type, oid, name, user,
                with(items, item, GuiForms.ValueRows.add(items.get(item), value)));
    }

    /**
     * A value of a multi-valued item removed, as the user does it: its row's remove button pressed (the form fails
     * when the item has no such value).
     */
    public EditObjectForm remove(String item, Object value) {
        return new EditObjectForm(tester, type, oid, name, user,
                with(items, item, GuiForms.ValueRows.remove(items.get(item), value)));
    }

    @Override
    GuiForms.Spec spec() {
        return new GuiForms.Spec(type, oid, null, null, null, null, user, items);
    }

    @Override
    String page() {
        return kind() + " " + name;
    }
}
