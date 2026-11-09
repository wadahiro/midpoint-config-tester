package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * {@code t.logs()}: what scripts log through {@code log} (INFO and up, with their arguments filled in and the
 * exception logged with them), narrowed by level and text; each test starts with none.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogsScenarioTest {

    @Test
    @Order(1)
    void whatAScriptLogs(MidPointConfigTester t) {
        t.midpoint().script("log.debug('not captured'); log.info('reissued: {} for {}', 't1/alice', 'alice');"
                + " log.error('failed for {}', 'bob', new IllegalStateException('no mail')); 'ok'").evaluate();

        assertThat(t.logs().messages()).containsExactly("reissued: t1/alice for alice", "failed for bob");
        t.logs().info().containing("reissued").exactly(1);
        assertThat(t.logs().errors().single().error()).isEqualTo("java.lang.IllegalStateException: no mail");
        assertThatThrownBy(() -> t.logs().warnings().exactly(1))
                .hasMessageContaining("Expected 1 log messages at WARN, found 0")
                .hasMessageContaining("INFO reissued: t1/alice for alice");
    }

    @Test
    @Order(2)
    void eachTestStartsWithNone(MidPointConfigTester t) {
        t.logs().none();
    }
}
