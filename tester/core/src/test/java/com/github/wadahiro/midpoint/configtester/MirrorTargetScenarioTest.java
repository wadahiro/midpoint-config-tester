package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.hr.HrAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.LdapAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Roles;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;

/**
 * The joiner / mover / leaver scenario of {@link HrLifecycleScenarioTest} with HR and LDAP on the mirror connector
 * ({@code scenarios/hr-mirror}): the in-memory stand-in of every resource the manifest leaves on it. Accounts are
 * named by the naming attribute of the manifest, groups keep their members (referential integrity on rename and
 * delete), the mirror's simulated status follows the user, and the reconciliation finds what the test put there.
 */
@MidPointScenarios("classpath:scenarios/hr-mirror/midpoint-config-tester.yaml")
class MirrorTargetScenarioTest {

    static final String JOHN = HrLifecycleScenarioTest.JOHN;
    static final String SALES = HrLifecycleScenarioTest.SALES;
    static final String IT = HrLifecycleScenarioTest.IT;

    @Test
    void joinerMoverLeaverOnMirrors(Scenario s) {
        // no initial data on a mirror: the groups the roles refer to are added by the test
        s.target(Resources.LDAP).system().addGroup(SALES, Map.of("cn", "sales"));
        s.target(Resources.LDAP).system().addGroup(IT, Map.of("cn", "it"));

        s.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
                .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales")
                .set(HrAttrs.START_DATE, LocalDate.of(2020, 4, 1).toString()));
        s.task(Tasks.RECONCILE_HR).run();
        s.user("e1001").hasFullName("John Doe").hasRole(Roles.DEPT_SALES).hasAccountOn(Resources.LDAP);
        s.target(Resources.LDAP).account(JOHN).exists()
                .hasAttribute(LdapAttrs.CN, "John Doe")
                .hasAttribute(LdapAttrs.OU, "Sales")
                .memberOf(SALES).notMemberOf(IT).isEnabled();
        assertThat(s.target(Resources.LDAP).accounts()).containsExactly(JOHN);

        s.source(Resources.HR).modify("1001", HrAttrs.DEPARTMENT, "IT");
        s.task(Tasks.RECONCILE_HR).run();
        s.user("e1001").hasRole(Roles.DEPT_IT).hasNoRole(Roles.DEPT_SALES);
        s.target(Resources.LDAP).account(JOHN).hasAttribute(LdapAttrs.OU, "IT").memberOf(IT).notMemberOf(SALES);

        s.source(Resources.HR).delete("1001");
        s.task(Tasks.RECONCILE_HR).run();
        s.user("e1001").doesNotExist();
        s.target(Resources.LDAP).account(JOHN).doesNotExist();
        // referential integrity: the deleted account is no member any more
        assertThat(s.target(Resources.LDAP).system().groupValues(IT, "member")).isEmpty();
    }

    @Test
    void everyTestStartsFromTheDeploymentAsLoaded(Scenario s) {
        assertThat(s.source(Resources.HR).accounts()).isEmpty();
        assertThat(s.target(Resources.LDAP).accounts()).isEmpty();
        s.user("e1001").doesNotExist();
    }
}
