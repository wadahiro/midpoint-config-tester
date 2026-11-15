package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivationStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.hr.HrAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;

/**
 * A script's local date is read in the time zone of the midPoint server ({@code tester.timeZone}, here Asia/Tokyo),
 * whatever the zone of the machine that runs the test: HR's start date (yyyy-MM-dd, {@code basic.parseDateTime}) is
 * midnight in Tokyo, so 05:00 there (20:00 UTC the day before) is on it. A JVM in UTC read it as midnight UTC, 09:00
 * in Tokyo: the joiner still disabled, without an account.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class TimeZoneScenarioTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    @Test
    void aStartDateBeginsAtMidnightOnTheServer(MidPointConfigTester t) {
        assertThat(t.clock().zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        t.clock().stopAt(Instant.parse("2027-03-01T00:00:00Z"));
        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
                .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales").set(HrAttrs.START_DATE, "2027-03-04"));
        t.task(Tasks.RECONCILE_HR).run();

        t.clock().stopAt(Instant.parse("2027-03-03T20:00:00Z"));
        t.clock().runDue();

        UserType user = t.midpoint().user("e1001");
        assertThat(user.getActivation().getValidFrom().toGregorianCalendar().toZonedDateTime().toInstant())
                .isEqualTo(Instant.parse("2027-03-03T15:00:00Z"));
        assertThat(user.getActivation().getEffectiveStatus()).isEqualTo(ActivationStatusType.ENABLED);
        assertThat(t.midpoint().target("LDAP").accountNames()).contains(JOHN);
        assertThat(t.clock().today()).isEqualTo(LocalDate.of(2027, 3, 4));
    }
}
