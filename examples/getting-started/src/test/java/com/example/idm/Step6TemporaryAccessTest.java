package com.example.idm;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

@MidPointConfigTest
class Step6TemporaryAccessTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    @Test
    void vpnAccessFor30DaysEndsOnItsDate(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
        LocalDate end = t.clock().today().plusDays(30);
        t.actor("e1001").request().assign("VPN Access", a -> a.validTo(end));   // the assignment's validTo
        t.workItems().assignedTo("alice").single().approve(t.actor("alice"));
        t.target("LDAP").account(JOHN).hasAttribute("businessCategory", "vpn");

        t.clock().advanceTo(end).advance("PT1H").runDue();
        t.target("LDAP").account(JOHN).hasNoAttribute("businessCategory");
        t.user("e1001").hasAccountOn("LDAP");   // his department keeps the account
    }
}
