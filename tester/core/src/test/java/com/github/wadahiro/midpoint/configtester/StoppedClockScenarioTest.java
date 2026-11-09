package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * {@code s.time().stopAt}: the clock says one instant, to the millisecond, however long the test takes, so a script's
 * time window can be checked at its bounds ({@code LocalTime.now()} against 22:00). {@code advance} moves the
 * stopped clock; the reset after the test lets it run again.
 */
@MidPointScenarios("classpath:scenarios/hr/midpoint-config-tester.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoppedClockScenarioTest {

    @Test
    @Order(1)
    void aStoppedClockSaysOneInstant(Scenario s) throws InterruptedException {
        LocalDateTime justBefore = LocalDateTime.now().plusDays(1).with(LocalTime.of(21, 59, 59, 999_000_000));
        Instant stopped = justBefore.atZone(ZoneId.systemDefault()).toInstant();

        s.time().stopAt(justBefore);
        Thread.sleep(20);

        assertThat(s.time().now()).isEqualTo(stopped);
        assertThat(LocalTime.now()).isEqualTo(LocalTime.of(21, 59, 59, 999_000_000));
        assertThat(Instant.now()).isEqualTo(stopped);
        assertThat(new Date().getTime()).isEqualTo(stopped.toEpochMilli());
        assertThat(Calendar.getInstance().getTimeInMillis()).isEqualTo(stopped.toEpochMilli());
        assertThat(script(s, "java.time.LocalTime.now().toString()")).isEqualTo("21:59:59.999");
        assertThat(script(s, "String.valueOf(System.currentTimeMillis())")).isEqualTo(String.valueOf(stopped.toEpochMilli()));
        assertThat(script(s, "String.valueOf(basic.currentDateTime().toGregorianCalendar().timeInMillis)"))
                .isEqualTo(String.valueOf(stopped.toEpochMilli()));

        s.time().advance(Duration.ofMillis(1));
        Thread.sleep(20);

        assertThat(LocalTime.now()).isEqualTo(LocalTime.of(22, 0));
        assertThat(script(s, "java.time.LocalTime.now().toString()")).isEqualTo("22:00");
        s.time().advance("PT1H");
        assertThat(LocalTime.now()).isEqualTo(LocalTime.of(23, 0));
    }

    @Test
    @Order(2)
    void theResetLetsTheClockRunAgain(Scenario s) throws InterruptedException {
        Instant first = Instant.now();
        Thread.sleep(20);
        assertThat(Instant.now()).isAfter(first);
        assertThat(Instant.now().toEpochMilli()).isCloseTo(System.currentTimeMillis(),
                org.assertj.core.data.Offset.offset(60_000L));
    }

    private static String script(Scenario s, String code) {
        return (String) s.midpoint().script(code).evaluate().value().get(0);
    }
}
