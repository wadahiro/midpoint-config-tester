package com.github.wadahiro.midpoint.configtester;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;

/**
 * A button of the "+" of a role's, org's or service's members panel ({@link AbstractRole#newMemberOffers}): what a
 * click creates, a new object of the type with the archetype, a member with the relation. Its text is how failures
 * name it: {@code OrgType with archetype org-category (relation default)}.
 */
public final class NewMemberOffer {

    private final Class<? extends AssignmentHolderType> type;
    private final String archetype;
    private final String archetypeOid;
    private final QName relation;
    private final String description;

    NewMemberOffer(Class<? extends AssignmentHolderType> type, String archetype, String archetypeOid, QName relation,
            String description) {
        this.type = type;
        this.archetype = archetype;
        this.archetypeOid = archetypeOid;
        this.relation = relation != null ? relation : SchemaConstants.ORG_DEFAULT;
        this.description = description;
    }

    /**
     * The type of the new member (the {@code assignmentRelation}'s {@code holderType}); null when it names none: the
     * GUI then opens the first type of the members panel's search.
     */
    public Class<? extends AssignmentHolderType> type() {
        return type;
    }

    /** The name of the new member's archetype ({@code holderArchetypeRef}), or null: none. */
    public String archetype() {
        return archetype;
    }

    public String archetypeOid() {
        return archetypeOid;
    }

    /** The relation of the membership: {@code org:default} when the {@code assignmentRelation} names none. */
    public QName relation() {
        return relation;
    }

    /** The {@code assignmentRelation}'s description, or null. */
    public String description() {
        return description;
    }

    /** Whether this is the button of the type, archetype (OID, null: none) and relation (null: the default). */
    boolean is(Class<? extends AssignmentHolderType> type, String archetypeOid, QName relation) {
        return java.util.Objects.equals(this.type, type) && java.util.Objects.equals(this.archetypeOid, archetypeOid)
                && QNameUtil.match(this.relation, relation != null ? relation : SchemaConstants.ORG_DEFAULT);
    }

    @Override
    public String toString() {
        return describe(type, archetype, relation);
    }

    static String describe(Class<? extends AssignmentHolderType> type, String archetype, QName relation) {
        return (type != null ? type.getSimpleName() : "the panel's default type")
                + (archetype != null ? " with archetype " + archetype : " without an archetype")
                + " (relation " + (relation != null ? relation : SchemaConstants.ORG_DEFAULT).getLocalPart() + ")";
    }
}
