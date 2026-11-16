package com.example.idm;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

@MidPointConfigTest
class Step1JoinerTest {

    @Test
    void aJoinerGetsAUserAndAnLdapAccount(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();

        t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP");
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasAttribute("ou", "Sales");
        t.mails().to("e1001@example.com").withSubject("Welcome John Doe").single();
    }
}
