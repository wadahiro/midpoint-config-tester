package com.github.wadahiro.midpoint.configtester;

import static com.github.wadahiro.midpoint.configtester.assertj.MidPointConditions.targeting;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialRoles;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * Requests and approvals over the sample deployment {@code scenarios/access}: users request roles for themselves (the
 * End user role lets them request the requestable ones); Privileged Access needs its owner and then a Security
 * officer (the group is expanded on work item creation: each officer has a work item, the first decision counts);
 * Auditor needs no approval but excludes Accountant. The mails of the approvals: {@link ApprovalMailScenarioTest}.
 *
 * <p>Every request and decision runs logged in as the user, so midPoint's authorizations apply: the requesters are
 * End users (the user template assigns the role to everyone), the approvers have the Approver role (their users'
 * file). A user decides only the work items assigned to them; on 4.8+ only with the Approver role too.</p>
 */
@MidPointScenarios("classpath:scenarios/access/midpoint-config-tester.yaml")
class AccessRequestScenarioTest {

    @Test
    void privilegedAccessNeedsTheOwnerThenASecurityOfficer(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        // nothing assigned yet: the request waits in a case, at the role owner
        s.user(Users.JACK).hasNoRole(Roles.PRIVILEGED_ACCESS);
        Case request = s.cases().forUser(Users.JACK).forRole(Roles.PRIVILEGED_ACCESS).single();
        request.isOpen().atStage(1);
        s.workItems().assignedTo(Users.ROLE_OWNER).single().approve(s.as(Users.ROLE_OWNER), "ok for jack");

        // stage 2: each Security officer has a work item of their own, none offered to the group
        request.atStage(2);
        request.workItems().exactly(2);
        WorkItem officer = s.workItems().assignedTo(Users.SEC_OFFICER).single().atStage(2);
        WorkItem analyst = s.workItems().assignedTo(Users.SEC_ANALYST).single().atStage(2);
        assertThat(officer.assignees()).containsExactly(Users.SEC_OFFICER);
        assertThat(officer.candidates()).isEmpty();

        // the first decision counts: the other officer's work item is closed without a decision
        officer.approve(s.as(Users.SEC_OFFICER));
        officer.isApprovedBy(Users.SEC_OFFICER);
        analyst.isCancelled();
        request.isApproved();
        s.user(Users.JACK).hasRole(Roles.PRIVILEGED_ACCESS);
        s.workItems().none();
    }

    @Test
    void oneSecurityOfficerRejectsForAll(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
        s.workItems().forUser(Users.JACK).single().approve(s.as(Users.ROLE_OWNER));
        WorkItem officer = s.workItems().assignedTo(Users.SEC_OFFICER).single();
        WorkItem analyst = s.workItems().assignedTo(Users.SEC_ANALYST).single();

        analyst.reject(s.as(Users.SEC_ANALYST), "not needed");
        analyst.isRejectedBy(Users.SEC_ANALYST);
        officer.isCancelled();
        s.cases().forUser(Users.JACK).single().isRejected();
        s.user(Users.JACK).hasNoRole(Roles.PRIVILEGED_ACCESS);
        s.workItems().none();
    }

    /** The role owner forwards the work item: the other user replaces them, and decides. */
    @Test
    void theOwnerForwardsTheWorkItem(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        s.workItems().assignedTo(Users.ROLE_OWNER).single().forward(s.as(Users.ROLE_OWNER), Users.SEC_ANALYST);

        s.workItems().assignedTo(Users.ROLE_OWNER).none();
        s.workItems().assignedTo(Users.SEC_ANALYST).single().atStage(1).approve(s.as(Users.SEC_ANALYST));
        s.cases().forUser(Users.JACK).single().atStage(2);
    }

    /** The role owner delegates the work item: the other user becomes an assignee too, either decides. */
    @Test
    void theOwnerDelegatesTheWorkItem(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        s.workItems().assignedTo(Users.ROLE_OWNER).single().delegate(s.as(Users.ROLE_OWNER), Users.SEC_ANALYST);

        WorkItem item = s.workItems().assignedTo(Users.SEC_ANALYST).single().atStage(1);
        assertThat(item.assignees()).containsExactlyInAnyOrder(Users.ROLE_OWNER, Users.SEC_ANALYST);
        item.approve(s.as(Users.SEC_ANALYST));
        s.cases().forUser(Users.JACK).single().atStage(2);
    }

