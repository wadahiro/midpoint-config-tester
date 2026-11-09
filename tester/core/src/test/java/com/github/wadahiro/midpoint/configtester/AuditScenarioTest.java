package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.tester.AuditRecord;

/**
 * {@code t.audit()}: the records midPoint writes to its audit log (request and execution of each change, with the
 * target, initiator and outcome), narrowed by type, stage and target; each test starts with none.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditScenarioTest {

    /** A script that searches the audit log through midPoint's model, as audit reports do, for the line it runs on. */
    static final String SEARCH = "def result = new com.evolveum.midpoint.schema.result.OperationResult('search')\n"
            + "def task = midpoint.getCurrentTask()\n"
            + "audit.metaClass.respondsTo(audit, 'listRecords')"
            + " ? audit.listRecords('select * from m_audit_event as aer where 1=1 order by aer.timestampValue desc',"
            + " [:], task, result).collect { it.eventType.toString() }"
            + " : audit.searchObjects(prismContext.queryFor(com.evolveum.midpoint.xml.ns._public.common.audit_3"
            + ".AuditEventRecordType).build(), null, task, result).collect { it.eventType.toString() }";

    @Test
    @Order(1)
    void theRecordsOfAChange(MidPointConfigTester t) {
        String oid = t.midpoint().addObjectXml("<user xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                + "<name>audited</name></user>").oid();
        t.midpoint().deleteObject(UserType.class, oid);

        t.audit().ofType("ADD_OBJECT").target("audited").request().exactly(1);
        AuditRecord deletion = t.audit().ofType("DELETE_OBJECT").execution().targetType("UserType").single();
        assertThat(deletion.targetOid()).isEqualTo(oid);
        assertThat(deletion.targetName()).isEqualTo("audited");
        assertThat(deletion.outcome()).isEqualTo("SUCCESS");
        assertThat(deletion.initiatorName()).isEqualTo("administrator");
        assertThat(deletion.timestamp()).isNotNull();
        assertThat(deletion.deltas()).anyMatch(d -> d.startsWith("DELETE UserType " + oid));
        t.audit().ofType("DELETE_OBJECT").execution().outcome("FATAL_ERROR").none();
        assertThatThrownBy(() -> t.audit().ofType("DELETE_OBJECT").target("nobody").single())
                .hasMessageContaining("Expected 1 audit records of DELETE_OBJECT of target nobody, found 0")
                .hasMessageContaining("DELETE_OBJECT EXECUTION UserType audited SUCCESS");
    }

    /** The records of a part of the test: what the code wrote, not what came before. */
    @Test
    @Order(2)
    void theRecordsWrittenWhileTheCodeRan(MidPointConfigTester t) {
        String oid = t.midpoint().addObjectXml("<user xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                + "<name>captured</name></user>").oid();

        Audit deletion = t.audit(() -> t.midpoint().deleteObject(UserType.class, oid));

        deletion.ofType("ADD_OBJECT").none();
        deletion.ofType("DELETE_OBJECT").execution().target(oid).exactly(1);
        t.audit().ofType("ADD_OBJECT").target("captured").request().exactly(1);
    }

    /**
     * {@code t.repositoryDatabase()}: midPoint's own database, as another system reads the audit table over JDBC (the
     * line's table and columns: 4.0 stores the event type as its ordinal, 4.4 on as its name).
     */
    @Test
    @Order(3)
    void theAuditTableAsAnotherSystemReadsIt(MidPointConfigTester t) {
        String oid = t.midpoint().addObjectXml("<user xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                + "<name>read-over-jdbc</name></user>").oid();
        t.midpoint().deleteObject(UserType.class, oid);

        java.util.List<java.util.Map<String, Object>> rows = "4.0".equals(t.line())
                ? t.repositoryDatabase().rows("SELECT targetName, outcome FROM m_audit_event"
                        + " WHERE eventType = 3 AND eventStage = 1 AND targetOid = ?", oid)
                : t.repositoryDatabase().rows("SELECT targetName, outcome FROM ma_audit_event"
                        + " WHERE eventType = 'DELETE_OBJECT' AND eventStage = 'EXECUTION' AND targetOid = ?::uuid", oid);
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.get("targetname")).isEqualTo("read-over-jdbc"));
        assertThat(t.repositoryDatabase().rows("4.0".equals(t.line()) ? "SELECT oid FROM m_user WHERE oid = ?"
                : "SELECT oid FROM m_user WHERE oid = ?::uuid", oid)).isEmpty();
    }

    /**
     * midPoint audits to its repository database, as in production: its own search of the audit log (as audit reports
     * and scripts do) finds the records.
     */
    @Test
    @Order(4)
    void midPointsSearchOfTheAuditLog(MidPointConfigTester t) {
        t.midpoint().addObjectXml("<user xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                + "<name>searched</name></user>");

        assertThat(t.audit().ofType("ADD_OBJECT").target("searched").count()).isGreaterThan(0);
        Object audit = t.midpoint().bean(com.evolveum.midpoint.model.api.ModelAuditService.class);
        assertThat(t.midpoint().script(SEARCH).variable("audit", audit, Object.class).evaluate().value())
                .contains("ADD_OBJECT");
    }

    @Test
    @Order(5)
    void eachTestStartsWithNone(MidPointConfigTester t) {
        t.audit().none();
    }
}
