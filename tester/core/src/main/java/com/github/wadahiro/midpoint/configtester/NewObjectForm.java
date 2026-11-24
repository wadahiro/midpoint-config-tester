package com.github.wadahiro.midpoint.configtester;

import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * The GUI's form for a new object ({@link MidPointConfigTester#newObjectForm}, as a user: {@link Actor#newObjectForm}):
 * the type's details page as the GUI opens it for a new object, with the archetype (as the "+" of an archetype's
 * members makes it: the archetype's object template gives the page its items and validations), the parent
 * organization (as "create child" of the organization tree: its assignment and parentOrgRef) or the role, org or
 * service the object is a member of with a relation (as the "+" of its members makes it:
 * {@link Actor#createMemberButton}). What the page does is {@link ObjectForm}'s.
 *
 * <pre>{@code
 * t.newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1")
 *     .validate("identifier", "a1").fails("The identifier is upper case letters and digits.")
 * t.newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1").set("identifier", "CAT")
 *     .set("displayName", "Category").submit().saves()
 * t.newObjectForm(UserType::class.java).archetype("employee").memberOf("site-1", SchemaConstants.ORG_MANAGER)
 *     .set("name", "mgr-1").submit().saves()
 * t.newObjectForm(OrgType::class.java).archetype("org-category").parent("site-1").tabs().hasNoTab("Governance")
 * }</pre>
 */
public final class NewObjectForm extends ObjectForm {

    private final String archetype;
    /** The role, org or service of the membership (name or OID), or null. */
    private final String member;
    /** Its type; null: whichever abstract role has the name. */
    private final Class<? extends AbstractRoleType> memberType;
    private final QName relation;

    NewObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String user) {
        this(tester, type, null, null, null, null, user, Map.of());
    }

    private NewObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String archetype,
            String member, Class<? extends AbstractRoleType> memberType, QName relation, String user,
            Map<String, Object> items) {
        super(tester, type, user, items);
        this.archetype = archetype;
        this.member = member;
        this.memberType = memberType;
        this.relation = relation;
    }

    /** The archetype of the new object (name or OID): its object template gives the page its items and validations. */
    public NewObjectForm archetype(String archetype) {
        return derive(new NewObjectForm(tester, type, archetype, member, memberType, relation, user, items),
                "archetype(" + archetype + ")");
    }

    /**
     * The organization the new object is created in (name or OID), as "create child" of the organization tree makes
     * it: its assignment (no relation set) and parentOrgRef.
     */
    public NewObjectForm parent(String org) {
        return derive(new NewObjectForm(tester, type, archetype, org, OrgType.class, null, user, items),
                "parent(" + org + ")");
    }

    /**
     * The role, org or service (name or OID) the new object is a member of, as the "+" of its members makes it: its
     * assignment with the relation set ({@code SchemaConstants.ORG_MANAGER}), and for an org its parentOrgRef when
     * the line's GUI puts it there (4.0: for every relation; 4.4 and later: when the relation is stored into
     * parentOrgRef, midPoint's {@code RelationRegistry}: by default the member relations, {@code default} and
     * {@code manager}). {@link Actor#createMemberButton} clicks it from the members panel.
     */
    public NewObjectForm memberOf(String roleOrOrg, QName relation) {
        return derive(new NewObjectForm(tester, type, archetype, roleOrOrg, null, relation, user, items),
                "memberOf(" + roleOrOrg + ")");
    }

    public NewObjectForm memberOf(AbstractRole<?> role, QName relation) {
        return derive(new NewObjectForm(tester, type, archetype, role.oid(), role.roleType(), relation, user,
                items), "memberOf(" + role.oid() + ")");
    }

    /** {@link #memberOf(String, QName)} by the generated name ({@code Orgs.SITE_1}, a role or a service). */
    public NewObjectForm memberOf(ObjectRef role, QName relation) {
        return derive(new NewObjectForm(tester, type, archetype,
                role.oid() != null ? role.oid() : role.on(tester.line()), AbstractRole.roleType(role), relation,
                user, items), "memberOf(" + role.name() + ")");
    }

    /** As a member with no relation set: for an org, {@link #parent(String)}. */
    public NewObjectForm memberOf(String roleOrOrg) {
        return memberOf(roleOrOrg, null);
    }

    /**
     * A value typed into the item's field first (its path of local names: {@code identifier},
     * {@code extension/site}), as the user fills in the form before the item validated or saving. A collection is the
     * values of a multi-valued item, each typed into a value row as the item's add button adds them
     * ({@code set("extension/tags", listOf("a", "b"))}).
     * The value as the item's type has it, typed as the field shows it: a number or its text, a date
     * ({@code Instant}, {@code ZonedDateTime}, {@code XMLGregorianCalendar}, its ISO text; to the minute), a
     * duration, a drop-down's choice (a boolean, an enumeration, an object type's QName), a lookup table's key or
     * label; a reference or a password fails, naming what to use instead
     * (docs/guide/gui-and-authorizations.md).
     */
    public NewObjectForm set(String item, Object value) {
        return derive(new NewObjectForm(tester, type, archetype, member, memberType, relation, user,
                with(items, item, value)), "set(" + item + ")");
    }

    @Override
    List<String> changeMethods() {
        return List.of("set", "archetype", "parent", "memberOf");
    }

    @Override
    GuiForms.Spec spec() {
        var api = tester.deployment().api();
        String memberOid = null;
        Class<? extends ObjectType> memberClass = null;
        if (member != null) {
            Class<? extends AbstractRoleType> lookup = memberType != null ? memberType : AbstractRoleType.class;
            ObjectType target = api.find(lookup, member);
            if (target == null) {
                throw new IllegalArgumentException("No " + (memberType != null ? memberType.getSimpleName()
                        : "role, org or service") + " " + member);
            }
            memberOid = target.getOid();
            memberClass = target.getClass();
        }
        return new GuiForms.Spec(type, null, archetype != null ? oidOf(api, ArchetypeType.class, archetype) : null,
                memberOid, memberClass, relation, user, items);
    }

    @Override
    String page() {
        return "a new " + kind() + (archetype != null ? " of archetype " + archetype : "")
                + (member != null ? " in " + member : "");
    }
}
