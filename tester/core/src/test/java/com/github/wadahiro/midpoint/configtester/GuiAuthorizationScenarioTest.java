package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * What the GUI shows a user, over the sample deployment {@code scenarios/access}: the Help desk role
 * ({@code 020-roles.xml}) opens the users list and a user's details, reads the users of the IT cost center (their
 * contact items) and changes their telephone number only; jack is an End user only.
 *
 * <p>midPoint's End user role, which every user has, lets them get any user, with all items
 * ({@code assignment-target-get}: the GUI's selection windows show the targets of assignments and deputies). So a
 * search finds what {@code #read} allows, but a user's details open for everyone, and the Help desk's narrower read
 * shows no more than the End user's get. The tests show both, as a configuration review would find them.</p>
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class GuiAuthorizationScenarioTest {

    /** The users list ({@code PageUsers}) and the roles list ({@code PageRoles}), by their page actions. */
    private static final String[] USERS_PAGE = {AuthorizationConstants.AUTZ_UI_USERS_ALL_URL,
            AuthorizationConstants.AUTZ_UI_USERS_URL, AuthorizationConstants.AUTZ_UI_USERS_VIEW_URL};
    private static final String[] ROLES_PAGE = {AuthorizationConstants.AUTZ_UI_ROLES_ALL_URL,
            AuthorizationConstants.AUTZ_UI_ROLES_URL, AuthorizationConstants.AUTZ_UI_ROLES_VIEW_URL};

    @Test
    void theHelpDeskOpensTheUsersButNotTheRoles(MidPointConfigTester t) {
        assertThat(t.actor(Users.HELPDESK).canOpen(USERS_PAGE)).isTrue();
        assertThat(t.actor(Users.HELPDESK).canOpen(ROLES_PAGE)).isFalse();
        assertThat(t.actor(Users.JACK).canOpen(USERS_PAGE)).isFalse();
    }

    /**
     * By URL, from the line's index of the GUI's pages: a user's details ({@code /admin/user/<oid>}) need
     * {@code #userDetails} (the GUI's URL mapping {@code /admin/user/**}). That pattern matches the new user page
     * ({@code /admin/user}) too, so {@code #userDetails} opens it as well as its own {@code #user}; creating the user
     * would still need {@code #add}. Every user opens their self-service pages (End user) and the login page; a URL
     * of no page is denied.
     */
    @Test
    void thePagesByUrl(MidPointConfigTester t) {
        assertThat(t.actor(Users.HELPDESK).canOpen("/admin/users")).isTrue();
        assertThat(t.actor(Users.HELPDESK).canOpen("/admin/user/" + Users.JACK.oid())).isTrue();
        assertThat(t.actor(Users.HELPDESK).canOpen("/admin/user")).isTrue();
        assertThat(t.actor(Users.HELPDESK).canOpen("/admin/roles")).isFalse();
        assertThat(t.actor(Users.JACK).canOpen("/admin/users")).isFalse();
        assertThat(t.actor(Users.JACK).canOpen("/admin/user/" + Users.JACK.oid())).isFalse();
        assertThat(t.actor(Users.JACK).canOpen("/self/dashboard")).isTrue();
        assertThat(t.actor(Users.JACK).canOpen("/login")).isTrue();
        assertThat(t.actor(Users.JACK).canOpen("/no/such/page")).isFalse();
    }

    @Test
    void theHelpDeskFindsTheUsersOfIt(MidPointConfigTester t) {
        assertThat(t.actor(Users.HELPDESK).search(UserType.class))
                .containsExactlyInAnyOrder(Users.JACK, Users.ROLE_OWNER, Users.HELPDESK);
    }

    /** The End user's {@code #get} of any user: a user the search does not find still opens, for anyone. */
    @Test
    void everyEndUserGetsAnyUser(MidPointConfigTester t) {
        assertThat(t.actor(Users.HELPDESK).canSearch(Users.ANN)).isFalse();
        assertThat(t.actor(Users.HELPDESK).canGet(Users.ANN)).isTrue();
        assertThat(t.actor(Users.HELPDESK).canRead(Users.ANN)).isFalse();
        assertThat(t.actor(Users.HELPDESK).canRead(Users.JACK)).isTrue();
        assertThat(t.actor(Users.JACK).canGet(Users.ANN)).isTrue();
        assertThat(t.actor(Users.JACK).form(Users.ANN).canRead("assignment")).isTrue();
    }

    @Test
    void anEndUserSeesThemselfAndTheRolesTheyMayRequest(MidPointConfigTester t) {
        assertThat(t.actor(Users.JACK).search(UserType.class)).containsExactly(Users.JACK);
        assertThat(t.actor(Users.JACK).search(RoleType.class)).contains(Roles.PRIVILEGED_ACCESS, Roles.AUDITOR)
                .doesNotContain(Roles.ACCOUNTANT, Roles.HELP_DESK);
    }

    @Test
    void theHelpDeskChangesTheTelephoneNumberOnly(MidPointConfigTester t) {
        Form form = t.actor(Users.HELPDESK).form(Users.JACK);

        assertThat(form.opens()).isTrue();
        assertThat(form.canRead("emailAddress")).isTrue();
        assertThat(form.modifiableItems()).containsExactly("telephoneNumber");
        assertThat(form.canModify("telephoneNumber")).isTrue();
        assertThat(form.canModify("emailAddress")).isFalse();
    }

    @Test
    void theHelpDeskChangesNothingOfAUserOutsideIt(MidPointConfigTester t) {
        Form form = t.actor(Users.HELPDESK).form(Users.ANN);

        assertThat(form.opens()).isTrue();
        assertThat(form.modifiableItems()).isEmpty();
    }

    /** A failed assertion on what a user sees lists the user's authorizations, by role. */
    @Test
    void aFailedAssertionShowsTheAuthorizations(MidPointConfigTester t) {
        AssertionError failure = catchThrowableOfType(() -> {
            try {
                assertThat(t.actor(Users.HELPDESK).search(UserType.class)).contains(Users.ANN);
            } catch (AssertionError e) {
                // what the extension does when the test fails
                throw MidPointConfigTestExtension.withExplanations(e, e.getMessage() + "\n\n" + t.explanations(e));
            }
        }, AssertionError.class);
        assertThat(failure.getMessage()).contains("Authorizations of helpdesk", "role Help desk:",
                "'read-it-users': allow authorization-model-3#read of UserType with a filter; items name, givenName");
    }
}
