package com.example.idm;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

@MidPointConfigTest
class Step3DatesTest {

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
        LocalDate end = t.clock().today().plusDays(5);
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01", "endDate", end.toString()));
        t.task("Reconcile HR").run();
        t.user("e1001").isEnabled();

        t.clock().advanceTo(end).advance("PT1H").runDue();
        t.user("e1001").isDisabled();
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").isDisabled();
    }
}
