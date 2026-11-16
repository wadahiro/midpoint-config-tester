package com.example.idm;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

@MidPointConfigTest
class HrLifecycleTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    static void hireJohn(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
    }

    @Test
    void joinerAndMover(MidPointConfigTester t) {
        hireJohn(t);
        t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP");

        t.source("HR").modify("1001", "department", "IT");
        t.task("Reconcile HR").run().verifyAll(c -> {   // these, and nothing else
            c.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
            c.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("ou");
        });
    }

    @Test
    void aFutureJoinerStartsOnHerStartDate(MidPointConfigTester t) {
        LocalDate start = t.clock().today().plusDays(10);
        t.source("HR").add("1003", Map.of("firstName", "Kate", "lastName", "Smith", "department", "IT",
                "startDate", start.toString()));
        t.task("Reconcile HR").run();
        t.user("e1003").isDisabled().hasNoAssignment("Dept IT").hasNoAccountOn("LDAP");

        t.clock().advanceTo(start).advance("PT1H").runDue();   // the scanners and the due tasks at the new time
        t.user("e1003").isEnabled().hasAssignment("Dept IT").hasAccountOn("LDAP");
    }

    @Test
    void aLeaverIsDisabledOnHisEndDate(MidPointConfigTester t) {
        hireJohn(t);
        LocalDate end = t.clock().today().plusDays(5);
        t.source("HR").modify("1001", "endDate", end.toString());
        t.task("Reconcile HR").run();
        t.user("e1001").isEnabled();

        t.clock().advanceTo(end).advance("PT1H").runDue();
        t.user("e1001").isDisabled();
        t.target("LDAP").account(JOHN).isDisabled();
    }

    /** Both tests start from John's request: midPoint as the @BeforeAll left it, restored for each. */
    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class JohnHasRequestedVpnAccess {

        @BeforeAll
        void request(MidPointConfigTester t) {
            hireJohn(t);
            t.actor("e1001").request().assign("VPN Access");
            t.mails().to("alice@example.com").withSubject("Approval needed: VPN Access").single();
        }

        @Test
        void aliceApproves(MidPointConfigTester t) {
            t.workItems().assignedTo("alice").single().approve(t.actor("alice")).verifyAll(c -> {
                c.changes().of("e1001").single().only("assignment", "roleMembershipRef");
                c.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("businessCategory");
                c.changes().ofType(CaseType.class).exactly(2);   // the request and its approval, closed
                c.changes().ofType(TaskType.class).added().single();   // the task that executes the approved change
                c.mails().to("e1001@example.com").withSubject("Request approved: VPN Access").single();
            });
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isApproved();
        }

        @Test
        void aliceRejects(MidPointConfigTester t) {
            t.workItems().assignedTo("alice").single().reject(t.actor("alice")).verifyAll(c -> {
                c.changes().ofType(CaseType.class).exactly(2);   // the request and its approval, closed
                c.changes().of("e1001").none();                  // John as he was
                c.connectorCalls().to("LDAP").writes().none();   // his LDAP account as it was
                c.mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single();
            });
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected();
            t.workItems().assignedTo("alice").none();
        }

        @Test
        void undecidedFor3DaysTheRequestIsRejected(MidPointConfigTester t) {
            t.clock().advance("P2D").runTriggerScanner().none();   // still waiting
            t.workItems().assignedTo("alice").single();

            t.clock().advance("P1D").runTriggerScanner().verifyAll(c -> {
                c.changes().ofType(CaseType.class).exactly(2);   // the request and its approval, closed
                c.changes().of("e1001").none();
                c.connectorCalls().to("LDAP").writes().none();
                c.mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single();
            });
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected();
        }
    }
}
