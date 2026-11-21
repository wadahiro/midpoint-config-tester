package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.CreateMemberButton;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.NewObjectForm;
import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.OrgRef;
import com.github.wadahiro.midpoint.configtester.names.RoleRef;

/**
 * The "+" of a members panel as a user sees it ({@link CreateMemberButton}): shown by the GUI's rule of the line, its
 * buttons, and a click: the GUI's form of the new member, saved as the user (fixtures/forms/members.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class CreateMemberButtonScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "members.xml");

    private static CreateMemberButton siteAdmins(MidPointConfigTester t, String site) {
        t.midpoint().importObject(FIXTURE);
        return t.actor("member-site-admin").createMemberButton(t.org(site));
    }

    @Test
    void theButtonIsShownByTheActionOfTheMembersPanel(MidPointConfigTester t) {
        siteAdmins(t, "member-site-1").isShown();

        boolean everyone = t.line().equals("4.0");
        // #adminAddOrgMember for an org's members, #adminAddMember for a role's
        assertThat(t.actor("member-helpdesk").createMemberButton(t.org("member-site-1")).shown()).isEqualTo(everyone);
        assertThat(t.actor("member-site-admin").createMemberButton(t.role("member-team-1")).shown())
                .isEqualTo(everyone);
        t.actor("member-team-admin").createMemberButton(new RoleRef("member-team-1", null, Lines.ALL)).isShown();
        assertThat(t.actor("member-team-admin").createMemberButton(new OrgRef("member-site-1", null, Lines.ALL))
                .shown()).isEqualTo(everyone);
    }

    @Test
    void aDenyOfTheActionShowsTheButtonToo(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        // Wicket's roles are the actions of every authorization, a deny's too; a page asks the security enforcer
        t.actor("member-denied").createMemberButton(t.org("member-site-1")).isShown();
        assertThat(t.actor("member-denied").canOpen(AuthorizationConstants.AUTZ_UI_ADMIN_ADD_ORG_MEMBER_ACTION_URI))
                .isFalse();
    }

    @Test
    void aHiddenButtonFailsItsClaims(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        CreateMemberButton helpdesk = t.actor("member-helpdesk").createMemberButton(t.org("member-site-1"));

        if (t.line().equals("4.0")) {
            assertThatThrownBy(helpdesk::isHidden).hasMessage("member-helpdesk sees the + of Org member-site-1's"
                    + " members: 4.0 shows the + to every user who opens the page; the header menu's Create needs"
                    + " #adminAddOrgMember");
            return;
        }
        helpdesk.isHidden().offersNoNewMember(OrgType.class, "member-category");
        String hidden = "member-helpdesk does not see the + of Org member-site-1's members: no authorization names"
                + " #adminAddOrgMember, #all or #guiAll";
        assertThatThrownBy(helpdesk::isShown).hasMessage(hidden);
        assertThatThrownBy(() -> helpdesk.offersNewMember(OrgType.class, "member-category")).hasMessage(hidden);
        assertThatThrownBy(() -> helpdesk.click(OrgType.class, "member-category")).hasMessage(hidden);
        // the configuration offers it all the same
        assertThat(helpdesk.newMemberOffers()).hasSize(4);
        assertThatThrownBy(() -> siteAdmins(t, "member-site-1").isHidden()).hasMessage("member-site-admin sees the +"
                + " of Org member-site-1's members (allow authorization-ui-3#adminAddOrgMember,"
                + " authorization-ui-3#orgAll, authorization-ui-3#usersAll)");
    }

    @Test
    void aClickOpensTheFormOfTheNewMemberAndSavesIt(MidPointConfigTester t) {
        siteAdmins(t, "member-site-1").offersNewMember(OrgType.class, "member-category")
                .click(OrgType.class, "member-category").set("name", "member-cat-1").submit().saves();

        t.org("member-cat-1").hasParent("member-site-1").hasAssignment("member-site-1")
                .hasAssignment("member-category");
    }

    @Test
    void theModelRefusesTheMembershipTheUserMayNotAssign(MidPointConfigTester t) {
        // the same button of another site: the page opens and takes the name, the save is refused by #assign
        NewObjectForm.Submit refused = siteAdmins(t, "member-site-2").isShown()
                .click(OrgType.class, "member-category").set("name", "member-cat-2").submit();

        assertThat(refused.saved()).isFalse();
        refused.rejectsWithMessageContaining("member-site-admin").rejectsWithMessageContaining("not authorized")
                .rejectsWithMessageContaining("with target org:10000000-0000-0000-0000-0000000f0111(member-site-2)");
        t.org("member-cat-2").doesNotExist();
    }

    @Test
    void theModelRefusesAMemberTheUserMayNotAdd(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        // offered and shown, but the user may add users only: the page of a new service has no field to fill in
        NewObjectForm service = t.actor("member-team-admin").createMemberButton(t.role("member-team-1"))
                .offersNewMember(ServiceType.class).click(ServiceType.class);

        assertThatThrownBy(() -> service.set("name", "member-service-1").submit())
                .hasMessageStartingWith("Cannot fill in name: ");
        NewObjectForm.Submit refused = service.submit();
        assertThat(refused.saved()).isFalse();
        if (t.line().equals("4.0")) {
            // 4.0 requires the name before it authorizes the request
            refused.rejectsWith("FATAL_ERROR: No name in new object null as produced by template null in iteration 0,"
                    + " we cannot process an object without a name");
        } else {
            // 4.4 quotes the user's name twice
            refused.rejectsWithMessageContaining("member-team-admin").rejectsWithMessageContaining("not authorized")
                    .rejectsWithMessageContaining("authorization-model-3#add on service:");
        }
    }

    @Test
    void onlyAButtonTheGuiOffersIsClicked(MidPointConfigTester t) {
        CreateMemberButton button = siteAdmins(t, "member-site-1");

        button.offersNoNewMember(OrgType.class, "member-site");
        assertThatThrownBy(() -> button.click(OrgType.class, "member-site")).hasMessageStartingWith(
                "member-site-admin: the + of Org member-site-1 has no button for OrgType with archetype member-site"
                        + " (relation default); it offers: OrgType with archetype member-category (relation default),");
        assertThatThrownBy(() -> button.offersNewMember(UserType.class, "member-employee", SchemaConstants.ORG_OWNER))
                .hasMessageStartingWith("member-site-admin: the + of Org member-site-1 does not offer UserType with"
                        + " archetype member-employee (relation owner); it offers: ");
        assertThatThrownBy(() -> button.offersNoNewMember(UserType.class, "member-employee"))
                .hasMessage("member-site-admin: the + of Org member-site-1 offers UserType with archetype"
                        + " member-employee (relation default)");
    }

    @Test
    void aManagerIsCreatedWithTheRelationOfTheButton(MidPointConfigTester t) {
        siteAdmins(t, "member-site-1")
                .click(UserType.class, "member-employee", SchemaConstants.ORG_MANAGER).set("name", "member-mgr-1")
                .submit().saves();

        t.user("member-mgr-1").hasAssignment("member-site-1", SchemaConstants.ORG_MANAGER)
                .hasNoAssignment("member-site-1").hasAssignment("member-employee");
    }

    @Test
    void theNewMemberIsInTheParentOrgRefOfAnOrgAsTheLinesGuiPutsIt(MidPointConfigTester t) {
        CreateMemberButton button = siteAdmins(t, "member-site-1");

        // the employee's template shows the new user's parentOrgRef to the validation of description
        button.click(UserType.class, "member-employee").validate("description", "x").fails("parentOrgRef: default");
        button.click(UserType.class, "member-employee", SchemaConstants.ORG_MANAGER).validate("description", "x")
                .fails("parentOrgRef: manager");
        // 4.0 puts the org there with any relation; 4.4 and later with a relation stored into parentOrgRef (by
        // default those of kind member: default and manager)
        button.click(UserType.class, "member-employee", SchemaConstants.ORG_APPROVER).validate("description", "x")
                .fails(t.line().equals("4.0") ? "parentOrgRef: approver" : "parentOrgRef: none");
        // the same form without the button, and "create child" of the organization tree: no relation set
        t.newObjectForm(UserType.class).archetype("member-employee")
                .memberOf("member-site-1", SchemaConstants.ORG_APPROVER).validate("description", "x")
                .fails(t.line().equals("4.0") ? "parentOrgRef: approver" : "parentOrgRef: none");
        t.newObjectForm(UserType.class).archetype("member-employee").parent("member-site-1")
                .validate("description", "x").fails("parentOrgRef: no relation");
        t.newObjectForm(UserType.class).archetype("member-employee").memberOf("member-site-1")
                .validate("description", "x").fails("parentOrgRef: no relation");
        // a role is never a parent
        t.newObjectForm(UserType.class).archetype("member-employee").memberOf(t.role("member-team-1"), null)
                .validate("description", "x").fails("parentOrgRef: none");
    }

    @Test
    void aRolesButtonOfNoTypeIsClickedWithTheTypeGiven(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        CreateMemberButton team = t.actor("member-team-admin").createMemberButton(t.role("member-team-1"));

        team.click(UserType.class).set("name", "member-team-user").submit().saves();
        team.click(UserType.class, (String) null, SchemaConstants.ORG_OWNER).set("name", "member-team-owner")
                .submit().saves();

        t.user("member-team-user").hasAssignment("member-team-1");
        t.user("member-team-owner").hasAssignment("member-team-1", SchemaConstants.ORG_OWNER)
                .hasNoAssignment("member-team-1");
        assertThatThrownBy(() -> team.click(OrgType.class)).hasMessageStartingWith("member-team-admin: the + of Role"
                + " member-team-1 has no button for OrgType without an archetype (relation default)");
    }
}