    @Test
    void aRoleWithoutApprovalIsAssignedAtOnce(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.AUDITOR);

        s.user(Users.JACK).hasRole(Roles.AUDITOR);
        s.cases().none();
    }

    @Test
    void aRoleThatIsNotRequestableIsRefused(Scenario s) {
        Failure failure = s.expectFailure(() -> s.as(Users.JACK).request().assign(Roles.ACCOUNTANT));

        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
        s.user(Users.JACK).hasNoRole(Roles.ACCOUNTANT);
    }

    @Test
    void theRequesterIsAnEndUserAndTheApproversAreApprovers(Scenario s) {
        assertThat(s.user(Users.JACK).object().getAssignment())
                .haveExactly(1, targeting(InitialRoles.END_USER)).doNotHave(targeting(InitialRoles.APPROVER));
        for (UserRef approver : List.of(Users.ROLE_OWNER, Users.SEC_OFFICER, Users.SEC_ANALYST)) {
            assertThat(s.user(approver).object().getAssignment()).as(approver.name())
                    .haveExactly(1, targeting(InitialRoles.APPROVER));
        }
    }

    /**
     * On 4.8+ the Approver role is what lets the role owner decide ({@code #completeWorkItem}): without it, the decision
     * on their own item is refused. Before 4.8 midPoint needs no authorization to complete a work item one is assigned
     * to (or a deputy or candidate of), so the role owner decides without it.
     */
    @Test
    void onFourEightTheApproverRoleIsWhatLetsAnApproverDecide(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
        s.user(Users.ROLE_OWNER).unassign(InitialRoles.APPROVER);
        WorkItem item = s.workItems().assignedTo(Users.ROLE_OWNER).single();

        if (s.line().equals("4.0") || s.line().equals("4.4")) {
            item.approve(s.as(Users.ROLE_OWNER));
            item.isApprovedBy(Users.ROLE_OWNER);
            return;
        }
        Failure failure = s.expectFailure(() -> item.approve(s.as(Users.ROLE_OWNER)));
        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
        item.isOpen();
        s.user(Users.JACK).hasNoRole(Roles.PRIVILEGED_ACCESS);
    }

    /** An approver decides only their own work items: another approver's is refused. */
    @Test
    void anApproverCannotDecideAnotherApproversItem(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
        WorkItem item = s.workItems().assignedTo(Users.ROLE_OWNER).single();

        Failure failure = s.expectFailure(() -> item.approve(s.as(Users.SEC_OFFICER)));

        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
        item.isOpen();
    }

    /** A requester cannot approve their own request. */
    @Test
    void theRequesterCannotApproveTheirOwnRequest(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
        WorkItem item = s.workItems().assignedTo(Users.ROLE_OWNER).single();

        Failure failure = s.expectFailure(() -> item.approve(s.as(Users.JACK)));

        assertThat(failure.isNotAuthorized()).as(failure.message()).isTrue();
        item.isOpen();
        s.user(Users.JACK).hasNoRole(Roles.PRIVILEGED_ACCESS);
    }

    @Test
    void theSegregationOfDutiesRuleRefusesAuditorToAnAccountant(Scenario s) {
        Failure failure = s.expectFailure(() -> s.user(Users.ANN).assign(Roles.AUDITOR));

        assertThat(failure.isPolicyViolation()).as(failure.message()).isTrue();
        failure.hasMessageContaining("Auditor");
        s.user(Users.ANN).hasNoRole(Roles.AUDITOR).hasRole(Roles.ACCOUNTANT);
    }

    @Test
    void expectFailureFailsWhenMidPointDoesIt(Scenario s) {
        AssertionError error = null;
        try {
            s.expectFailure(() -> s.user(Users.JACK).assign(Roles.AUDITOR));
        } catch (AssertionError e) {
            error = e;
        }
        assertThat(error).isNotNull().hasMessageContaining("Expected midPoint to refuse the operation");
    }
}
