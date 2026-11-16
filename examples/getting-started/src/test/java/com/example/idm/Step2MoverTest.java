package com.example.idm;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

@MidPointConfigTest
class Step2MoverTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    @BeforeEach
    void hireJohn(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
    }

    @Test
    void aMoverChangesHisRoleAndHisAccountsOuAndNothingElse(MidPointConfigTester t) {
        t.source("HR").modify("1001", "department", "IT");

        t.task("Reconcile HR").run().verifyAll(c -> {
            c.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
            c.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("ou");
        });
    }

    @Test
    void reconcilingAgainChangesNothing(MidPointConfigTester t) {
        t.task("Reconcile HR").run().none();
    }
}
