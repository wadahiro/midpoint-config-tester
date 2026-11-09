package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.schema.constants.SchemaConstants;

import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * Who may assign what to whom, over the sample deployment {@code scenarios/access}, checked both ways: as midPoint's
 * clockwork authorizes it ({@link Actor#canAssign}, what the authorization matrix checks) and by the request itself,
 * which midPoint executes or refuses. Every user is an End user, who assigns themself the requestable roles
 * ({@code #assign} in the request, their own {@code #modify} in the execution); the manager of the IT department
 * (Department manager, {@code 020-roles.xml}) assigns its members the requestable roles, with the default relation
 * only; the help desk assigns nothing.
 */
@MidPointScenarios("classpath:scenarios/access/midpoint-config-tester.yaml")
class AssignAuthorizationScenarioTest {

    @Test
    void anEndUserAssignsThemselfARequestableRole(Scenario s) {
        assertThat(s.as(Users.JACK).canAssign(Users.JACK, Roles.AUDITOR, null)).isTrue();

        s.as(Users.JACK).request().assign(Roles.AUDITOR);

        s.user(Users.JACK).hasRole(Roles.AUDITOR);
    }

    @Test
    void anEndUserAssignsThemselfNoOtherRole(Scenario s) {
        assertThat(s.as(Users.JACK).canAssign(Users.JACK, Roles.ACCOUNTANT, null)).isFalse();

        Failure failure = s.expectFailure(() -> s.as(Users.JACK).request().assign(Roles.ACCOUNTANT));

        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
    }

    @Test
    void theHelpDeskAssignsNobodyARole(Scenario s) {
        assertThat(s.as(Users.HELPDESK).canAssign(Users.JACK, Roles.AUDITOR, null)).isFalse();

        Failure failure = s.expectFailure(() -> s.as(Users.HELPDESK).request(Users.JACK).assign(Roles.AUDITOR));

        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
    }

    @Test
    void theManagerAssignsAndUnassignsTheirMembersARequestableRole(Scenario s) {
        assertThat(s.as(Users.IT_MANAGER).canAssign(Users.JACK, Roles.AUDITOR, null)).isTrue();
        assertThat(s.as(Users.IT_MANAGER).canUnassign(Users.JACK, Roles.AUDITOR, null)).isTrue();

        s.as(Users.IT_MANAGER).request(Users.JACK).assign(Roles.AUDITOR);
        s.user(Users.JACK).hasRole(Roles.AUDITOR);
        s.as(Users.IT_MANAGER).request(Users.JACK).unassign(Roles.AUDITOR);
        s.user(Users.JACK).hasNoRole(Roles.AUDITOR);
    }

    /**
     * Nor anyone outside IT: the help desk user (not ann, whose Accountant role Auditor excludes: 4.0 refuses that
     * by the segregation of duties before the authorizations).
     */
    @Test
    void theManagerAssignsNoOtherRoleNorAnyoneElse(Scenario s) {
        assertThat(s.as(Users.IT_MANAGER).canAssign(Users.JACK, Roles.ACCOUNTANT, null)).isFalse();
        assertThat(s.as(Users.IT_MANAGER).canAssign(Users.HELPDESK, Roles.AUDITOR, null)).isFalse();

        Failure other = s.expectFailure(() -> s.as(Users.IT_MANAGER).request(Users.JACK).assign(Roles.ACCOUNTANT));
        assertThat(other.isNotAuthorized()).as(other.message()).isTrue();
        Failure outside = s.expectFailure(() -> s.as(Users.IT_MANAGER).request(Users.HELPDESK).assign(Roles.AUDITOR));
        assertThat(outside.isNotAuthorized()).as(outside.message()).isTrue();
    }

    /** The authorization's relation: the default only, so not as the role's owner. */
    @Test
    void theManagerMakesNobodyAnOwner(Scenario s) {
        assertThat(s.as(Users.IT_MANAGER).canAssign(Users.JACK, Roles.AUDITOR, SchemaConstants.ORG_OWNER)).isFalse();

        Failure failure = s.expectFailure(() -> s.as(Users.IT_MANAGER).request(Users.JACK)
                .assign(Roles.AUDITOR, SchemaConstants.ORG_OWNER));

        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
    }

    /** The End user role has no {@code #unassign}: a role once assigned stays, whoever assigned it. */
    @Test
    void anEndUserUnassignsThemselfNothing(Scenario s) {
        s.user(Users.JACK).assign(Roles.AUDITOR);
        assertThat(s.as(Users.JACK).canUnassign(Users.JACK, Roles.AUDITOR, null)).isFalse();

        Failure failure = s.expectFailure(() -> s.as(Users.JACK).request().unassign(Roles.AUDITOR));

        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
        s.user(Users.JACK).hasRole(Roles.AUDITOR);
    }
}
