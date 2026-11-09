package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The manifest's {@code constants}: midPoint's constants ({@code <constants>} of its config.xml), what
 * {@code <const>} and {@code midpoint.getConst} read.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class ConstantsScenarioTest {

    @Test
    void aScriptReadsTheManifestsConstant(MidPointConfigTester t) {
        assertThat(t.midpoint().script("midpoint.getConst('hrTenant')").evaluate().value()).containsExactly("t-1001");
    }
}
