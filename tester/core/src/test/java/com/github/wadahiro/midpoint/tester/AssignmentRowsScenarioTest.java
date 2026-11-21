package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.github.wadahiro.midpoint.configtester.AssignmentRow;
import com.github.wadahiro.midpoint.configtester.AssignmentRows;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.ObjectForm;
import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.OrgRef;
import com.github.wadahiro.midpoint.configtester.names.RoleRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;

/**
 * The rows of a details page's Assignments and Inducements tabs as a user sees them
 * ({@code EditObjectForm.assignments()}, {@code inducements()}), read from the page the GUI renders, and a row's
 * Unassign pressed before Save ({@code unassign}, {@code uninduce}): which rows a user sees, whose Unassign and "+" the
 * GUI shows them (the security enforcer with the object, request phase), what the model does with the save
 * (fixtures/forms/assignment-rows.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class AssignmentRowsScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "assignment-rows.xml");

    private static final OrgRef SITE_1 = new OrgRef("ar-site-1", null, Lines.ALL);
    private static final RoleRef META_1 = new RoleRef("ar-meta-1", null, Lines.ALL);
    private static final RoleRef META_2 = new RoleRef("ar-meta-2", null, Lines.ALL);
    private static final RoleRef TEAM_1 = new RoleRef("ar-team-1", null, Lines.ALL);
    private static final UserRef JACK = new UserRef("ar-jack", null, Lines.ALL);
    private static final UserRef BOSS = new UserRef("ar-boss", null, Lines.ALL);

    private static final String OID_META_1 = "10000000-0000-0000-0000-0000000f0702";
    private static final String OID_META_2 = "10000000-0000-0000-0000-0000000f0703";

    /** The OIDs of the targets ar-team-1 induces, as stored. */
    private static java.util.List<String> inducedOids(MidPointConfigTester t) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType inducement : t.midpoint()
                .find(com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType.class, "ar-team-1")
                .getInducement()) {
            if (inducement.getTargetRef() != null) {
                out.add(inducement.getTargetRef().getOid());
            }
        }
        return out;
    }

    private static void importFixture(MidPointConfigTester t) {
        t.midpoint().importObject(Fixtures.of(t.midpoint(), FIXTURE));
    }

    @Test
    void theRowsOfTheInducementsAndAssignmentsTabs(MidPointConfigTester t) {
        importFixture(t);

        AssignmentRows inducements = t.actor("ar-role-admin").editForm(TEAM_1).inducements()
                .hasRow(META_1).hasRow(META_2).hasRow(META_1, SchemaConstants.ORG_DEFAULT);
        assertThat(inducements.kind()).isEqualTo(AssignmentRows.Kind.INDUCEMENTS);
        assertThat(inducements.all()).extracting(AssignmentRow::label)
                .containsExactlyInAnyOrder("ar-meta-1", "ar-meta-2", "Policy rule ar-rule");
        AssignmentRow meta = inducements.row(META_1).hasUnassign();
        assertThat(meta.target()).isEqualTo(META_1);
        assertThat(meta.unassignShown()).isTrue();
        AssignmentRow rule = inducements.all().stream().filter(r -> r.target() == null).findFirst().orElseThrow();
        assertThat(rule.label()).isEqualTo("Policy rule ar-rule");
        inducements.assignButton().isShown();

        // the archetype's assignment is no row
        AssignmentRows assignments = t.actor("ar-role-admin").editForm(TEAM_1).assignments().hasRow(SITE_1);
        assertThat(assignments.all()).extracting(AssignmentRow::label).containsExactly("ar-site-1");
        // a user's deputy assignment is no row either
        assertThat(t.editForm(JACK).assignments().hasRow(META_2).hasNoRow(BOSS).all()).hasSize(1);
    }

    @Test
    void theRowsOfEveryPageOfTheTable(MidPointConfigTester t) {
        importFixture(t);

        RoleRef last = new RoleRef("ar-many-12", null, Lines.ALL);
        AssignmentRows rows = t.actor("ar-role-admin").editForm(new RoleRef("ar-many", null, Lines.ALL)).inducements()
                .hasRow(new RoleRef("ar-many-01", null, Lines.ALL)).hasRow(last);
        assertThat(rows.all()).hasSize(12);
        rows.row(last).hasUnassign();
    }

    @Test
    void theUnassignOfARowSaves(MidPointConfigTester t) {
        importFixture(t);

        ObjectForm.Submit saved = t.actor("ar-role-admin").editForm(TEAM_1).uninduce(META_1).submit().saves();
        assertThat(inducedOids(t)).doesNotContain(OID_META_1);
        assertThatThrownBy(() -> saved.rejectsWithMessageContaining("not authorized"))
                .isInstanceOf(AssertionError.class).hasMessageStartingWith("The form saves");
        t.actor("ar-role-admin").editForm(TEAM_1).inducements().hasNoRow(META_1).hasRow(META_2);

        // a row's Unassign and a typed item in one Save
        t.actor("ar-role-admin").editForm(TEAM_1).set("description", "team one")
                .unassign(SITE_1, SchemaConstants.ORG_DEFAULT).submit().saves();
        t.actor("ar-role-admin").editForm(TEAM_1).assignments().hasNoRow(SITE_1);
        assertThat(t.midpoint().find(com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType.class,
                "ar-team-1").getDescription()).isEqualTo("team one");
        // the row's own shortcut
        t.actor("ar-role-admin").editForm(TEAM_1).inducements().row(META_2).unassign().submit().saves();
        assertThat(inducedOids(t)).doesNotContain(OID_META_2);
    }

    @Test
    void theModelRefusesAnUnassignTheUserMayOnlyPress(MidPointConfigTester t) {
        importFixture(t);

        t.actor("ar-ui-only").editForm(TEAM_1).inducements().row(META_1).hasUnassign();
        ObjectForm.Submit submit = t.actor("ar-ui-only").editForm(TEAM_1).uninduce(META_1).submit();
        assertThat(submit.saved()).isFalse();
        // the delta deletes the whole value: the model authorizes it as an unassignment of its target (#unassign with
        // order 1 for an inducement), whatever the user's UI actions
        assertThat(String.join("\n", submit.messages())).contains("not authorized for operation with inducement on"
                + " role:10000000-0000-0000-0000-0000000f0704(ar-team-1) with target"
                + " role:10000000-0000-0000-0000-0000000f0702(ar-meta-1)");
        // the refusal by the part a test can state, without the OIDs
        submit.rejectsWithMessageContaining("not authorized for operation with inducement on role:")
                .rejectsWithMessageContaining("(ar-team-1) with target role:").rejectsWithMessageContaining("(ar-meta-1)");
        assertThatThrownBy(() -> submit.rejectsWithMessageContaining("(ar-meta-2)")).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("The form shows no message containing \"(ar-meta-2)\"; its messages: [")
                .hasMessageContaining("with target role:10000000-0000-0000-0000-0000000f0702(ar-meta-1)");
        // the exact message is still asked by rejectsWith
        assertThatThrownBy(() -> submit.rejectsWith("(ar-meta-1)")).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("The form does not show \"(ar-meta-1)\"; its messages: [");
        assertThat(inducedOids(t)).contains(OID_META_1);
    }

    @Test
    void aUserWhoMayChangeNothingHasNoSaveToPress(MidPointConfigTester t) {
        importFixture(t);

        // 4.0: Save shown and disabled; 4.4 and later: hidden without a #modify of the object
        // (AssignmentHolderOperationalButtonsPanel.isObjectStatusAndAuthorizationVerifiedForModification)
        assertThatThrownBy(() -> t.actor("ar-helpdesk").editForm(TEAM_1).submit()).isInstanceOf(AssertionError.class)
                .hasMessage(com.github.wadahiro.midpoint.tester.Lines.atLeast(t.midpoint(), "4.4")
                        ? "The page shows no Save button (the object is read-only to the user, or the page saves with"
                                + " a preview)"
                        : "The page's Save button is disabled (the object is read-only to the user)");
    }

    @Test
    void aUserWithoutAdminUnassignSeesNoUnassign(MidPointConfigTester t) {
        importFixture(t);

        AssignmentRows rows = t.actor("ar-helpdesk").editForm(TEAM_1).inducements().hasRow(META_1);
        rows.row(META_1).hasNoUnassign();
        rows.assignButton().isHidden();
        assertThat(rows.assignButton().shown()).isFalse();
        assertThatThrownBy(() -> rows.row(META_1).hasUnassign()).isInstanceOf(AssertionError.class)
                .hasMessage("ar-helpdesk: the row of Role ar-meta-1 (default) on the Inducements tab of Role"
                        + " ar-team-1 has no Unassign: #adminUnassign is not allowed on Role ar-team-1 (the security"
                        + " enforcer, request phase)");
        assertThatThrownBy(() -> t.actor("ar-helpdesk").editForm(TEAM_1).uninduce(META_1).submit())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cannot unassign Role ar-meta-1 (default): its row on the Inducements tab has"
                        + " no Unassign");
    }

    @Test
    void theUnassignIsDecidedWithTheObject(MidPointConfigTester t) {
        importFixture(t);

        t.actor("ar-object-limited").editForm(TEAM_1).inducements().row(META_1).hasNoUnassign();
        t.actor("ar-object-limited").editForm(META_1).inducements().row(META_2).hasUnassign();
        assertThat(t.actor("ar-object-limited").isAuthorized(
                "http://midpoint.evolveum.com/xml/ns/public/security/authorization-ui-3#adminUnassign", TEAM_1))
                .isFalse();
    }

    @Test
    void aUserWhoMayNotReadTheItemSeesNoRows(MidPointConfigTester t) {
        importFixture(t);

        AssignmentRows rows = t.actor("ar-blind").editForm(TEAM_1).inducements();
        assertThat(rows.all()).isEmpty();
        assertThatThrownBy(() -> rows.hasRow(META_1)).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("ar-blind: the Inducements tab of Role ar-team-1 shows no row of Role"
                        + " ar-meta-1 (default); its rows: none; the administrator sees: ")
                .hasMessageContaining("Role ar-meta-1 (default)").hasMessageContaining("Role ar-meta-2 (default)");
        rows.hasNoRow(META_1);
    }

    @Test
    void theOldDesignOf44ReadsTheSameRows(MidPointConfigTester t) {
        org.junit.jupiter.api.Assumptions.assumeTrue(t.line().equals("4.4"),
                "only 4.4 has both designs (useNewDesign)");
        importFixture(t);
        t.midpoint().importObject(new File(Fixtures.dir("forms"), "assignment-rows-old-design.xml"));

        AssignmentRows inducements = t.actor("ar-old-design").editForm(TEAM_1).inducements().hasRow(META_1)
                .hasRow(META_2);
        assertThat(inducements.all()).hasSize(3);
        inducements.row(META_1).hasUnassign();
        inducements.assignButton().isShown();
        t.actor("ar-old-design").editForm(TEAM_1).assignments().hasRow(SITE_1);
        t.actor("ar-old-design").editForm(TEAM_1).uninduce(META_1).submit().saves();
        assertThat(inducedOids(t)).doesNotContain(OID_META_1);
    }
}
