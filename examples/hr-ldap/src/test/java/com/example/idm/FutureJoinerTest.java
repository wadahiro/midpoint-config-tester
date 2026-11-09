package com.example.idm;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.idm.names.HrAttrs;
import com.example.idm.names.Resources;
import com.example.idm.names.Roles;
import com.example.idm.names.Tasks;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/** An employee hired today who starts in ten days: nothing is provisioned before the start date. */
@MidPointConfigTest
class FutureJoinerTest {

    @Test
    void theAccountComesOnTheStartDate(MidPointConfigTester t) {
        LocalDate start = t.clock().today().plusDays(10);
        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1003").set(HrAttrs.FIRST_NAME, "Kate")
                .set(HrAttrs.LAST_NAME, "Smith").set(HrAttrs.DEPARTMENT, "IT").set(HrAttrs.START_DATE, start.toString()));
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1003").isDisabled().hasNoRole(Roles.DEPT_IT).hasNoAccountOn(Resources.LDAP);

        // the clock moves to the start date; the validity and trigger scanners and the due tasks run
        t.clock().advanceTo(start).advance("PT1H").runDue();
        t.user("e1003").isEnabled().hasRole(Roles.DEPT_IT).hasAccountOn(Resources.LDAP);
    }
}
