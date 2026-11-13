package com.github.wadahiro.midpoint.configtester;

import java.util.Map;

import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;

/** {@link ProjectGroupScenarioTest} on the mirror connector, which has no initial data: the test adds the groups. */
@MidPointConfigTest("classpath:scenarios/hr-mirror/midpoint-config-tester.yaml")
class ProjectGroupMirrorScenarioTest extends ProjectGroupScenarioTest {

    @Override
    void addDepartmentGroups(MidPointConfigTester t) {
        t.target(Resources.LDAP).system().addGroup(HrLifecycleScenarioTest.SALES, Map.of("cn", "sales"));
        t.target(Resources.LDAP).system().addGroup(HrLifecycleScenarioTest.IT, Map.of("cn", "it"));
    }
}
