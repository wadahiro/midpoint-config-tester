package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.Effects;
import com.github.wadahiro.midpoint.configtester.MembersPanel;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.OrgRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;

/**
 * The Assign and Unassign of a members panel as a user sees them ({@link MembersPanel}): the panels' relations, the
 * buttons shown by the line's GUI actions, and the operations as the line's GUI runs them: a task owned by the user
 * (4.0 and 4.4 check #executeScript when it starts, 4.8 and later the bulk action), a row's Unassign as the GUI's own
 * change on 4.4 and later (fixtures/forms/member-operations.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class MembersPanelScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "member-operations.xml");
    private static final OrgRef SITE_1 = new OrgRef("mo-site-1", null, Lines.ALL);
    private static final UserRef JACK = new UserRef("mo-jack", null, Lines.ALL);
    private static final String UI = "http://midpoint.evolveum.com/xml/ns/public/security/authorization-ui-3#";

    private static MembersPanel orgTree(MidPointConfigTester t, String user) {
        t.midpoint().importObject(FIXTURE);
        return t.actor(user).orgTreeMembersPanel(t.org("mo-site-1"));
    }

    @Test
    void eachPanelOffersTheRelationsOfItsCategory(MidPointConfigTester t) {
        MembersPanel tree = orgTree(t, "mo-site-manager");
        MembersPanel members = t.actor("mo-site-manager").membersPanel(SITE_1);
        MembersPanel governance = t.actor("mo-site-manager").governancePanel(t.org("mo-site-1"));

        assertThat(tree.kind()).isEqualTo(MembersPanel.Kind.ORG_TREE);
        assertThat(tree.relations()).containsExactly(SchemaConstants.ORG_DEFAULT, SchemaConstants.ORG_MANAGER,
                SchemaConstants.ORG_APPROVER, SchemaConstants.ORG_OWNER);
        assertThat(members.relations()).containsExactly(SchemaConstants.ORG_DEFAULT);
        assertThat(governance.relations()).containsExactly(SchemaConstants.ORG_MANAGER, SchemaConstants.ORG_APPROVER,
                SchemaConstants.ORG_OWNER);
        assertThat(tree.memberTypes()).startsWith(UserType.class, RoleType.class, OrgType.class, ServiceType.class)
                .hasSize(t.line().equals("4.0") || t.line().equals("4.4") || t.line().equals("4.8") ? 4 : 5);
        // the tree has orgs only
        assertThatThrownBy(() -> t.actor("mo-site-manager").orgTreeMembersPanel(t.role("mo-team-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theButtonsAreShownByTheActionOfThePanel(MidPointConfigTester t) {
        MembersPanel tree = orgTree(t, "mo-site-manager");
        boolean v40 = t.line().equals("4.0");

        tree.assignButton().isShown();
        tree.unassignButton().isShown();
        assertThat(tree.assignButton().action()).isEqualTo(UI + "adminAssignOrgMember");
        assertThat(t.actor("mo-site-manager").governancePanel(SITE_1).assignButton().action())
                .isEqualTo(UI + (v40 ? "adminAssignGovernance" : "adminAssignOrgMember"));
        assertThat(t.actor("mo-site-manager").membersPanel(t.role("mo-team-1")).unassignButton().action())
                .isEqualTo(UI + "adminUnassignMember");
        // the org's member actions: the tree and the Members panel on every line, Governance from 4.4
        MembersPanel tree2 = t.actor("mo-members-only").orgTreeMembersPanel(SITE_1);
        tree2.assignButton().isShown();
        t.actor("mo-members-only").membersPanel(SITE_1).unassignButton().isShown();
        assertThat(t.actor("mo-members-only").governancePanel(SITE_1).assignButton().shown()).isEqualTo(!v40);
        // Wicket's roles are the actions of every authorization, a deny's too
        t.actor("mo-denied").orgTreeMembersPanel(SITE_1).assignButton().isShown();
        t.actor("mo-denied").orgTreeMembersPanel(SITE_1).unassignButton().isHidden();
    }

    @Test
    void aHiddenButtonFailsItsClaimsAndItsOperations(MidPointConfigTester t) {
        MembersPanel helpdesk = orgTree(t, "mo-helpdesk");

        helpdesk.assignButton().isHidden();
        helpdesk.unassignButton().isHidden();
        String hidden = "mo-helpdesk does not see Assign on the members panel of Org mo-site-1 in the organization"
                + " tree: no authorization names #adminAssignOrgMember, #all or #guiAll";
        assertThatThrownBy(() -> helpdesk.assignButton().isShown()).hasMessage(hidden);
        assertThatThrownBy(() -> helpdesk.assign(SchemaConstants.ORG_MANAGER, JACK)).hasMessage(hidden);
        assertThatThrownBy(() -> t.actor("mo-site-manager").orgTreeMembersPanel(SITE_1).assignButton().isHidden())
                .hasMessageStartingWith("mo-site-manager sees Assign on the members panel of Org mo-site-1 in the"
                        + " organization tree (allow authorization-ui-3#adminAssignOrgMember,");
        t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
    }

    @Test
    void aManagerIsAssignedAndUnassignedFromTheOrganizationTree(MidPointConfigTester t) {
        MembersPanel tree = orgTree(t, "mo-site-manager");

        Effects assigned = tree.assign(SchemaConstants.ORG_MANAGER, t.user("mo-jack"));

        t.user("mo-jack").hasAssignment("mo-site-1", SchemaConstants.ORG_MANAGER).hasNoAssignment("mo-site-1");
        // the GUI's task is not an effect; the member's change is
        assertThat(assigned.changes().ofType(com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType.class)
                .count()).isZero();
        assigned.changes().of("mo-jack").single();

        // 4.4 and later: the row's Unassign, the GUI's own change; 4.0: the row selected, the panel's Unassign
        Effects unassigned = tree.unassign(SchemaConstants.ORG_MANAGER, JACK);

        t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
        unassigned.changes().of("mo-jack").single();
    }

    @Test
    void aMemberOfTwoRelationsIsUnassignedOfTheOneGiven(MidPointConfigTester t) {
        MembersPanel tree = orgTree(t, "mo-site-manager");
        t.user("mo-jack").assign("mo-site-1");
        t.user("mo-jack").assign("mo-site-1", SchemaConstants.ORG_MANAGER);

        // the tree has both relations: the GUI asks which (4.4+), the row has two (4.0)
        assertThatThrownBy(() -> tree.unassign(JACK)).hasMessageContaining("default, manager");
        tree.unassign(SchemaConstants.ORG_MANAGER, JACK);
        t.user("mo-jack").hasAssignment("mo-site-1").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);

        // the Members panel has one of them: its row unassigns that one
        t.user("mo-jack").assign("mo-site-1", SchemaConstants.ORG_MANAGER);
        t.actor("mo-site-manager").membersPanel(SITE_1).unassign(JACK);
        t.user("mo-jack").hasNoAssignment("mo-site-1").hasAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
    }

    @Test
    void theGovernancePanelAssignsAndUnassignsManagers(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        MembersPanel governance = t.actor("mo-site-manager").governancePanel(t.org("mo-site-1"));

        assertThatThrownBy(() -> governance.assign(SchemaConstants.ORG_DEFAULT, JACK)).hasMessage("mo-site-manager:"
                + " the Governance panel of Org mo-site-1 does not offer relation default; it offers manager,"
                + " approver, owner");
        governance.assign(SchemaConstants.ORG_MANAGER, t.user("mo-jack"), t.user("mo-ann"));
        t.user("mo-jack").hasAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
        t.user("mo-ann").hasAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);

        governance.unassignSelected(t.user("mo-jack"), t.user("mo-ann"));

        t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
        t.user("mo-ann").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
    }

    @Test
    void unassignAllRemovesTheDirectMembersOfTheRelation(MidPointConfigTester t) {
        MembersPanel tree = orgTree(t, "mo-site-manager");
        t.user("mo-jack").assign("mo-site-1", SchemaConstants.ORG_MANAGER);
        t.user("mo-ann").assign("mo-site-1", SchemaConstants.ORG_MANAGER);
        t.user("mo-ann").assign("mo-site-1");

        tree.unassignAll(SchemaConstants.ORG_MANAGER);

        t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
        t.user("mo-ann").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER).hasAssignment("mo-site-1");
    }

    @Test
    void aRolesMembersPanelAssignsItsMembers(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.actor("mo-site-manager").membersPanel(t.role("mo-team-1")).assign(JACK);

        t.user("mo-jack").hasAssignment("mo-team-1");
        assertThatThrownBy(() -> t.actor("mo-site-manager").membersPanel(t.role("mo-team-1"))
                .assign(SchemaConstants.ORG_MANAGER, JACK)).hasMessage("mo-site-manager: the Members panel of Role"
                        + " mo-team-1 does not offer relation manager; it offers default");
    }

    @Test
    void theTaskNeedsTheLinesAuthorizationWhenItStarts(MidPointConfigTester t) {
        MembersPanel uiOnly = orgTree(t, "mo-ui-only");
        boolean bulk = !t.line().equals("4.0") && !t.line().equals("4.4");

        String refused = t.expectFailure(() -> uiOnly.assign(SchemaConstants.ORG_MANAGER, JACK)).message();

        assertThat(refused).startsWith("assign User mo-jack to Org mo-site-1 as manager from the members panel of"
                + " Org mo-site-1 in the organization tree failed: ")
                .contains(bulk ? "http://midpoint.evolveum.com/xml/ns/public/security/authorization-bulk-3#assign"
                        : "http://midpoint.evolveum.com/xml/ns/public/security/authorization-model-3#executeScript");
        t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);

        // the row's Unassign: 4.4 and later the model's #unassign (no task), 4.0 the task's #executeScript
        t.user("mo-jack").assign("mo-site-1", SchemaConstants.ORG_MANAGER);
        String row = t.expectFailure(() -> uiOnly.unassign(SchemaConstants.ORG_MANAGER, JACK)).message();
        assertThat(row).contains(t.line().equals("4.0") ? "#executeScript" : "Not authorized");
        t.user("mo-jack").hasAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
    }

    @Test
    void theTaskFailsOnAMemberTheUserMayNotAssign(MidPointConfigTester t) {
        MembersPanel noAssign = orgTree(t, "mo-no-model-assign");

        String failed = t.expectFailure(() -> noAssign.assign(SchemaConstants.ORG_MANAGER, JACK)).message();

        assertThat(failed).startsWith("assign User mo-jack to Org mo-site-1 as manager from the members panel of"
                + " Org mo-site-1 in the organization tree failed: the task ").contains("(owned by mo-no-model-assign)")
                .contains("last failure: mo-jack: ").containsIgnoringCase("not authorized");
        t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
    }

    @Test
    void aMemberTheUsersSearchDoesNotFindCannotBeSelected(MidPointConfigTester t) {
        MembersPanel tree = orgTree(t, "mo-cannot-see-jack");

        assertThatThrownBy(() -> tree.assign(SchemaConstants.ORG_MANAGER, JACK)).hasMessage("mo-cannot-see-jack"
                + " cannot select User mo-jack in the Assign popup of the members panel of Org mo-site-1 in the"
                + " organization tree: a search by mo-cannot-see-jack of UserType does not find it (#search or"
                + " #read)");
        tree.assign(SchemaConstants.ORG_MANAGER, t.user("mo-ann"));
        t.user("mo-ann").hasAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
        t.user("mo-jack").hasNoAssignment("mo-site-1", SchemaConstants.ORG_MANAGER);
    }
}
