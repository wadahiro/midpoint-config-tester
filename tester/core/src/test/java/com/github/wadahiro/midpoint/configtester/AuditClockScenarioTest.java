package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.access.Users;
import com.github.wadahiro.midpoint.tester.AuditRecord;

/**
 * The audit's timestamps do not move with {@code t.clock()}: midPoint's audit service fills a record's timestamp from
 * {@code System.currentTimeMillis()} called from Java (or the lightweight identifier's, also the real time), which
 * the tester cannot shift. A test that compares audit timestamps with the shifted clock has to know it.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class AuditClockScenarioTest {

    @Test
    void anAuditRecordAfterAnAdvanceCarriesTheRealTime(MidPointConfigTester t) {
        Instant real = Instant.now();
        t.clock().advance("P30D");

        t.user(Users.JACK).modify(m -> m.set("telephoneNumber", "+1 555 0199"));

        AuditRecord change = t.audit().ofType("MODIFY_OBJECT").execution().target(Users.JACK.name()).single();
        assertThat(Duration.between(real, change.timestamp()).abs()).isLessThan(Duration.ofHours(1));
        assertThat(Duration.between(change.timestamp(), t.clock().now())).isGreaterThan(Duration.ofDays(29));
    }
}
