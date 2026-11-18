package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * A REST request to a deployment whose system configuration references no security policy ({@code scenarios/hr}):
 * midPoint has no authentication for the REST channel and answers 500 with no body; the tester says why. With
 * {@link RestScenarioTest} in the same JVM, the REST layer started for one deployment serves the other.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class RestWithoutSecurityPolicyTest {

    @Test
    void aRequestFailsWithTheMissingReference(MidPointConfigTester t) {
        String line = t.midpoint().line();
        if (line.equals("4.0") || line.equals("4.4")) {
            return;
        }
        t.user("administrator").modify(m -> m.password("Rest-Secret-1"));

        assertThatThrownBy(() -> t.rest().basic("administrator", "Rest-Secret-1").get("/ws/rest/self"))
                .hasMessageContaining("globalSecurityPolicyRef");
        // the tester's login is back after the failed request
        t.user("administrator").modify(m -> m.set("telephoneNumber", "+420 123"));
        assertThat(t.user("administrator").values("telephoneNumber")).containsExactly("+420 123");
    }
}
