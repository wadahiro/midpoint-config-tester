package com.github.wadahiro.midpoint.configtester;

import static com.github.wadahiro.midpoint.configtester.HrLifecycleScenarioTest.JOHN;
import static com.github.wadahiro.midpoint.configtester.HrLifecycleScenarioTest.SALES;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;

/**
 * midPoint provisions a group: a project org created in midPoint (with the Project metarole of the HR sample,
 * {@code scenarios/hr}) gets its own LDAP group, and a user assigned the project becomes a member of the group, on
 * the in-memory LDAP server ({@link ProjectGroupLdapScenarioTest}) and on the mirror connector
 * ({@link ProjectGroupMirrorScenarioTest}).
 */
abstract class ProjectGroupScenarioTest {

    static final String APOLLO = "cn=apollo,ou=groups,dc=example,dc=com";

    /** The groups the department roles refer to, where the target system does not have them already. */
    abstract void addDepartmentGroups(MidPointConfigTester t);

    @Test
    void aProjectGetsItsGroupAndItsMembersJoinIt(MidPointConfigTester t) {
        addDepartmentGroups(t);
        new HrLifecycleScenarioTest.Hr(t).hire("1001", "John", "Doe", "Sales", LocalDate.of(2020, 4, 1));

        Effects created = t.org("apollo").add(o -> o.assign("Project"));
        created.connectorCalls().to("LDAP").adds().of(APOLLO).single();
        t.org("apollo").hasEntitlementOn(Resources.LDAP).hasNoAccountOn(Resources.LDAP);
        t.org("apollo").entitlement(Resources.LDAP).shadow().hasKind("entitlement").hasIntent("group");
        t.org("apollo").entitlement(Resources.LDAP).onTarget().hasAttribute("cn", "apollo");
        t.org("apollo").recompute().none();

        Effects joined = t.user("e1001").assign("apollo");
        joined.connectorCalls().to("LDAP").modifies().of(APOLLO).single().only("member");
        t.target(Resources.LDAP).group(APOLLO).hasMember(JOHN);
        t.org("apollo").entitlement(Resources.LDAP).onTarget().hasMember(JOHN);
        t.org("apollo").hasMember("e1001");
        t.user("e1001").account(Resources.LDAP).onTarget().memberOf(APOLLO).memberOf(SALES);

        t.user("e1001").unassign("apollo");
        t.target(Resources.LDAP).group(APOLLO).hasNoMember(JOHN);
        t.user("e1001").account(Resources.LDAP).onTarget().notMemberOf(APOLLO).memberOf(SALES);
        t.org("apollo").hasNoMember("e1001").hasEntitlementOn(Resources.LDAP);
    }
}
