package com.github.wadahiro.midpoint.configtester;

import java.util.Map;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * The GUI's form for a new object ({@link MidPointConfigTester#newObjectForm}, as a user: {@link Actor#newObjectForm}):
 * the type's details page as the GUI opens it for a new object, with the archetype (as the "+" of an archetype's
 * members makes it: the archetype's object template gives the page its items and validations) and the parent
 * organization (as "create child" of the organization tree: its assignment and parentOrgRef). What the page does is
 * {@link ObjectForm}'s.
 *
 * <pre>{@code
 * t.newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1")
 *     .validate("identifier", "a1").fails("The identifier is upper case letters and digits.")
 * t.newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1").set("identifier", "CAT")
 *     .set("displayName", "Category").submit().saves()
 * }</pre>
 */
public final class NewObjectForm extends ObjectForm {

    private final String archetype;
    private final String parent;

    NewObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String user) {
        this(tester, type, null, null, user, Map.of());
    }

    private NewObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String archetype, String parent,
            String user, Map<String, Object> items) {
        super(tester, type, user, items);
        this.archetype = archetype;
        this.parent = parent;
    }

    /** The archetype of the new object (name or OID): its object template gives the page its items and validations. */
    public NewObjectForm archetype(String archetype) {
        return new NewObjectForm(tester, type, archetype, parent, user, items);
    }

    /** The organization the new object is created in (name or OID): its assignment and parentOrgRef. */
    public NewObjectForm parent(String org) {
        return new NewObjectForm(tester, type, archetype, org, user, items);
    }

    /**
     * A value typed into the item's field first (its path of local names: {@code identifier},
     * {@code extension/site}), as the user fills in the form before the item validated or saving.
     */
    public NewObjectForm set(String item, Object value) {
        return new NewObjectForm(tester, type, archetype, parent, user, with(items, item, value));
    }

    @Override
    GuiForms.Spec spec() {
        var api = tester.deployment().api();
        return new GuiForms.Spec(type, null, archetype != null ? oidOf(api, ArchetypeType.class, archetype) : null,
                parent != null ? oidOf(api, OrgType.class, parent) : null, user, items);
    }
}
