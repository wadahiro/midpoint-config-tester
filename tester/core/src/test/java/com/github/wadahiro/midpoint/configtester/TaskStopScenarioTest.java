package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.hr.HrAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;

/**
 * {@code t.task(name).stopAfter(n).run()}: the run stops after n items, as an administrator's Suspend stops it in the
 * middle (the reconciliation checks between accounts whether it may go on); the next run does the rest.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class TaskStopScenarioTest {

    @Test
    void aRunStoppedAfterOneAccount(MidPointConfigTester t) {
        for (String id : new String[] { "1001", "1002", "1003" }) {
            t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, id).set(HrAttrs.FIRST_NAME, "F" + id)
                    .set(HrAttrs.LAST_NAME, "L" + id).set(HrAttrs.DEPARTMENT, "Sales"));
        }

        t.task(Tasks.RECONCILE_HR).stopAfter(1).run();
        assertThat(users(t)).containsExactly("e1001");

        t.task(Tasks.RECONCILE_HR).run();
        assertThat(users(t)).contains("e1001", "e1002", "e1003");
    }

    private static java.util.List<String> users(MidPointConfigTester t) {
        return t.midpoint().actor("administrator").search(UserType.class, null).value().stream()
                .map(u -> u.getName().getOrig()).filter(n -> n.startsWith("e1")).sorted()
                .collect(java.util.stream.Collectors.toList());
    }
}
