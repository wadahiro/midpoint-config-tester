package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.AssignmentCandidatesSpecification;
import com.evolveum.midpoint.model.api.AssignmentObjectRelation;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.github.wadahiro.midpoint.tester.MidPoint;

/**
 * What the "+" of a role's, org's or service's members panel offers to create ({@code configtester.NewMemberOffer}):
 * the assignment holder specification midPoint derives from the object's archetype
 * ({@code ModelInteractionService.determineAssignmentHolderSpecification}: the {@code assignmentRelation}s of its
 * inducements), divided as the GUI divides it into its buttons ({@code WebComponentUtil
 * .divideAssignmentRelationsByAllValues}, the same on every line): an entry with neither a holder type nor a relation
 * dropped, then one button per relation, type and archetype, the same button once. The GUI of 4.0 shows the same
 * button again for each entry that repeats it, and 4.4 and later one that names no type again: here each button is
 * once on every line.
 */
public final class MemberOffers {

    /** One button: its type, archetype and relation, each null when the entry names none. */
    public static final class Offer {
        public final QName type;
        public final ObjectReferenceType archetype;
        public final QName relation;
        public final String description;

        Offer(QName type, ObjectReferenceType archetype, QName relation, String description) {
            this.type = type;
            this.archetype = archetype;
            this.relation = relation;
            this.description = description;
        }

        private boolean sameButton(Offer other) {
            return same(type, other.type) && same(relation, other.relation)
                    && Objects.equals(archetype == null ? null : archetype.getOid(),
                            other.archetype == null ? null : other.archetype.getOid());
        }

        private static boolean same(QName a, QName b) {
            return a == null ? b == null : b != null && QNameUtil.match(a, b);
        }
    }

    /** The buttons, in the GUI's order. */
    public final List<Offer> offers;
    /**
     * Whether midPoint gave a specification: 4.4 and later give none for an object without a structural archetype,
     * 4.0 one without relations (null).
     */
    public final boolean specified;
    /** The {@code assignmentRelation}s of the specification, before the GUI's division. */
    public final int entries;

    private MemberOffers(List<Offer> offers, boolean specified, int entries) {
        this.offers = Collections.unmodifiableList(offers);
        this.specified = specified;
        this.entries = entries;
    }

    /** The offers of the role, org or service with this OID, as the administrator reads its archetype. */
    public static MemberOffers of(MidPoint api, String abstractRoleOid) {
        MidPointImpl mp = (MidPointImpl) api;
        AbstractRoleType role = mp.existing(AbstractRoleType.class, abstractRoleOid);
        return mp.query("member offers of " + role.getName().getOrig(), (task, result) -> {
            AssignmentCandidatesSpecification spec = mp.bean(ModelInteractionService.class)
                    .determineAssignmentHolderSpecification(role.asPrismObject(), result);
            List<AssignmentObjectRelation> relations = spec == null || spec.getAssignmentObjectRelations() == null
                    ? List.of() : spec.getAssignmentObjectRelations();
            return new MemberOffers(divide(relations), spec != null, relations.size());
        });
    }

    private static List<Offer> divide(List<AssignmentObjectRelation> relations) {
        List<Offer> out = new ArrayList<>();
        for (AssignmentObjectRelation relation : relations) {
            if (isEmpty(relation.getObjectTypes()) && isEmpty(relation.getRelations())) {
                // divideAssignmentRelationsByRelationValue: ignored, even with a holder archetype
                continue;
            }
            for (QName name : orNone(relation.getRelations())) {
                for (QName type : orNone(relation.getObjectTypes())) {
                    for (ObjectReferenceType archetype : orNone(relation.getArchetypeRefs())) {
                        Offer offer = new Offer(type, archetype, name, relation.getDescription());
                        if (out.stream().noneMatch(offer::sameButton)) {
                            out.add(offer);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The values, or one null: an entry that names none is one button without it. */
    private static <T> List<T> orNone(List<T> values) {
        return isEmpty(values) ? Collections.singletonList(null) : values;
    }

    private static boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }
}
