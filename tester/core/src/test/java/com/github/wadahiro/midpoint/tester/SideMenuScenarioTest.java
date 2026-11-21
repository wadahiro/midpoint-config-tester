package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.common.MidPointLine;
import com.github.wadahiro.midpoint.configtester.MenuPage;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.SideMenu;

/**
 * The side menu as a user sees it ({@link com.github.wadahiro.midpoint.configtester.Actor#sideMenu}), built by the
 * GUI for the user: the sections and items the user's authorizations show, by the GUI's rule (any authorization
 * naming one of the page's actions, whatever its decision, so a denied page is listed and refused), the request-access
 * item by its line-independent name and its line's label and URL, a GUI profile's additional link, the experimental
 * Consents page (4.4 and later), an empty menu, the administrator's sections per line, and the failures
 * (fixtures/menu/menu.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class SideMenuScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("menu"), "menu.xml");

    private static void importFixture(MidPointConfigTester t) {
        t.midpoint().importObject(Fixtures.of(t.midpoint(), FIXTURE));
    }

    /** 4.0 and 4.4: the shopping cart "Request a role"; 4.8 and later: the wizard "Request access". */
    private static boolean shoppingCart(MidPointConfigTester t) {
        return MidPointLine.compare(t.line(), "4.8") < 0;
    }

    @Test
    void anEndUserSeesTheSelfServiceSectionOnly(MidPointConfigTester t) {
        importFixture(t);

        SideMenu menu = t.actor("menu-end-user").sideMenu();

        menu.hasSection("SELF SERVICE").hasNoSection("ADMINISTRATION").hasNoSection("PageAdmin.menu.top.configuration")
                .hasNoSection("ADDITIONAL")
                .hasItem(MenuPage.SELF_HOME).hasItem(MenuPage.SELF_PROFILE).hasItem(MenuPage.SELF_CREDENTIALS)
                .hasItem(MenuPage.REQUEST_ACCESS)
                .hasNoItem(MenuPage.USERS).hasNoItem(MenuPage.DASHBOARD).hasNoItem(MenuPage.SELF_CONSENTS);
        assertThat(menu.sectionLabels()).containsExactly("SELF SERVICE");
        assertThat(menu.labels()).containsExactly("Home", "Profile", "Credentials",
                shoppingCart(t) ? "Request a role" : "Request access");

        SideMenu.Item request = menu.item(MenuPage.REQUEST_ACCESS);
        assertThat(request.url()).isEqualTo(shoppingCart(t) ? "/self/assignmentShoppingCart" : "/self/requestAccess");
        assertThat(request.key()).isEqualTo(shoppingCart(t) ? "PageAdmin.menu.request" : "PageRequestAccess.title");
        assertThat(request.page()).isEqualTo(shoppingCart(t) ? "PageAssignmentShoppingCart" : "PageRequestAccess");
        assertThat(request.section().label()).isEqualTo("SELF SERVICE");
        assertThat(request.parent()).isNull();
        assertThat(request.actions()).extracting(SideMenuScenarioTest::local)
                .containsExactly("selfAll", "selfRequestAssignments");
        // the same item by its URL, its label and its key
        assertThat(menu.shows(request.url())).isTrue();
        assertThat(menu.shows(request.label())).isTrue();
        assertThat(menu.shows(request.key())).isTrue();
        // and the page opens
        assertThat(t.actor("menu-end-user").canOpen(request.url())).isTrue();
    }

    /** A user without the request-access authorization: no item, and the page is refused ({@code canOpen}). */
    @Test
    void aUserWithoutTheRequestAuthorizationHasNoRequestItem(MidPointConfigTester t) {
        importFixture(t);

        SideMenu menu = t.actor("menu-viewer").sideMenu();

        menu.hasItem(MenuPage.SELF_HOME).hasItem(MenuPage.SELF_CREDENTIALS)
                .hasNoItem(MenuPage.SELF_PROFILE).hasNoItem(MenuPage.REQUEST_ACCESS);
        assertThat(menu.labels()).containsExactly("Home", "Credentials");
        for (String url : MenuPage.REQUEST_ACCESS.urls()) {
            assertThat(t.actor("menu-viewer").canOpen(url)).as(url).isFalse();
        }
    }

    /** A help desk with the users' pages: ADMINISTRATION with Users > All users, nothing of roles, no self-service. */
    @Test
    void aHelpDeskSeesTheUsersListAndNoSelfService(MidPointConfigTester t) {
        importFixture(t);

        SideMenu menu = t.actor("menu-helpdesk").sideMenu();

        menu.hasSection("ADMINISTRATION").hasNoSection("SELF SERVICE").hasNoSection("PageAdmin.menu.top.configuration")
                .hasItem(MenuPage.USERS).hasItem("/admin/users").hasItem("All users")
                .hasNoItem(MenuPage.ROLES).hasNoItem(MenuPage.ORG_TREE).hasNoItem(MenuPage.DASHBOARD)
                .hasNoItem(MenuPage.SELF_HOME);
        assertThat(menu.sectionLabels()).containsExactly("ADMINISTRATION");

        SideMenu.Item users = menu.item(MenuPage.USERS);
        assertThat(users.label()).isEqualTo("All users");
        assertThat(users.key()).isEqualTo("PageAdmin.menu.top.users.list");
        assertThat(users.page()).isEqualTo("PageUsers");
        assertThat(users.parent().label()).isEqualTo("Users");
        // the heading has no page; its other sub-items (New user: #user or #usersAll, 4.0 #add) are hidden
        assertThat(users.parent().url()).isNull();
        assertThat(users.parent().items()).extracting(SideMenu.Item::label).containsExactly("All users");
        assertThat(menu.section("ADMINISTRATION").labels()).containsExactly("Users");
        assertThat(t.actor("menu-helpdesk").canOpen("/admin/users")).isTrue();
    }

    /**
     * The menu's rule: an item is listed when any of the user's authorizations names one of its page's actions
     * (Wicket's roles of the session), whatever the decision; opening the page is the security enforcer's decision.
     * So a denied page is in the menu, and refused. 4.11 and later first hide an item whose action a deny
     * authorization names ({@code SecurityEnforcer.isAuthorizationDenied}).
     */
    @Test
    void aDeniedPageIsListedButRefused(MidPointConfigTester t) {
        importFixture(t);

        SideMenu menu = t.actor("menu-denied").sideMenu();
        assertThat(t.actor("menu-denied").canOpen("/admin/users")).isFalse();

        if (MidPointLine.compare(t.line(), "4.11") >= 0) {
            menu.hasNoItem(MenuPage.USERS);
            assertThat(menu.items()).isEmpty();
            AssertionError failure = catchThrowableOfType(() -> menu.hasItem(MenuPage.USERS), AssertionError.class);
            assertThat(failure.getMessage())
                    .contains("'deny-users-page' deny [authorization-ui-3#users] of role menu-deny-users")
                    .contains("4.11 and later hide an item whose action a deny authorization names");
            return;
        }
        menu.hasItem(MenuPage.USERS);
        AssertionError failure = catchThrowableOfType(() -> menu.hasNoItem(MenuPage.USERS), AssertionError.class);
        assertThat(failure.getMessage())
                .contains("menu-denied: the side menu shows 'All users' (/admin/users) under ADMINISTRATION > Users")
                .contains("'deny-users-page' deny [authorization-ui-3#users] of role menu-deny-users")
                .contains("the menu: ADMINISTRATION: Users > All users");
    }

    /**
     * The ADDITIONAL section lists the GUI profile's links: one to a GUI page when the user may open it (the page's
     * actions), an external one to anyone who has it (4.4: never, its item needs a page).
     */
    @Test
    void theAdditionalSectionListsTheProfilesLinks(MidPointConfigTester t) {
        importFixture(t);

        SideMenu menu = t.actor("menu-linked").sideMenu();

        menu.hasSection("ADDITIONAL").hasItem("My credentials").hasItem("/self/credentials");
        assertThat(menu.sectionLabels()).containsExactly("SELF SERVICE", "ADDITIONAL");
        SideMenu.Item credentials = menu.item("My credentials");
        assertThat(credentials.url()).isEqualTo("/self/credentials");
        assertThat(credentials.page()).isEqualTo("PageSelfCredentials");
        assertThat(credentials.key()).isNull();
        assertThat(credentials.actions()).extracting(SideMenuScenarioTest::local)
                .containsExactly("selfAll", "selfCredentials");
        if (t.line().equals("4.4")) {
            menu.hasNoItem("Help pages");
            assertThat(menu.section("ADDITIONAL").labels()).containsExactly("My credentials");
        } else {
            menu.hasItem("Help pages").hasItem("https://example.com/help");
            assertThat(menu.section("ADDITIONAL").labels()).containsExactly("Help pages", "My credentials");
            SideMenu.Item link = menu.item("Help pages");
            assertThat(link.url()).isEqualTo("https://example.com/help");
            assertThat(link.key()).isNull();
            assertThat(link.actions()).isEmpty();
            assertThat(link.page()).isNull();
        }
        // a user without the page's action sees neither the page's link (4.4+) nor, on 4.4, the external one
        t.actor("menu-end-user").sideMenu().hasNoSection("ADDITIONAL");
    }

    /**
     * The Consents page is experimental: 4.4 and later list it only with the GUI profile's
     * {@code enableExperimentalFeatures}; 4.0 has no Consents item at all.
     */
    @Test
    void anExperimentalPageNeedsTheExperimentalFeatures(MidPointConfigTester t) {
        importFixture(t);

        t.actor("menu-end-user").sideMenu().hasNoItem(MenuPage.SELF_CONSENTS);
        SideMenu experimenter = t.actor("menu-experimenter").sideMenu();
        if (t.line().equals("4.0")) {
            experimenter.hasNoItem(MenuPage.SELF_CONSENTS);
        } else {
            experimenter.hasItem(MenuPage.SELF_CONSENTS);
            assertThat(experimenter.item(MenuPage.SELF_CONSENTS).label()).isEqualTo("Consents");
        }
        // the page itself opens for both: #selfAll is among its actions
        assertThat(t.actor("menu-end-user").canOpen("/self/consents")).isTrue();
    }

    @Test
    void aUserWithoutAuthorizationsHasAnEmptyMenu(MidPointConfigTester t) {
        importFixture(t);

        SideMenu menu = t.actor("menu-nobody").sideMenu();

        assertThat(menu.sections()).isEmpty();
        assertThat(menu.items()).isEmpty();
        assertThat(menu.labels()).isEmpty();
        menu.hasNoSection("SELF SERVICE").hasNoItem(MenuPage.SELF_HOME);
    }

    /** The administrator ({@code #all}) sees every section of the line, and the pages of every kind. */
    @Test
    void theAdministratorSeesEverySection(MidPointConfigTester t) {
        SideMenu menu = t.actor("administrator").sideMenu();

        // by key: the configuration section's text differs between the lines' localizations
        List<String> sections = new ArrayList<>(List.of("PageAdmin.menu.selfService", "PageAdmin.menu.mainNavigation"));
        if (MidPointLine.compare(t.line(), "4.11") >= 0) {
            sections.add("PageAdmin.menu.top.integration");
        }
        sections.add("PageAdmin.menu.top.configuration");
        assertThat(menu.sections()).extracting(SideMenu.Section::key).containsExactly(sections.toArray(new String[0]));
        assertThat(menu.sectionLabels()).startsWith("SELF SERVICE", "ADMINISTRATION");
        menu.hasItem(MenuPage.SELF_HOME).hasItem(MenuPage.SELF_PROFILE).hasItem(MenuPage.SELF_CREDENTIALS)
                .hasItem(MenuPage.REQUEST_ACCESS).hasItem(MenuPage.DASHBOARD)
                .hasItem(MenuPage.USERS).hasItem(MenuPage.ORG_TREE).hasItem(MenuPage.ROLES).hasItem(MenuPage.SERVICES)
                .hasItem(MenuPage.RESOURCES).hasItem(MenuPage.MY_WORK_ITEMS).hasItem(MenuPage.ALL_WORK_ITEMS)
                .hasItem(MenuPage.CERTIFICATION_CAMPAIGNS).hasItem(MenuPage.CERTIFICATION_DEFINITIONS)
                .hasItem(MenuPage.TASKS).hasItem(MenuPage.REPORTS).hasItem(MenuPage.AUDIT_LOG)
                .hasItem(MenuPage.REPOSITORY_OBJECTS).hasItem(MenuPage.IMPORT_OBJECT)
                .hasItem(MenuPage.SYSTEM_CONFIGURATION).hasItem(MenuPage.ABOUT);
        // where the lines put a page: the audit log under Reports (4.0, 4.4) or on its own; resources in INTEGRATION (4.11)
        SideMenu.Item audit = menu.item(MenuPage.AUDIT_LOG);
        assertThat(audit.section().label()).isEqualTo("ADMINISTRATION");
        assertThat(audit.parent() != null).isEqualTo(MidPointLine.compare(t.line(), "4.8") < 0);
        assertThat(menu.item(MenuPage.RESOURCES).section().label())
                .isEqualTo(MidPointLine.compare(t.line(), "4.11") >= 0 ? "INTEGRATION" : "ADMINISTRATION");
        assertThat(menu.item(MenuPage.USERS).parent().label()).isEqualTo("Users");
    }

    /** A failed claim says what the page asks for, whether the user's authorizations name it, and lists the menu. */
    @Test
    void aFailedClaimExplainsTheMenu(MidPointConfigTester t) {
        importFixture(t);

        SideMenu viewer = t.actor("menu-viewer").sideMenu();
        AssertionError missing = catchThrowableOfType(() -> viewer.hasItem(MenuPage.REQUEST_ACCESS),
                AssertionError.class);
        assertThat(missing.getMessage())
                .contains("menu-viewer: the side menu shows no item of REQUEST_ACCESS")
                .contains("authorization-ui-3#selfAll", "authorization-ui-3#selfRequestAssignments")
                .contains("(whatever its decision), and none does")
                .contains("the menu: SELF SERVICE: Home, Credentials");

        AssertionError noSuchPage = catchThrowableOfType(() -> viewer.hasItem("/no/such/page"), AssertionError.class);
        assertThat(noSuchPage.getMessage()).contains("no page of the GUI is at /no/such/page");

        AssertionError shown = catchThrowableOfType(() -> viewer.hasNoItem("Home"), AssertionError.class);
        assertThat(shown.getMessage())
                .contains("menu-viewer: the side menu shows 'Home' (/self) under SELF SERVICE")
                .contains("'home-and-credentials' allow [authorization-ui-3#selfDashboard,"
                        + " authorization-ui-3#selfCredentials] of role menu-dashboard-only");

        AssertionError section = catchThrowableOfType(() -> viewer.hasSection("ADMINISTRATION"), AssertionError.class);
        assertThat(section.getMessage()).contains("shows no section ADMINISTRATION");
        assertThat(viewer.toString()).startsWith("the side menu of menu-viewer: the menu: SELF SERVICE: Home, Credentials");
    }

    private static String local(String action) {
        return action.substring(action.indexOf('#') + 1);
    }
}
