package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The manifest's {@code databases} and {@code environment}: a script reads another system's database over JDBC, at
 * the URL its environment variable gives (known only when the tester runs); the test puts rows there; each test
 * starts from the init SQL's content.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseScenarioTest {

    static final String READ = "def out = null; groovy.sql.Sql.withInstance(System.getenv('AUDIT_JDBC_URL'),"
            + " System.getenv('AUDIT_JDBC_USER'), '', 'org.postgresql.Driver') { sql -> out = sql.rows("
            + "'SELECT targetName FROM m_audit_event WHERE eventType = 3 ORDER BY id').collect { it.targetName }"
            + ".join(',') }; out";

    @Test
    @Order(1)
    void aScriptReadsTheRowsTheTestPut(MidPointConfigTester t) {
        t.database("audit").execute("INSERT INTO m_audit_event (eventType, targetOid, targetName) VALUES (?, ?, ?)",
                3, "1001-oid", "jdoe");

        assertThat(t.midpoint().script(READ).evaluate().value()).containsExactly("seeded,jdoe");
        assertThat(t.database("audit").rows("SELECT targetName FROM m_audit_event ORDER BY id"))
                .extracting(r -> r.get("targetname")).containsExactly("seeded", "jdoe");
    }

    /** An {@code Instant} (the test's clock, moved) is written as a timestamp. */
    @Test
    @Order(2)
    void aTimeOnTheTestsClock(MidPointConfigTester t) {
        t.clock().advance("P30D");
        java.time.Instant now = t.clock().now();
        t.database("audit").execute("INSERT INTO m_audit_event (eventType, targetName, timestampValue) VALUES (?, ?, ?)",
                3, "later", now);

        assertThat(t.database("audit").rows("SELECT timestampValue FROM m_audit_event WHERE targetName = 'later'"))
                .singleElement().satisfies(r -> assertThat(((java.sql.Timestamp) r.get("timestampvalue")).toInstant())
                        .isEqualTo(now));
    }

    @Test
    @Order(3)
    void eachTestStartsFromTheInitScripts(MidPointConfigTester t) {
        assertThat(t.midpoint().script(READ).evaluate().value()).containsExactly("seeded");
    }
}
