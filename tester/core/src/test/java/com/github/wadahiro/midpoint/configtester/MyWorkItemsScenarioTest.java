package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * A user's own work items ({@code t.actor(u).workItems()}): those the GUI's "My work items" lists, searched as the
 * user, over the requests of {@link AccessRequestScenarioTest} (Privileged Access: its owner, then a Security officer).
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class MyWorkItemsScenarioTest {

    @Test
    void theApproverSeesTheItemInTheirWorkItems(MidPointConfigTester t) {
        t.actor(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        WorkItem owners = t.actor(Users.ROLE_OWNER).workItems().forUser(Users.JACK).forRole(Roles.PRIVILEGED_ACCESS)
                .single();
        assertThat(owners.stage()).isEqualTo(1);
        assertThat(owners.assignees()).containsExactly(Users.ROLE_OWNER.name());
        // not the others': the officers' stage has not started, the requester approves nothing
        t.actor(Users.SEC_OFFICER).workItems().none();
        t.actor(Users.JACK).workItems().none();

        // decided as the user it was found for
        t.actor(Users.ROLE_OWNER).workItems().approve();
        t.actor(Users.ROLE_OWNER).workItems().none();
        t.actor(Users.SEC_OFFICER).workItems().single().atStage(2).hasAssignee(Users.SEC_OFFICER);
        t.actor(Users.SEC_ANALYST).workItems().single().atStage(2).hasAssignee(Users.SEC_ANALYST);

        t.actor(Users.SEC_OFFICER).workItems().single().reject();
        t.cases().forUser(Users.JACK).single().isRejected();
        t.actor(Users.SEC_ANALYST).workItems().none();
    }

    @Test
    void aFailedClaimNamesTheUsersWorkItems(MidPointConfigTester t) {
        t.actor(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        assertThatThrownBy(() -> t.actor(Users.SEC_OFFICER).workItems().forUser(Users.JACK).single())
                .hasMessageContaining("Expected one open work item in sec-officer's work items, for user jack, found 0")
                .hasMessageContaining("all open work items: ['");
        // read as the administrator, a decision names who decides
        assertThatThrownBy(() -> t.workItems().single().approve()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was read as the administrator");
    }

    @Test
    void aDeputySeesTheWorkItemsOfTheUserTheyStandInFor(MidPointConfigTester t) {
        // a deputy of the role owner, with the Approver role
        t.midpoint().addObjectXml("<user xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'"
                + " xmlns:org='http://midpoint.evolveum.com/xml/ns/public/common/org-3'><name>stand-in</name>"
                + "<assignment><targetRef oid='" + t.user(Users.ROLE_OWNER).oid() + "' type='UserType'"
                + " relation='org:deputy'/></assignment>"
                + "<assignment><targetRef oid='00000000-0000-0000-0000-00000000000a' type='RoleType'/></assignment>"
                + "</user>");
        t.actor(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        WorkItem item = t.actor("stand-in").workItems().single();
        assertThat(item.assignees()).containsExactly(Users.ROLE_OWNER.name());
        t.actor(Users.ROLE_OWNER).workItems().single();
    }
}
