package com.example.idm;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.idm.names.HrAttrs;
import com.example.idm.names.Resources;
import com.example.idm.names.Roles;
import com.example.idm.names.Tasks;
import com.github.wadahiro.midpoint.configtester.MidPointScenarios;
import com.github.wadahiro.midpoint.configtester.Scenario;

/** An employee hired today who starts in ten days: nothing is provisioned before the start date. */
@MidPointScenarios
class FutureJoinerTest {

    @Test
    void theAccountComesOnTheStartDate(Scenario s) {
        LocalDate start = s.time().today().plusDays(10);
        s.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1003").set(HrAttrs.FIRST_NAME, "Kate")
                .set(HrAttrs.LAST_NAME, "Smith").set(HrAttrs.DEPARTMENT, "IT").set(HrAttrs.START_DATE, start.toString()));
        s.task(Tasks.RECONCILE_HR).run();
        s.user("e1003").isDisabled().hasNoRole(Roles.DEPT_IT).hasNoAccountOn(Resources.LDAP);

        // the clock moves to the start date; the validity and trigger scanners and the due tasks run
        s.time().advanceTo(start).advance("PT1H").runDue();
        s.user("e1003").isEnabled().hasRole(Roles.DEPT_IT).hasAccountOn(Resources.LDAP);
    }
}
