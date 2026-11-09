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
@MidPointConfigTest("classpath:scenarios/hr-mirror/midpoint-config-tester.yaml")
class MirrorTargetScenarioTest {

    static final String JOHN = HrLifecycleScenarioTest.JOHN;
    static final String SALES = HrLifecycleScenarioTest.SALES;
    static final String IT = HrLifecycleScenarioTest.IT;

    @Test
    void joinerMoverLeaverOnMirrors(MidPointConfigTester t) {
        // no initial data on a mirror: the groups the roles refer to are added by the test
        t.target(Resources.LDAP).system().addGroup(SALES, Map.of("cn", "sales"));
        t.target(Resources.LDAP).system().addGroup(IT, Map.of("cn", "it"));

        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
                .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales")
                .set(HrAttrs.START_DATE, LocalDate.of(2020, 4, 1).toString()));
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").hasFullName("John Doe").hasRole(Roles.DEPT_SALES).hasAccountOn(Resources.LDAP);
        t.target(Resources.LDAP).account(JOHN).exists()
                .hasAttribute(LdapAttrs.CN, "John Doe")
                .hasAttribute(LdapAttrs.OU, "Sales")
                .memberOf(SALES).notMemberOf(IT).isEnabled();
        assertThat(t.target(Resources.LDAP).accounts()).containsExactly(JOHN);

        t.source(Resources.HR).modify("1001", HrAttrs.DEPARTMENT, "IT");
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").hasRole(Roles.DEPT_IT).hasNoRole(Roles.DEPT_SALES);
        t.target(Resources.LDAP).account(JOHN).hasAttribute(LdapAttrs.OU, "IT").memberOf(IT).notMemberOf(SALES);

        t.source(Resources.HR).delete("1001");
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").doesNotExist();
        t.target(Resources.LDAP).account(JOHN).doesNotExist();
        // referential integrity: the deleted account is no member any more
        assertThat(t.target(Resources.LDAP).system().groupValues(IT, "member")).isEmpty();
    }

    @Test
    void everyTestStartsFromTheDeploymentAsLoaded(MidPointConfigTester t) {
        assertThat(t.source(Resources.HR).accounts()).isEmpty();
        assertThat(t.target(Resources.LDAP).accounts()).isEmpty();
        t.user("e1001").doesNotExist();
    }
}
