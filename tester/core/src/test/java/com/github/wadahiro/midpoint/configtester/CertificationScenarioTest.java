package com.github.wadahiro.midpoint.configtester;

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
        campaign.isInStage(1).reviews().forReviewer(Users.SEC_OFFICER).exactly(2);
        campaign.reviews().forUser(Users.JACK).forRole(Roles.AUDITOR).single().revoke(t.actor(Users.SEC_OFFICER), "left the audit");
        campaign.reviews().forUser(Users.ROLE_OWNER).single().accept(t.actor(Users.SEC_OFFICER));
        campaign.reviews().undecided().none();

        campaign.closeStage().remediate();

        campaign.hasOutcome(Users.JACK, Roles.AUDITOR, "revoke").hasOutcome(Users.ROLE_OWNER, Roles.AUDITOR, "accept");
        t.user(Users.JACK).hasNoRole(Roles.AUDITOR);
        t.user(Users.ROLE_OWNER).hasRole(Roles.AUDITOR);
    }
}
