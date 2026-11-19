package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.github.wadahiro.midpoint.configtester.names.ArchetypeRef;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.internal.MemberOffers;

/**
 * What a {@link Role}, {@link Org} and {@link Service} have besides being a {@link Focus}: their members, the focuses
 * midPoint made members of them (their {@code roleMembershipRef}, with the default relation: not managers or owners),
 * and what the "+" of their members panel offers to create ({@link #newMemberOffers}).
 *
 * @param <SELF> the class itself, which the assertions return
 */
public abstract class AbstractRole<SELF extends AbstractRole<SELF>> extends Focus<SELF> {

    AbstractRole(MidPointConfigTester tester, Class<? extends AbstractRoleType> type, String kind, String name) {
        super(tester, type, kind, name);
    }

    /** The names of the members (users, and roles, orgs or services that have this one), as the repository has them. */
    public List<String> members() {
        List<String> out = new ArrayList<>();
        for (FocusType member : tester.deployment().api().members(existing().getOid())) {
            out.add(member.getName().getOrig());
        }
        return out;
    }

    /** {@link #hasMember(String)} by the generated name ({@code Users.JACK}, a role, an org...). */
    public SELF hasMember(com.github.wadahiro.midpoint.configtester.names.ObjectRef focus) {
        return hasMember(focus.on(tester.line()));
    }

    public SELF hasNoMember(com.github.wadahiro.midpoint.configtester.names.ObjectRef focus) {
        return hasNoMember(focus.on(tester.line()));
    }

    public SELF hasMember(String focus) {
        List<String> members = members();
        if (!members.contains(focus)) {
            throw new AssertionError(kind + " " + name + ": expected a member " + focus + ", members: " + members);
        }
        return self();
    }

    public SELF hasNoMember(String focus) {
        List<String> members = members();
        if (members.contains(focus)) {
            throw new AssertionError(kind + " " + name + ": expected no member " + focus + ", members: " + members);
        }
        return self();
    }

    // ---- the "+" of the members panel -----------------------------------------------------------------------

    /**
     * The buttons the "+" of the members panel offers, in the GUI's order: what the {@code assignmentRelation}s of
     * the object's archetype (of its inducements) say a new member is (type, archetype, relation), divided as the GUI
     * divides them into buttons (one per relation, type and archetype; an entry with neither a {@code holderType}
     * nor a {@code relation} ignored; each button once). The configuration only, as the administrator reads it:
     * whether a user sees the "+" is {@link Actor#createMemberButton}. Not offered: the panel's generic "+", whose
     * dialog takes any type and relation of the panel, without an archetype. A super-archetype's
     * {@code assignmentRelation}s are not the object's (on no line); 4.4 and later read only a structural archetype.
     */
    public List<NewMemberOffer> newMemberOffers() {
        return offers().list;
    }

    /** The "+" offers a new member of the type without an archetype, with the default relation. */
    public SELF offersNewMember(Class<? extends AssignmentHolderType> type) {
        return offersNewMember(type, (String) null, null);
    }

    /**
     * The "+" offers a new member of the type with the archetype (name or OID; null: none), with the default
     * relation, as {@link #newMemberOffers} has it: {@code t.org("site-1").offersNewMember(OrgType.class,
     * "org-category")}.
     */
    public SELF offersNewMember(Class<? extends AssignmentHolderType> type, String archetype) {
        return offersNewMember(type, archetype, null);
    }

    /** The "+" offers a new member of the type with the archetype and the relation ({@code ORG_MANAGER}). */
    public SELF offersNewMember(Class<? extends AssignmentHolderType> type, String archetype, QName relation) {
        Offers offers = offers();
        if (offers.find(type, archetypeOid(archetype), relation) == null) {
            throw new AssertionError(kind + " " + name + ": the GUI does not offer to create "
                    + NewMemberOffer.describe(type, archetype, relation) + "; " + offers.text());
        }
        return self();
    }

