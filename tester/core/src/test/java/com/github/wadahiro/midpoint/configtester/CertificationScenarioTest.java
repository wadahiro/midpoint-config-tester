package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.access.CertificationDefinitions;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * A certification campaign over the sample deployment {@code scenarios/access}: the Security officer reviews the
 * assignments of the requestable roles; what they revoke is removed by the automated remediation.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class CertificationScenarioTest {

    @Test
    void aRevokedAssignmentIsRemovedByTheRemediation(MidPointConfigTester t) {
        t.user(Users.JACK).assign(Roles.AUDITOR);
        t.user(Users.ROLE_OWNER).assign(Roles.AUDITOR);

        Campaign campaign = t.certification(CertificationDefinitions.ROLE_ASSIGNMENTS).start();
        campaign.atStage(1).reviews().forReviewer(Users.SEC_OFFICER).exactly(2);
        campaign.reviews().forUser(Users.JACK).forRole(Roles.AUDITOR).single().revoke(t.actor(Users.SEC_OFFICER), "left the audit");
        campaign.reviews().forUser(Users.ROLE_OWNER).single().accept(t.actor(Users.SEC_OFFICER));
        campaign.reviews().undecided().none();

        campaign.closeStage();
        campaign.remediate();

        campaign.hasOutcome(Users.JACK, Roles.AUDITOR, "revoke").hasOutcome(Users.ROLE_OWNER, Roles.AUDITOR, "accept");
        t.user(Users.JACK).hasNoAssignment(Roles.AUDITOR);
        t.user(Users.ROLE_OWNER).hasAssignment(Roles.AUDITOR);
    }

    @Test
    void aCommentAloneKeepsTheResponse(MidPointConfigTester t) {
        t.user(Users.JACK).assign(Roles.AUDITOR);
        Campaign campaign = t.certification(CertificationDefinitions.ROLE_ASSIGNMENTS).start();

        // the comment column of an undecided item: one save, the item stays undecided
        campaign.reviews().forUser(Users.JACK).single().comment(t.actor(Users.SEC_OFFICER), "asked the manager");
        Review commented = campaign.reviews().forUser(Users.JACK).undecided().single();
        assertThat(commented.response()).isNull();
        assertThat(commented.comment()).isEqualTo("asked the manager");
        assertThat(campaign.outcomeOf(Users.JACK, Roles.AUDITOR)).isNotEqualTo("accept");

        // a response button later: a save of its own, the comment the row shows goes with it
        commented.accept(t.actor(Users.SEC_OFFICER));
        Review accepted = campaign.reviews().forUser(Users.JACK).single();
        assertThat(accepted.response()).isEqualTo("accept");
        assertThat(accepted.comment()).isEqualTo("asked the manager");

        // a comment on a decided item: the response stays
        accepted.comment(t.actor(Users.SEC_OFFICER), "manager confirmed");
        Review recommented = campaign.reviews().forUser(Users.JACK).single();
        assertThat(recommented.response()).isEqualTo("accept");
        assertThat(recommented.comment()).isEqualTo("manager confirmed");
        campaign.hasOutcome(Users.JACK, Roles.AUDITOR, "accept");

        // the stale review of before the decision does not undo it
        commented.comment(t.actor(Users.SEC_OFFICER), "final note");
        assertThat(campaign.reviews().forUser(Users.JACK).single().response()).isEqualTo("accept");

        // a response button without a comment of its own keeps the row's comment
        campaign.reviews().forUser(Users.JACK).single().revoke(t.actor(Users.SEC_OFFICER));
        Review revoked = campaign.reviews().forUser(Users.JACK).single();
        assertThat(revoked.response()).isEqualTo("revoke");
        assertThat(revoked.comment()).isEqualTo("final note");
    }
}
