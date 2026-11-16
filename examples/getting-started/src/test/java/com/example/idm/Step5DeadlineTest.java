package com.example.idm;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

@MidPointConfigTest
class Step5DeadlineTest {

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class JohnHasRequestedVpnAccess {

        @BeforeAll
        void request(MidPointConfigTester t) {
            t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                    "startDate", "2020-04-01"));
            t.task("Reconcile HR").run();
            t.actor("e1001").request().assign("VPN Access");
        }

        @Test
        void twoDaysOnTheRequestStillWaits(MidPointConfigTester t) {
            t.clock().advance("P2D").runTriggerScanner().none();
            t.workItems().assignedTo("alice").single();
        }

        @Test
        void undecidedFor3DaysTheRequestIsRejected(MidPointConfigTester t) {
            t.clock().advance("P3D").runTriggerScanner().verifyAll(c -> {
                c.changes().ofType(CaseType.class).exactly(2);   // the request and its approval, closed
                c.changes().of("e1001").none();
                c.connectorCalls().to("LDAP").writes().none();
                c.mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single();
            });
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected();
        }
    }
}