    public SELF offersNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype) {
        return offersNewMember(type, archetype.on(tester.line()), null);
    }

    public SELF offersNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype, QName relation) {
        return offersNewMember(type, archetype.on(tester.line()), relation);
    }

    /**
     * The "+" has no button of a new member of the type without an archetype, with the default relation. The
     * generic "+" still creates one (its dialog: any type and relation of the panel).
     */
    public SELF offersNoNewMember(Class<? extends AssignmentHolderType> type) {
        return offersNoNewMember(type, (String) null, null);
    }

    /**
     * The "+" has no button of a new member of the type with the archetype (name or OID; null: none), with the
     * default relation. A user still creates one from the generic "+" without the archetype, then assigns it.
     */
    public SELF offersNoNewMember(Class<? extends AssignmentHolderType> type, String archetype) {
        return offersNoNewMember(type, archetype, null);
    }

    public SELF offersNoNewMember(Class<? extends AssignmentHolderType> type, String archetype, QName relation) {
        if (offers().find(type, archetypeOid(archetype), relation) != null) {
            throw new AssertionError(kind + " " + name + ": the GUI offers to create "
                    + NewMemberOffer.describe(type, archetype, relation));
        }
        return self();
    }

    public SELF offersNoNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype) {
        return offersNoNewMember(type, archetype.on(tester.line()), null);
    }

    public SELF offersNoNewMember(Class<? extends AssignmentHolderType> type, ArchetypeRef archetype, QName relation) {
        return offersNoNewMember(type, archetype.on(tester.line()), relation);
    }

    /** The offers read anew, with why there are none, the read explained once per test. */
    Offers offers() {
        String oid = existing().getOid();
        MemberOffers read = MemberOffers.of(tester.deployment().api(), oid);
        List<NewMemberOffer> list = new ArrayList<>();
        for (MemberOffers.Offer offer : read.offers) {
            list.add(new NewMemberOffer(holderType(offer.type), tester.nameOf(offer.archetype),
                    offer.archetype != null ? offer.archetype.getOid() : null, offer.relation, offer.description));
        }
        Offers offers = new Offers(list, list.isEmpty() ? nothing(read) : null);
        tester.read("new member offers of " + name, () -> kind + " " + name + ": the \"+\" of its members offers"
                + (list.isEmpty() ? " nothing: " + offers.nothing : list.stream()
                        .map(o -> "\n  " + o + (o.description() != null ? ": " + o.description() : ""))
                        .collect(Collectors.joining())));
        return offers;
    }

    /** Why the GUI offers nothing: no archetype to read, or nothing in it. */
    private String nothing(MemberOffers read) {
        List<String> archetypes = new ArrayList<>();
        for (ObjectReferenceType archetype : existing().getArchetypeRef()) {
            archetypes.add(tester.nameOf(archetype));
        }
        if (archetypes.isEmpty()) {
            return name + " has no archetype";
        }
        if (!read.specified) {
            return name + " has no structural archetype (its archetypes: " + String.join(", ", archetypes) + ")";
        }
        if (read.entries == 0) {
            return "its archetype " + String.join(", ", archetypes) + " induces no assignmentRelation";
        }
        return "each assignmentRelation of its archetype " + String.join(", ", archetypes)
                + " names neither a holderType nor a relation, which the GUI ignores";
    }

    /** The OID of the archetype (name or OID), null for none; no such archetype is a mistake of the test. */
    String archetypeOid(String archetype) {
        return archetype == null ? null : ObjectForm.oidOf(tester.deployment().api(), ArchetypeType.class, archetype);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AssignmentHolderType> holderType(QName type) {
        return type == null ? null
                : (Class<? extends AssignmentHolderType>) (Class<?>) ObjectTypes.getObjectTypeFromTypeQName(type)
                        .getClassDefinition();
    }

    @SuppressWarnings("unchecked")
    Class<? extends AbstractRoleType> roleType() {
        return (Class<? extends AbstractRoleType>) type;
    }

    /** The role, org or service of the generated name. */
    static AbstractRole<?> of(MidPointConfigTester tester, ObjectRef role) {
        Class<? extends AbstractRoleType> type = roleType(role);
        String key = role.oid() != null ? role.oid() : role.on(tester.line());
        if (type == OrgType.class) {
            return tester.org(key);
        }
        return type == RoleType.class ? tester.role(key) : tester.service(key);
    }

    static Class<? extends AbstractRoleType> roleType(ObjectRef role) {
        switch (role.type()) {
            case "RoleType":
                return RoleType.class;
            case "OrgType":
                return OrgType.class;
            case "ServiceType":
                return ServiceType.class;
            default:
                throw new IllegalArgumentException("Not a role, org or service: " + role);
        }
    }

    /** The offers, and why there are none. */
    static final class Offers {
        final List<NewMemberOffer> list;
        final String nothing;

        Offers(List<NewMemberOffer> list, String nothing) {
            this.list = list;
            this.nothing = nothing;
        }

        NewMemberOffer find(Class<? extends AssignmentHolderType> type, String archetypeOid, QName relation) {
            return list.stream().filter(o -> o.is(type, archetypeOid, relation)).findFirst().orElse(null);
        }

        /** {@code it offers: ...} or {@code it offers nothing: ...}. */
        String text() {
            return list.isEmpty() ? "it offers nothing: " + nothing
                    : "it offers: " + list.stream().map(NewMemberOffer::toString).collect(Collectors.joining(", "));
        }
    }
}
