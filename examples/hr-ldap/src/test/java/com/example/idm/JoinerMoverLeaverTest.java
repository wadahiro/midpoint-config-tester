package com.example.idm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.idm.names.HrAttrs;
import com.example.idm.names.LdapAttrs;
import com.example.idm.names.Resources;
import com.example.idm.names.Roles;
import com.example.idm.names.Tasks;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.names.midpoint.UserItems;

/**
 * Hire, move and terminate an employee in HR; the reconciliation as deployed does the rest. The deployment
 * ({@code midpoint-config-tester.yaml} next to the POM) is loaded once per JVM; every test starts from it.
 */
@MidPointConfigTest
class JoinerMoverLeaverTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";
    static final String SALES = "cn=sales,ou=groups,dc=example,dc=com";
    static final String IT = "cn=it,ou=groups,dc=example,dc=com";

    @Test
    void anEmployeeJoinsMovesAndLeaves(MidPointConfigTester t) {
        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
                .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales")
                .set(HrAttrs.START_DATE, LocalDate.of(2020, 4, 1).toString()));
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").hasFullName("John Doe").hasItem(UserItems.COST_CENTER, "Sales")
                .hasRole(Roles.DEPT_SALES).isEnabled().hasAccountOn(Resources.LDAP);
        t.target(Resources.LDAP).account(JOHN).hasAttribute(LdapAttrs.OU, "Sales").memberOf(SALES).isEnabled();
        t.mails().to("e1001@example.com").withSubject("Welcome John Doe").exactly(1);

        t.source(Resources.HR).modify("1001", HrAttrs.DEPARTMENT, "IT");
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").hasRole(Roles.DEPT_IT).hasNoRole(Roles.DEPT_SALES);
        t.target(Resources.LDAP).account(JOHN).hasAttribute(LdapAttrs.OU, "IT").memberOf(IT).notMemberOf(SALES);

        t.source(Resources.HR).delete("1001");
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").doesNotExist();
        t.target(Resources.LDAP).account(JOHN).doesNotExist();
    }

    /** {@code why} shows the mappings that set a value in this test: here the template's full name. */
    @Test
    void theFullNameComesFromTheTemplate(MidPointConfigTester t) {
        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1002").set(HrAttrs.FIRST_NAME, "Ann")
                .set(HrAttrs.LAST_NAME, "Lee").set(HrAttrs.DEPARTMENT, "IT").set(HrAttrs.START_DATE, "2020-04-01"));
        t.task(Tasks.RECONCILE_HR).run();

        t.user("e1002").hasItem(UserItems.FULL_NAME, "Ann Lee");
        assertThat(t.user("e1002").why(UserItems.FULL_NAME).last().owner())
                .isEqualTo("User template");
    }
}
