package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.model.api.AssignmentCandidatesSpecification;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.NewMemberOffer;
import com.github.wadahiro.midpoint.configtester.names.ArchetypeRef;
import com.github.wadahiro.midpoint.configtester.names.Lines;

/**
 * What the "+" of a members panel offers ({@code AbstractRole.newMemberOffers}): the archetype's
 * {@code assignmentRelation}s divided into the GUI's buttons, the same on every line (fixtures/forms/members.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class NewMemberOffersScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "members.xml");

    @Test
    void aButtonPerRelationTypeAndArchetype(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        // three relations of one assignmentRelation are three buttons; the repeated category is one (4.0's GUI shows
        // it twice); the assignmentRelation with an archetype only is not one
        assertThat(t.org("member-site-1").newMemberOffers()).extracting(NewMemberOffer::toString).containsExactly(
                "OrgType with archetype member-category (relation default)",
                "UserType with archetype member-employee (relation default)",
                "UserType with archetype member-employee (relation manager)",
                "UserType with archetype member-employee (relation approver)");
        NewMemberOffer category = t.org("member-site-1").newMemberOffers().get(0);
        assertThat(category.type()).isEqualTo(OrgType.class);
        assertThat(category.archetypeOid()).isEqualTo("10000000-0000-0000-0000-0000000f0101");
        assertThat(category.relation()).isEqualTo(SchemaConstants.ORG_DEFAULT);
        assertThat(category.description()).isEqualTo("A category of the site");
    }

    @Test
    void aRoleOffersEachTypeAndAnOwnerOfNoType(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        assertThat(t.role("member-team-1").newMemberOffers()).extracting(NewMemberOffer::toString).containsExactly(
                "UserType without an archetype (relation default)",
                "ServiceType without an archetype (relation default)",
                "the panel's default type without an archetype (relation owner)");
        assertThat(t.role("member-team-1").newMemberOffers().get(2).type()).isNull();
        t.role("member-team-1").offersNewMember(UserType.class).offersNewMember(ServiceType.class)
                .offersNewMember(null, (String) null, SchemaConstants.ORG_OWNER).offersNoNewMember(OrgType.class);
    }

    @Test
    void theClaimsCompareTypeArchetypeAndRelation(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.org("member-site-1").offersNewMember(OrgType.class, "member-category")
                .offersNewMember(UserType.class, "member-employee", SchemaConstants.ORG_MANAGER)
                .offersNewMember(OrgType.class, new ArchetypeRef("member-category", null, Lines.ALL))
                .offersNoNewMember(OrgType.class, "member-site")
                .offersNoNewMember(OrgType.class)
                .offersNoNewMember(UserType.class, "member-employee", SchemaConstants.ORG_OWNER);
        assertThatThrownBy(() -> t.org("member-site-1").offersNewMember(OrgType.class, "member-site"))
                .isInstanceOf(AssertionError.class)
                .hasMessage("Org member-site-1: the GUI does not offer to create OrgType with archetype member-site"
                        + " (relation default); it offers: OrgType with archetype member-category (relation default),"
                        + " UserType with archetype member-employee (relation default), UserType with archetype"
                        + " member-employee (relation manager), UserType with archetype member-employee (relation"
                        + " approver)");
        assertThatThrownBy(() -> t.org("member-site-1").offersNoNewMember(UserType.class, "member-employee",
                SchemaConstants.ORG_APPROVER)).hasMessage("Org member-site-1: the GUI offers to create UserType with"
                        + " archetype member-employee (relation approver)");
        assertThatThrownBy(() -> t.org("member-site-1").offersNewMember(OrgType.class, "no-such-archetype"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No ArchetypeType no-such-archetype");
    }

    @Test
    void withoutAnArchetypeOrItsAssignmentRelationsNothingIsOffered(MidPointConfigTester t) throws Exception {
        t.midpoint().importObject(FIXTURE);

        assertThat(t.org("member-bare-org").newMemberOffers()).isEmpty();
        assertThatThrownBy(() -> t.org("member-bare-org").offersNewMember(OrgType.class))
                .hasMessageEndingWith("; it offers nothing: member-bare-org has no archetype");
        assertThat(t.org("member-plain-org").newMemberOffers()).isEmpty();
        assertThatThrownBy(() -> t.org("member-plain-org").offersNewMember(OrgType.class))
                .hasMessageEndingWith("; it offers nothing: its archetype member-plain induces no assignmentRelation");
        // midPoint's answer without an archetype: 4.0 a specification without relations (null), 4.4 and later none
        AssignmentCandidatesSpecification bare = t.midpoint().bean(ModelInteractionService.class)
                .determineAssignmentHolderSpecification(t.midpoint().find(OrgType.class, "member-bare-org")
                        .asPrismObject(), new OperationResult("specification"));
        if (t.line().equals("4.0")) {
            assertThat(bare.getAssignmentObjectRelations()).isNull();
        } else {
            assertThat(bare).isNull();
        }
        AssignmentCandidatesSpecification plain = t.midpoint().bean(ModelInteractionService.class)
                .determineAssignmentHolderSpecification(t.midpoint().find(OrgType.class, "member-plain-org")
                        .asPrismObject(), new OperationResult("specification"));
        assertThat(plain.getAssignmentObjectRelations()).isEmpty();
    }
}
