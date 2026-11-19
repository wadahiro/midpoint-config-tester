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
 * }</pre>
 */
public final class EditObjectForm extends ObjectForm {

    private final String oid;

    EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String nameOrOid, String user) {
        this(tester, type, oidOf(tester.deployment().api(), type, nameOrOid), user, Map.of());
    }

    private EditObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String oid, String user,
            Map<String, Object> items) {
        super(tester, type, user, items);
        this.oid = oid;
    }

    /** A value typed into the item's field first, as the user changes the form before the item validated or saving. */
    public EditObjectForm set(String item, Object value) {
        return new EditObjectForm(tester, type, oid, user, with(items, item, value));
    }

    @Override
    GuiForms.Spec spec() {
        return new GuiForms.Spec(type, oid, null, null, null, null, user, items);
    }
}
