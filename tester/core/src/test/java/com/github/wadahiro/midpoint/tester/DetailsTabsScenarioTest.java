package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.ObjectForm;
import com.github.wadahiro.midpoint.configtester.Tab;
import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.OrgRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;

/**
 * The tabs of an object's details page as a user sees them ({@link ObjectForm#tabs}), read from the page the GUI
 * renders: the administrator's default tabs, a tab hidden by the user's GUI configuration (4.0: objectForms; 4.4 and
 * later: the objectDetails panel's visibility; 4.4's old design: objectForms), a new object's page, how a tab is named,
 * the failures (fixtures/forms/tabs.xml, its 4.0 variant, tabs-old-design.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class DetailsTabsScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "tabs.xml");

    private static void importFixture(MidPointConfigTester t) {
        t.midpoint().importObject(Fixtures.of(t.midpoint(), FIXTURE));
    }

    private static boolean line40(MidPointConfigTester t) {
        return t.line().equals("4.0");
    }

    @Test
    void theAdministratorSeesTheDefaultTabsOfAnOrgARoleAndAUser(MidPointConfigTester t) {
        importFixture(t);

        ObjectForm.Tabs org = t.editForm(OrgType.class, "tabs-site-1").tabs()
                .hasTab("Basic").hasTab("Assignments").hasTab("Inducements").hasTab("Members").hasTab("Governance");
        // the GUI's order: the lines put History, Cases and Inducements elsewhere
        assertThat(org.names()).containsSubsequence("Basic", "Assignments", "Members", "Governance");
        Tab governance = org.tab("Governance");
        assertThat(governance.shown()).isTrue();
        assertThat(governance.hiddenBecause()).isNull();
        assertThat(governance.key()).isEqualTo("pageRole.governance");
        assertThat(governance.identifier()).isEqualTo(line40(t) ? "focusTabGovernance" : "orgGovernance");
        // 4.4 and later: the panels' sub-panels; 4.0 has none
        if (line40(t)) {
            assertThat(org.tab("Assignments").subtabs()).isEmpty();
        } else {
            assertThat(org.tab("Assignments").subtabs()).extracting(Tab::identifier)
                    .contains("allAssignments", "roleAssignments", "orgAssignments");
            assertThat(org.tab("Assignments").subtabs()).filteredOn(Tab::shown).extracting(Tab::label)
                    .contains("All", "Role", "Organization");
        }

        t.editForm(RoleType.class, "tabs-team-1").tabs().hasTab("Basic").hasTab("Inducements").hasTab("Members")
                .hasTab("Governance");
        ObjectForm.Tabs user = t.editForm(UserType.class, "tabs-jack").tabs().hasTab("Basic").hasTab("Assignments")
                .hasTab("Delegations").hasNoTab("Governance").hasNoTab("Members");
        assertThat(user.all()).extracting(Tab::label).doesNotContain("Governance", "Members", "Inducements");
    }

    @Test
    void theUsersGuiConfigurationHidesATab(MidPointConfigTester t) {
        importFixture(t);

        ObjectForm.Tabs org = t.actor("tabs-helpdesk").editForm(OrgType.class, "tabs-site-1").tabs()
                .hasTab("Basic").hasTab("Assignments").hasTab("Members").hasNoTab("Governance");
        Tab governance = org.tab("Governance");
        assertThat(governance.shown()).isFalse();
        assertThat(org.names()).doesNotContain("Governance");
        if (line40(t)) {
            // the objectForms of the type name the tabs the user sees: every other one is hidden
            assertThat(governance.hiddenBecause()).isEqualTo("no objectForm of OrgType names focusTabGovernance");
            assertThat(org.names()).containsExactly("Basic", "Assignments", "Members");
            org.hasNoTab("Inducements");
        } else {
            // the panel's visibility, merged into the page's panels: the other panels as they are
            assertThat(governance.hiddenBecause()).isEqualTo("visibility hidden");
            org.hasTab("Inducements");
        }

        // the configuration is the type's: the page of a role and the administrator's page of the org keep it
        t.actor("tabs-helpdesk").editForm(RoleType.class, "tabs-team-1").tabs().hasTab("Governance");
        t.editForm(OrgType.class, "tabs-site-1").tabs().hasTab("Governance");
    }

    @Test
    void aNewObjectsPageHasNoGovernanceYet(MidPointConfigTester t) {
        importFixture(t);

        ObjectForm.Tabs tabs = t.actor("tabs-site-admin").newObjectForm(OrgType.class).parent("tabs-site-1").tabs()
                .hasTab("Basic").hasTab("Assignments").hasNoTab("Governance").hasNoTab("Members");
        assertThat(tabs.tab("Governance").hiddenBecause()).isEqualTo(line40(t) ? "not on a new object"
                : "applicableForOperation modify: not on a new object");
        // the same user's page of the org that exists
        t.actor("tabs-site-admin").editForm(OrgType.class, "tabs-site-1").tabs().hasTab("Governance");
        t.newObjectForm(RoleType.class).tabs().hasTab("Inducements").hasNoTab("Governance");
    }

    @Test
    void aTabIsNamedByItsLabelItsKeyOrItsIdentifier(MidPointConfigTester t) {
        importFixture(t);
        String identifier = line40(t) ? "focusTabGovernance" : "orgGovernance";

        ObjectForm.Tabs tabs = t.editForm(OrgType.class, "tabs-site-1").tabs()
                .hasTab("Governance").hasTab("pageRole.governance").hasTab(identifier);
        assertThat(tabs.tab("pageRole.governance").label()).isEqualTo("Governance");
        assertThat(tabs.tab(identifier).label()).isEqualTo("Governance");
        assertThat(tabs.tab("pageAdminFocus.assignments").label()).isEqualTo("Assignments");
        // a hidden tab by its key and identifier as well
        ObjectForm.Tabs helpdesk = t.actor("tabs-helpdesk").editForm(OrgType.class, "tabs-site-1").tabs()
                .hasNoTab("pageRole.governance").hasNoTab(identifier);
        assertThat(helpdesk.tab(identifier).shown()).isFalse();
    }

    @Test
    void aFailureListsTheShownAndTheHiddenTabs(MidPointConfigTester t) {
        importFixture(t);
        ObjectForm.Tabs helpdesk = t.actor("tabs-helpdesk").editForm(OrgType.class, "tabs-site-1").tabs();

        if (line40(t)) {
            assertThatThrownBy(() -> helpdesk.hasTab("Governance")).isInstanceOf(AssertionError.class).hasMessage(
                    "tabs-helpdesk: the page of Org tabs-site-1 shows no Governance tab; its tabs: Basic,"
                            + " Assignments, Members; hidden: Projections (no objectForm of OrgType names"
                            + " focusTabProjections), Cases (no objectForm of OrgType names focusTabTasks), Applicable"
                            + " Policies (no objectForm of OrgType names focusTabApplicablePolicies), Inducements (no"
                            + " objectForm of OrgType names focusTabInducements), Governance (no objectForm of OrgType"
                            + " names focusTabGovernance)");
        } else {
            assertThatThrownBy(() -> helpdesk.hasTab("Governance")).isInstanceOf(AssertionError.class)
                    .hasMessageStartingWith("tabs-helpdesk: the page of Org tabs-site-1 shows no Governance tab;"
                            + " its tabs: Basic, Projections, Assignments, ")
                    .hasMessageContaining("; hidden: ").hasMessageContaining("Governance (visibility hidden)");
        }
        assertThatThrownBy(() -> helpdesk.hasNoTab("Members")).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("tabs-helpdesk: the page of Org tabs-site-1 shows a Members tab; its tabs:"
                        + " Basic, ");
        assertThatThrownBy(() -> helpdesk.tab("Foo")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("The page of Org tabs-site-1 has no tab Foo (label, key or identifier); its"
                        + " tabs: Basic, ");
        // a tab of no page of the type, as the administrator
        assertThatThrownBy(() -> t.editForm(UserType.class, "tabs-jack").tabs().hasTab("Governance"))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("The page of User tabs-jack shows no Governance tab; its tabs: Basic, ");
        assertThatThrownBy(() -> t.newObjectForm(OrgType.class).parent("tabs-site-1").tabs().hasTab("Governance"))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("The page of a new Org in tabs-site-1 shows no Governance tab; its tabs: ")
                .hasMessageContaining(line40(t) ? "Governance (not on a new object)"
                        : "Governance (applicableForOperation modify: not on a new object)");
        // a user who may not open the page fails as the forms do (4.4: /admin/userNew)
        assertThatThrownBy(() -> t.actor("tabs-site-admin").editForm(UserType.class, "tabs-jack").tabs())
                .isInstanceOf(AssertionError.class).hasMessageStartingWith("tabs-site-admin may not open /admin/user")
                .hasMessageContaining("/10000000-0000-0000-0000-0000000f0203 (PageUser)");
    }

    @Test
    void theEditFormOfAGeneratedName(MidPointConfigTester t) {
        importFixture(t);
        OrgRef site = new OrgRef("tabs-site-1", null, Lines.ALL);
        UserRef helpdesk = new UserRef("tabs-helpdesk", null, Lines.ALL);

        t.editForm(site).tabs().hasTab("Governance");
        t.editForm(new OrgRef("tabs-site-1", "10000000-0000-0000-0000-0000000f0201", Lines.ALL)).tabs()
                .hasTab("Governance");
        t.actor(helpdesk).editForm(site).tabs().hasTab("Members").hasNoTab("Governance");
        t.editForm(new UserRef("tabs-jack", null, Lines.ALL)).tabs().hasTab("Delegations");
        assertThatThrownBy(() -> t.editForm(new OrgRef("tabs-no-such-org", null, Lines.ALL)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No OrgType tabs-no-such-org");
    }

    @Test
    void theOldDesignOf44ReadsTheTabsOfItsTabbedPanel(MidPointConfigTester t) {
        assumeTrue(t.line().equals("4.4"), "only 4.4 has both designs (useNewDesign)");
        importFixture(t);
        t.midpoint().importObject(new File(Fixtures.dir("forms"), "tabs-old-design.xml"));

        ObjectForm.Tabs tabs = t.actor("tabs-old-design").editForm(OrgType.class, "tabs-site-1").tabs()
                .hasTab("Basic").hasTab("Members").hasNoTab("Governance").hasNoTab("Inducements");
        assertThat(tabs.names()).containsExactly("Basic", "Assignments", "Members");
        assertThat(tabs.tab("pageRole.governance").identifier()).isEqualTo("focusTabGovernance");
        assertThat(tabs.tab("Governance").hiddenBecause())
                .isEqualTo("no objectForm of OrgType names focusTabGovernance");
    }
}
