package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialUsers;
import com.github.wadahiro.midpoint.configtester.sample.access.Reports;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/** A report of the sample deployment {@code scenarios/access}: users by cost center with their roles (4.4+). */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class ReportScenarioTest {

    @Test
    void usersOfACostCenterWithTheirRoles(MidPointConfigTester t) {
        // the overlay of 4.0 removes the report (4.0 has no object collection reports): its generated name says so
        assumeTrue(Reports.USERS_BY_COST_CENTER.existsOn(t.line()), "midPoint 4.0 has no object collection reports");
        t.user(Users.JACK).assign(Roles.AUDITOR);

        ReportOutput it = t.report(Reports.USERS_BY_COST_CENTER).run(Map.of("costCenter", "IT"));

        assertThat(it.columns()).containsExactly("User", "Full name", "Cost center", "Roles");
        it.hasRows(2).hasRowWith("User", Users.JACK.name()).hasRowWith("User", Users.ROLE_OWNER.name())
                .hasNoRowWith("User", Users.ANN.name());
        assertThat(it.rows()).filteredOn(row -> "jack".equals(row.get("User")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("Full name")).isEqualTo("Jack Sparrow");
                    assertThat(row.get("Roles")).isEqualTo("Auditor, End user");
                });
    }

    @Test
    void withoutTheParameterEveryoneWithACostCenter(MidPointConfigTester t) {
        // the overlay of 4.0 removes the report (4.0 has no object collection reports): its generated name says so
        assumeTrue(Reports.USERS_BY_COST_CENTER.existsOn(t.line()), "midPoint 4.0 has no object collection reports");

        t.report(Reports.USERS_BY_COST_CENTER).run().hasRows(4).hasRowWith("User", Users.SEC_OFFICER.name())
                .hasNoRowWith("User", InitialUsers.ADMINISTRATOR.name());
    }
}
