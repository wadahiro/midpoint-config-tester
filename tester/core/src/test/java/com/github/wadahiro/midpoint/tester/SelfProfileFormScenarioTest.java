package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.EditObjectForm;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * The user's own profile page as a form ({@code Actor.selfProfileForm()}), run by the line's GUI over
 * {@code scenarios/access} (fixtures/self-profile: a role for one's telephone number in both phases, one in the
 * request phase only, and a 4.8+ self-profile page configuration hiding the Assignments panel). The page is the
 * details page of the logged-in user (4.0 {@code PageSelfProfile}, 4.4 the design's {@code PageUserSelfProfile},
 * 4.8+ {@code PageUserSelfProfile}), which {@code #selfAll} opens without {@code #userDetails}; its Save is the
 * details page's Save, authorized in the request and the execution phase.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class SelfProfileFormScenarioTest {

    private static void importRoles(MidPointConfigTester t) {
        t.midpoint().importObject(Fixtures.of(t.midpoint(), new File(Fixtures.dir("self-profile"), "roles.xml")));
    }

    /** An End user without #userDetails cannot open their details page, but opens and saves their profile. */
    @Test
    void aUserWithoutUserDetailsChangesTheirProfile(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");

        assertThatThrownBy(() -> t.actor(Users.JACK).editForm(UserType.class, Users.JACK.name())
                .set("telephoneNumber", "+1 555 0100").submit())
                .hasMessageContaining("may not open /admin/user");   // 4.4's new design: /admin/userNew/{oid}

        t.actor(Users.JACK).selfProfileForm().set("telephoneNumber", "+1 555 0100").submit().saves();

        t.user(Users.JACK).hasItem("telephoneNumber", "+1 555 0100");
    }

    /** The page's fields follow the user's authorizations: an item they may not change has no input. */
    @Test
    void anItemTheUserMayNotChangeIsReadOnly(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");

        assertThatThrownBy(() -> t.actor(Users.JACK).selfProfileForm().set("costCenter", "Sales").submit())
                .hasMessageContaining("costCenter").hasMessageContaining("read-only");
        t.user(Users.JACK).hasItem("costCenter", "IT");
    }

    /**
     * The Save goes through the model: a change allowed in the request phase only is refused at execution (4.0,
     * 4.8+: the field takes the value, the Save is refused; 4.4: its GUI shows the field read-only already).
     */
    @Test
    void theSaveNeedsTheExecutionPhaseToo(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-request-only");

        if (t.line().equals("4.4")) {
            assertThatThrownBy(() -> t.actor(Users.JACK).selfProfileForm().set("telephoneNumber", "+1 555 0100")
                    .submit()).hasMessageContaining("telephoneNumber").hasMessageContaining("read-only");
        } else {
            EditObjectForm.Submit submit = t.actor(Users.JACK).selfProfileForm()
                    .set("telephoneNumber", "+1 555 0100").submit();
            assertThat(submit.saved()).as(submit.messages().toString()).isFalse();
            assertThat(String.join("\n", submit.messages())).containsIgnoringCase("not authorized");
        }
        assertThat(((UserType) t.user(Users.JACK).asObject()).getTelephoneNumber()).isNull();
    }

    /**
     * The panels of the profile page: the details page's, on 4.8+ those of the GUI profile's
     * {@code adminGuiConfiguration/selfProfilePage} when configured (4.0 and 4.4 have no such element: the fixture's
     * variant configures nothing).
     */
    @Test
    void theProfilePageFollowsSelfProfilePageOnFourEight(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");
        t.actor(Users.JACK).selfProfileForm().tabs().hasTab("Assignments");

        t.user(Users.JACK).assign("sp-profile-config");

        if (Lines.atLeast(t.midpoint(), "4.8")) {
            t.actor(Users.JACK).selfProfileForm().tabs().hasNoTab("Assignments");
        } else {
            t.actor(Users.JACK).selfProfileForm().tabs().hasTab("Assignments");
        }
        // the details page of the same user, as the administrator, keeps the panel
        t.editForm(UserType.class, Users.JACK.name()).tabs().hasTab("Assignments");
    }

    /** 4.4's old design: PageSelfProfile sends to the old PageUserSelfProfile (/self/profile/user), the same form. */
    @Test
    void theOldDesignOf44OpensItsOwnProfilePage(MidPointConfigTester t) {
        assumeTrue(t.line().equals("4.4"), "only 4.4 has both designs (useNewDesign)");
        importRoles(t);
        t.midpoint().importObject(new File(Fixtures.dir("self-profile"), "old-design.xml"));
        t.user(Users.JACK).assign("sp-self-editor");
        t.user(Users.JACK).assign("sp-old-design");

        t.actor(Users.JACK).selfProfileForm().tabs().hasTab("Assignments");
        t.actor(Users.JACK).selfProfileForm().set("telephoneNumber", "+1 555 0100").submit().saves();

        t.user(Users.JACK).hasItem("telephoneNumber", "+1 555 0100");
    }

    /**
     * The Assignments tab of the profile page as the user sees it: with {@code #unassign} of their own requestable
     * roles the row has its Unassign, and the Save (the model's unassignment, then the End user role's execution-phase
     * modify of one's assignments) removes the role.
     */
    @Test
    void aUserRemovesTheirOwnRoleFromTheProfilePage(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");
        t.user(Users.JACK).assign("sp-self-unassign");
        t.user(Users.JACK).assign(Roles.AUDITOR);

        t.actor(Users.JACK).selfProfileForm().assignments().row(Roles.AUDITOR).hasUnassign();
        t.actor(Users.JACK).selfProfileForm().unassign(Roles.AUDITOR).submit().saves();

        t.user(Users.JACK).hasNoAssignment(Roles.AUDITOR);
    }

    /** The three requestable roles of fixtures/self-profile/unassign-roles.xml, assigned to jack. */
    private static ObjectRef[] threeRoles(MidPointConfigTester t) {
        t.midpoint().importObject(new File(Fixtures.dir("self-profile"), "unassign-roles.xml"));
        ObjectRef[] roles = {
            ObjectRef.of("RoleType", "sp-requestable-1", "10000000-0000-0000-0000-0000000f0b11"),
            ObjectRef.of("RoleType", "sp-requestable-2", "10000000-0000-0000-0000-0000000f0b12"),
            ObjectRef.of("RoleType", "sp-requestable-3", "10000000-0000-0000-0000-0000000f0b13")};
        for (ObjectRef role : roles) {
            t.user(Users.JACK).assign(role.name());
        }
        return roles;
    }

    /** Three rows removed one at a time, each with a Save of its own (a #modify of an item of oneself too). */
    @Test
    void aUserRemovesThreeRolesOneSaveEach(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");
        t.user(Users.JACK).assign("sp-self-unassign");
        ObjectRef[] roles = threeRoles(t);

        for (ObjectRef role : roles) {
            t.actor(Users.JACK).selfProfileForm().unassign(role).submit().saves();
            t.user(Users.JACK).hasNoAssignment(role.name());
        }
    }

    /** Three rows removed in one Save: every row's Unassign pressed, then Save once. */
    @Test
    void aUserRemovesThreeRolesInOneSave(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");
        t.user(Users.JACK).assign("sp-self-unassign");
        ObjectRef[] roles = threeRoles(t);

        t.actor(Users.JACK).selfProfileForm().unassign(roles[0]).unassign(roles[1]).unassign(roles[2]).submit()
                .saves();

        for (ObjectRef role : roles) {
            t.user(Users.JACK).hasNoAssignment(role.name());
        }
    }

    /**
     * Without a #modify of any item of oneself (the End user role and the Unassign only): one row per Save, as
     * {@link #aUserRemovesThreeRolesOneSaveEach}.
     */
    @Test
    void aUserWithoutModifyRemovesThreeRolesOneSaveEach(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-unassign");
        ObjectRef[] roles = threeRoles(t);

        for (ObjectRef role : roles) {
            t.actor(Users.JACK).selfProfileForm().unassign(role).submit().saves();
            t.user(Users.JACK).hasNoAssignment(role.name());
        }
    }

    /** Without a #modify of any item of oneself: three rows in one Save, as {@link #aUserRemovesThreeRolesInOneSave}. */
    @Test
    void aUserWithoutModifyRemovesThreeRolesInOneSave(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-unassign");
        ObjectRef[] roles = threeRoles(t);

        t.actor(Users.JACK).selfProfileForm().unassign(roles[0]).unassign(roles[1]).unassign(roles[2]).submit()
                .saves();

        for (ObjectRef role : roles) {
            t.user(Users.JACK).hasNoAssignment(role.name());
        }
    }

    /**
     * The form is immutable: {@code unassign} returns a new form. Its result dropped (a loop calling it on the same
     * form), the Save would change nothing (4.0: saved, "There were no changes to be saved"; without a #modify of
     * oneself, Save disabled): {@code submit} fails naming the dropped changes instead.
     */
    @Test
    void aDroppedUnassignFailsTheSubmit(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");
        t.user(Users.JACK).assign("sp-self-unassign");
        ObjectRef[] roles = threeRoles(t);

        EditObjectForm form = t.actor(Users.JACK).selfProfileForm();
        for (ObjectRef role : roles) {
            form.unassign(role);
        }
        assertThatThrownBy(form::submit).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 changes derived from it were dropped: unassign(Role sp-requestable-1),"
                        + " unassign(Role sp-requestable-2), unassign(Role sp-requestable-3)")
                .hasMessageContaining("returns a new form");
        for (ObjectRef role : roles) {
            t.user(Users.JACK).hasAssignment(role.name());
        }

        // the results kept: one Save removes the three rows
        EditObjectForm kept = t.actor(Users.JACK).selfProfileForm();
        for (ObjectRef role : roles) {
            kept = kept.unassign(role);
        }
        kept.submit().saves();
        for (ObjectRef role : roles) {
            t.user(Users.JACK).hasNoAssignment(role.name());
        }
    }

    /** As {@link #aDroppedUnassignFailsTheSubmit}, for a user without a #modify of any item of oneself. */
    @Test
    void aDroppedUnassignFailsTheSubmitWithoutModify(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-unassign");
        ObjectRef[] roles = threeRoles(t);

        EditObjectForm form = t.actor(Users.JACK).selfProfileForm().unassign(roles[0]);
        form.unassign(roles[1]);
        form.unassign(roles[2]);
        assertThatThrownBy(form::submit).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 changes derived from it were dropped: unassign(Role sp-requestable-2),"
                        + " unassign(Role sp-requestable-3)")
                .hasMessageContaining("This form records unassign(Role sp-requestable-1).");
        for (ObjectRef role : roles) {
            t.user(Users.JACK).hasAssignment(role.name());
        }
    }

    /** Without {@code #unassign} the row shows no Unassign, and pressing it is not possible. */
    @Test
    void withoutTheAuthorizationTheRowHasNoUnassign(MidPointConfigTester t) {
        importRoles(t);
        t.user(Users.JACK).assign("sp-self-editor");
        t.user(Users.JACK).assign(Roles.AUDITOR);

        t.actor(Users.JACK).selfProfileForm().assignments().row(Roles.AUDITOR).hasNoUnassign();
        assertThatThrownBy(() -> t.actor(Users.JACK).selfProfileForm().unassign(Roles.AUDITOR).submit())
                .hasMessageContaining("Unassign");

        t.user(Users.JACK).hasAssignment(Roles.AUDITOR);
    }
}
