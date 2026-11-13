package com.github.wadahiro.midpoint.configtester;

/** {@link ProjectGroupScenarioTest} on the in-memory LDAP server, whose initial data has the department groups. */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class ProjectGroupLdapScenarioTest extends ProjectGroupScenarioTest {

    @Override
    void addDepartmentGroups(MidPointConfigTester t) {
    }
}
