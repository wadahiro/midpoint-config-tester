package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * Requests to midPoint's REST API ({@link Rest}) over the sample deployment {@code scenarios/access}, whose system
 * configuration references midPoint's default security policy (HTTP Basic on the REST channel): authenticated by the
 * policy, authorized by midPoint (the administrator may call the REST API, an End user may not), the proxy header,
 * what a request changed. 4.0 and 4.4 serve REST through CXF: the tester has no REST calls there.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class RestScenarioTest {

    private static final String ADMINISTRATOR = "administrator";

    private static boolean hasRest(MidPointConfigTester t) {
        String line = t.midpoint().line();
        return !line.equals("4.0") && !line.equals("4.4");
    }

    @Test
    void theAdministratorReadsItselfWithItsPassword(MidPointConfigTester t) {
        if (!hasRest(t)) {
            assertThatThrownBy(() -> t.rest().get("/ws/rest/self"))
                    .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("4.8 or later");
            return;
        }
        t.user(ADMINISTRATOR).modify(m -> m.password("Rest-Secret-1"));

        RestResponse self = t.rest().basic(ADMINISTRATOR, "Rest-Secret-1").get("/ws/rest/self");

        assertThat(self.status()).as("%s", self).isEqualTo(200);
        assertThat(self.header("Content-Type")).startsWith("application/json");
        assertThat(self.body()).contains("\"name\"").contains(ADMINISTRATOR);
        assertThat(self.json().findValuesAsText("name")).contains(ADMINISTRATOR);
        // a read changes no data, but the login is recorded in the user (its last successful authentication)
        self.effects().changes().of(ADMINISTRATOR).single();
        assertThat(self.effects().changes().count()).isEqualTo(1);
    }

    @Test
    void aWrongPasswordOrNoCredentialsAreRefused(MidPointConfigTester t) {
        if (!hasRest(t)) {
            return;
        }
        t.user(ADMINISTRATOR).modify(m -> m.password("Rest-Secret-1"));

        assertThat(t.rest().basic(ADMINISTRATOR, "wrong").get("/ws/rest/self").status()).isEqualTo(401);
        assertThat(t.rest().get("/ws/rest/self").status()).isEqualTo(401);
    }

    @Test
    void aUserWithoutTheRestAuthorizationIsForbidden(MidPointConfigTester t) {
        if (!hasRest(t)) {
            return;
        }
        // jack is an End user: midPoint's GUI, not its REST API (the password policy refuses one with the user's name)
        t.user(Users.JACK).modify(m -> m.password("Rest-Secret-2"));

        RestResponse self = t.rest().basic(Users.JACK.name(), "Rest-Secret-2").get("/ws/rest/self");

        assertThat(self.status()).as("%s", self).isEqualTo(403);
    }

    @Test
    void theResetDropsAPasswordSetInAnotherTest(MidPointConfigTester t) {
        if (!hasRest(t)) {
            return;
        }
        // the earlier tests set the administrator's password: the reset brought back the deployment's (none)
        assertThat(t.rest().basic(ADMINISTRATOR, "Rest-Secret-1").get("/ws/rest/self").status()).isEqualTo(401);
    }

    @Test
    void theProxyHeaderSwitchesToAnotherUser(MidPointConfigTester t) {
        if (!hasRest(t)) {
            return;
        }
        t.user(ADMINISTRATOR).modify(m -> m.password("Rest-Secret-1"));
        String jack = t.user(Users.JACK).oid();

        RestResponse self = t.rest().basic(ADMINISTRATOR, "Rest-Secret-1").header("Switch-To-Principal", jack)
                .get("/ws/rest/self");

        assertThat(self.status()).as("%s", self).isEqualTo(200);
        assertThat(self.body()).contains(Users.JACK.name());
    }

    @Test
    void aModificationIsInTheEffectsOfTheRequest(MidPointConfigTester t) {
        if (!hasRest(t)) {
            return;
        }
        t.user(ADMINISTRATOR).modify(m -> m.password("Rest-Secret-1"));
        String jack = t.user(Users.JACK).oid();

        RestResponse r = t.rest().basic(ADMINISTRATOR, "Rest-Secret-1").patch("/ws/rest/users/" + jack,
                "{\"objectModification\": {\"itemDelta\": {\"modificationType\": \"replace\","
                        + " \"path\": \"telephoneNumber\", \"value\": \"+420 123\"}}}");

        assertThat(r.status()).as("%s", r).isEqualTo(204);
        r.effects().changes().of(Users.JACK.name()).single().only("telephoneNumber");
        assertThat(t.user(Users.JACK).values("telephoneNumber")).containsExactly("+420 123");
        // the tester's login is back after the request: a driver call runs as the administrator again
        t.user(Users.JACK).modify(m -> m.set("telephoneNumber", "+420 456"));
    }
}
