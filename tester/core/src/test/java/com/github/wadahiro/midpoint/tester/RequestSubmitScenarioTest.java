package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleManagementConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.github.wadahiro.midpoint.configtester.Failure;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.RoleRef;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * The request-access page's Submit ({@code Actor.request}) over {@code scenarios/access}: several roles in one
 * Submit with the page's options (fixtures/request-submit: two roles approved by role-owner), the comment kept on the
 * assignments, and several targets as the task the line's GUI starts (4.0: an execute-changes task added through the
 * model as the requester; 4.4: an iterative change execution persisted directly; 4.8+: an explicit change execution
 * submitted privileged), owned by the requester.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class RequestSubmitScenarioTest {

    private static final RoleRef ALPHA = new RoleRef("rs-alpha", null, Lines.ALL);
    private static final RoleRef BETA = new RoleRef("rs-beta", null, Lines.ALL);

    private static void importRoles(MidPointConfigTester t) {
        t.midpoint().importObject(new File(Fixtures.dir("request-submit"), "roles.xml"));
    }

    /** The system configuration's {@code roleManagement/defaultExecuteAfterAllApprovals}, as the page reads it. */
    private static void executeAfterAllApprovals(MidPointConfigTester t, boolean value) {
        t.midpoint().modify(SystemConfigurationType.class, "00000000-0000-0000-0000-000000000001",
                ItemPath.create(SystemConfigurationType.F_ROLE_MANAGEMENT),
                new RoleManagementConfigurationType().defaultExecuteAfterAllApprovals(value));
    }

    /**
     * The request's comment as midPoint keeps it on the assignment ({@code OperationalDataManager}): its
     * {@code metadata/requestorComment} up to 4.8, its value metadata's {@code process/requestorComment} on 4.9+.
     */
    private static String requestorComment(MidPointConfigTester t, String user, RoleRef role) {
        String oid = t.role(role).oid();
        for (AssignmentType assignment : t.user(user).asObject().getAssignment()) {
            if (assignment.getTargetRef() != null && oid.equals(assignment.getTargetRef().getOid())) {
                if (assignment.getMetadata() != null && assignment.getMetadata().getRequestorComment() != null) {
                    return assignment.getMetadata().getRequestorComment();
                }
                // the value metadata (4.9+; no such API on 4.0, so by reflection)
                try {
                    Object container = assignment.asPrismContainerValue();
                    Object metadata = container.getClass().getMethod("getValueMetadata").invoke(container);
                    for (Object value : (Iterable<?>) metadata.getClass().getMethod("getRealValues").invoke(metadata)) {
                        Object process = value.getClass().getMethod("getProcess").invoke(value);
                        if (process != null) {
                            return (String) process.getClass().getMethod("getRequestorComment").invoke(process);
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    return null;
                }
                return null;
            }
        }
        throw new AssertionError(user + " has no assignment of " + role.name());
    }

    /** Without the option (or with it true) the approved role waits for the other's approval: one execution at the end. */
    @Test
    void twoRolesInOneSubmitAreAppliedTogetherAfterAllApprovalsByDefault(MidPointConfigTester t) {
        importRoles(t);

        t.actor(Users.JACK).request().comment("both, please").assign(ALPHA, BETA);

        t.cases().forUser(Users.JACK).exactly(2);
        t.cases().forUser(Users.JACK).forRole(ALPHA).single().isOpen();
        t.cases().forUser(Users.JACK).forRole(BETA).single().isOpen();
        t.user(Users.JACK).hasNoAssignment(ALPHA).hasNoAssignment(BETA);

        t.workItems().forRole(ALPHA).single().approve(t.actor(Users.ROLE_OWNER));
        t.cases().forUser(Users.JACK).forRole(ALPHA).single().isApproved();
        t.user(Users.JACK).hasNoAssignment(ALPHA).hasNoAssignment(BETA);

        t.workItems().forRole(BETA).single().approve(t.actor(Users.ROLE_OWNER));
        t.user(Users.JACK).hasAssignment(ALPHA).hasAssignment(BETA);
        assertThat(requestorComment(t, Users.JACK.name(), ALPHA)).isEqualTo("both, please");
        assertThat(requestorComment(t, Users.JACK.name(), BETA)).isEqualTo("both, please");
    }

    @Test
    void withDefaultExecuteAfterAllApprovalsFalseEachApprovedRoleIsAppliedAtOnce(MidPointConfigTester t) {
        importRoles(t);
        executeAfterAllApprovals(t, false);

        t.actor(Users.JACK).request().assign(ALPHA, BETA);
        t.cases().forUser(Users.JACK).exactly(2);

        t.workItems().forRole(ALPHA).single().approve(t.actor(Users.ROLE_OWNER));
        t.user(Users.JACK).hasAssignment(ALPHA).hasNoAssignment(BETA);
        t.cases().forUser(Users.JACK).forRole(BETA).single().isOpen();

        t.workItems().forRole(BETA).single().approve(t.actor(Users.ROLE_OWNER));
        t.user(Users.JACK).hasAssignment(ALPHA).hasAssignment(BETA);
    }

    @Test
    void withDefaultExecuteAfterAllApprovalsTrueTheApprovedRoleWaitsForTheOther(MidPointConfigTester t) {
        importRoles(t);
        executeAfterAllApprovals(t, true);

        t.actor(Users.JACK).request().assign(ALPHA, BETA);
        t.workItems().forRole(ALPHA).single().approve(t.actor(Users.ROLE_OWNER));

        t.user(Users.JACK).hasNoAssignment(ALPHA).hasNoAssignment(BETA);
        t.workItems().forRole(BETA).single().approve(t.actor(Users.ROLE_OWNER));
        t.user(Users.JACK).hasAssignment(ALPHA).hasAssignment(BETA);
    }

    /**
     * A role without approval in the same Submit as one with: by default it waits too (the part needing no approval
     * is executed with the rest); with {@code defaultExecuteAfterAllApprovals} false it is assigned at Submit.
     */
    @Test
    void aRoleWithoutApprovalInTheSameSubmitFollowsTheOption(MidPointConfigTester t) {
        importRoles(t);

        t.actor(Users.JACK).request().assign(ALPHA, Roles.AUDITOR);
        t.user(Users.JACK).hasNoAssignment(Roles.AUDITOR);
        t.workItems().forRole(ALPHA).single().approve(t.actor(Users.ROLE_OWNER));
        t.user(Users.JACK).hasAssignment(ALPHA).hasAssignment(Roles.AUDITOR);

        executeAfterAllApprovals(t, false);
        t.actor(Users.ANN).request().assign(BETA, Roles.PRIVILEGED_ACCESS);
        t.user(Users.ANN).hasNoAssignment(BETA).hasNoAssignment(Roles.PRIVILEGED_ACCESS);
        t.workItems().forRole(BETA).single().approve(t.actor(Users.ROLE_OWNER));
        t.user(Users.ANN).hasAssignment(BETA).hasNoAssignment(Roles.PRIVILEGED_ACCESS);
        t.cases().forUser(Users.ANN).forRole(Roles.PRIVILEGED_ACCESS).single().isOpen();
    }

    /** One role, as before: the Submit's options go with it, so the comment is kept on the assignment. */
    @Test
    void aSingleRequestCarriesTheComment(MidPointConfigTester t) {
        t.actor(Users.JACK).request().comment("for the audit").assign(Roles.AUDITOR);

        t.user(Users.JACK).hasAssignment(Roles.AUDITOR);
        assertThat(requestorComment(t, Users.JACK.name(), Roles.AUDITOR)).isEqualTo("for the audit");
        t.cases().none();
    }

    /** Several targets: the task the GUI starts, owned by the requester, changes each; the call waits for it. */
    @Test
    void severalTargetsRunAsATaskOwnedByTheRequester(MidPointConfigTester t) {
        t.user("bob").add();

        t.actor("administrator").request(Users.JACK.name(), "bob").assign(Roles.AUDITOR);

        t.user(Users.JACK).hasAssignment(Roles.AUDITOR);
        t.user("bob").hasAssignment(Roles.AUDITOR);
        TaskType task = t.midpoint().find(TaskType.class, t.line().equals("4.0") || t.line().equals("4.4")
                ? "com.evolveum.midpoint.web.page.self.PageAssignmentsList.requestAssignments"
                : "Requesting access for 2 users");
        assertThat(task).as("the task of the Submit").isNotNull();
        assertThat(task.getOwnerRef().getOid()).isEqualTo(t.user("administrator").oid());
    }

    /** A target the change fails on (ann holds Accountant, which excludes Auditor): the task fails on it. */
    @Test
    void theTaskFailsOnATargetWhoseChangeIsRefused(MidPointConfigTester t) {
        Failure failure = t.expectFailure(
                () -> t.actor("administrator").request(Users.JACK, Users.ANN).assign(Roles.AUDITOR));

        assertThat(failure.message()).contains("failed: the task \"").contains("(owned by administrator) failed:");
        t.user(Users.JACK).hasAssignment(Roles.AUDITOR);
        t.user(Users.ANN).hasNoAssignment(Roles.AUDITOR);
    }

    /**
     * A manager requests for two members: the task runs as the manager, whose authorizations allow the change. 4.0
     * adds the task through the model as the manager: midPoint's End user role allows it (a self-owned execute-changes
     * task, {@code self-owned-task-add-execute-changes}); 4.4 and later start it without a check.
     */
    @Test
    void aManagerRequestsForTwoMembers(MidPointConfigTester t) {
        t.user("bob").add();
        t.user("bob").assign("IT");

        t.actor(Users.IT_MANAGER).request(Users.JACK.name(), "bob").assign(Roles.AUDITOR);

        t.user(Users.JACK).hasAssignment(Roles.AUDITOR);
        t.user("bob").hasAssignment(Roles.AUDITOR);
        TaskType task = t.midpoint().find(TaskType.class, t.line().equals("4.0") || t.line().equals("4.4")
                ? "com.evolveum.midpoint.web.page.self.PageAssignmentsList.requestAssignments"
                : "Requesting access for 2 users");
        assertThat(task.getOwnerRef().getOid()).isEqualTo(t.user(Users.IT_MANAGER).oid());
    }

    /**
     * A requester who may not add tasks (a deny of {@code #add} of TaskType): 4.0 refuses the Submit, which adds the
     * task through the model as the requester; 4.4 starts the task with no check; 4.8 and later submit it privileged,
     * which adds {@code #all} to the requester's own authorizations ({@code SecurityContextManagerImpl.runPrivileged}),
     * so their deny still denies (the task's archetype assignment, {@code ClockworkRequestAuthorizer}).
     */
    @Test
    void aRequesterWhoMayNotAddTasks(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.IT_MANAGER).assign("rs-no-task-add");
        t.user("bob").add();
        t.user("bob").assign("IT");

        if (t.line().equals("4.4")) {
            t.actor(Users.IT_MANAGER).request(Users.JACK.name(), "bob").assign(Roles.AUDITOR);
            t.user(Users.JACK).hasAssignment(Roles.AUDITOR);
            t.user("bob").hasAssignment(Roles.AUDITOR);
            return;
        }
        Failure failure = t.expectFailure(
                () -> t.actor(Users.IT_MANAGER).request(Users.JACK.name(), "bob").assign(Roles.AUDITOR));
        failure.isNotAuthorized();
        t.user(Users.JACK).hasNoAssignment(Roles.AUDITOR);
    }

    /** A target the manager may not assign to: the task starts (4.4+) and fails on that target. */
    @Test
    void theTaskFailsOnATargetTheRequesterMayNotAssign(MidPointConfigTester t) {
        if (t.line().equals("4.0")) {
            return;
        }
        Failure failure = t.expectFailure(
                () -> t.actor(Users.IT_MANAGER).request(Users.JACK, Users.HELPDESK).assign(Roles.AUDITOR));

        assertThat(failure.message()).contains("(owned by " + Users.IT_MANAGER.name() + ") failed:");
        t.user(Users.JACK).hasAssignment(Roles.AUDITOR);
        t.user(Users.HELPDESK).hasNoAssignment(Roles.AUDITOR);
    }
}
