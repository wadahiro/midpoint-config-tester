package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ScriptsScenarioTest} with LDAP on its real connector ({@code scenarios/scripts-real}): the additional SSH
 * connector goes to a mirror of its own, which accepts the scripts; they are the LDAP resource's calls all the same,
 * and fail when the test says so.
 */
@MidPointConfigTest("classpath:scenarios/scripts-real/midpoint-config-tester.yaml")
class ScriptsRealScenarioTest {

    @BeforeEach
    void notOn40(MidPointConfigTester t) {
        ScriptsScenarioTest.notOn40(t);
    }

    @Test
    void theScriptsGoToTheMirrorOfTheAdditionalConnector(MidPointConfigTester t) {
        Effects hired = ScriptsScenarioTest.hire(t);

        hired.connectorCalls().to("LDAP").adds().of(ScriptsScenarioTest.JOHN).single();
        assertThat(hired.connectorCalls().to("LDAP").scripts().single().hasArgument("user", "e1001")
                .codeContains("mkdir").failed()).isFalse();
    }

    @Test
    void theyFailWhenTheTestSaysSo(MidPointConfigTester t) {
        t.target("LDAP").scriptsFail();
        t.source("HR").add("1001", java.util.Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));

        Effects failed = t.task("Reconcile HR").allowPartialErrors().run();

        assertThat(failed.connectorCalls().to("LDAP").scripts().single().failed()).isTrue();
    }
}
