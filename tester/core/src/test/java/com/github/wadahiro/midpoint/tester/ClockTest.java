package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * {@code t.clock().advance} moves the JVM's wall clock with midPoint's: what the test's own code and every library
 * read through {@code Date}, {@code Calendar} and {@code java.time}, not {@code System.currentTimeMillis()} from Java
 * nor {@code System.nanoTime()}; Groovy scripts read the moved time whichever way they read it
 * ({@link #scriptsSeeTheAdvancedTime}). The reset after each test sets it back. Quartz and {@code runDueTasks}:
 * {@code TaskRunsTest.recurringTaskRunsOnItsScheduleByMidPointsClock}; the validity scanner after
 * {@code t.clock().advanceTo}: {@code HrLifecycleScenarioTest}.
 */
@MidPointConfigTest(Fixtures.BARE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClockTest {

    static final Offset<Long> MINUTE = Offset.offset(60_000L);

    @Test
    @Order(1)
    void theJvmClockMovesWithMidPoints(MidPointConfigTester t) {
        long nanos = System.nanoTime();
        t.clock().advance("P30D");

        long clock = t.clock().now().toEpochMilli();
        long real = System.currentTimeMillis();
        assertThat(clock - real).isCloseTo(Duration.ofDays(30).toMillis(), MINUTE);
        assertThat(new Date().getTime()).isCloseTo(clock, MINUTE);
        assertThat(Calendar.getInstance().getTimeInMillis()).isCloseTo(clock, MINUTE);
        assertThat(Instant.now().toEpochMilli()).isCloseTo(clock, MINUTE);
        assertThat(ZonedDateTime.now().toInstant().toEpochMilli()).isCloseTo(clock, MINUTE);
        assertThat(LocalDate.now()).isEqualTo(Instant.ofEpochMilli(clock).atZone(ZoneId.systemDefault()).toLocalDate());
        // not shifted: System.currentTimeMillis() called from Java (native), and monotonic time
        assertThat(System.currentTimeMillis()).isCloseTo(real, MINUTE);
        assertThat(System.nanoTime() - nanos).isLessThan(Duration.ofMinutes(5).toNanos());

        // waits use monotonic time: a driver call after a large advance does not time out (it fails if it does)
        t.clock().runValidityScanner();
    }

    @Test
    @Order(2)
    void theResetSetsTheJvmClockBack(MidPointConfigTester t) {
        assertThat(t.clock().now().toEpochMilli()).isCloseTo(System.currentTimeMillis(), MINUTE);
        assertThat(Instant.now().toEpochMilli()).isCloseTo(System.currentTimeMillis(), MINUTE);
        assertThat(new Date().getTime()).isCloseTo(System.currentTimeMillis(), MINUTE);
    }

    /**
     * A script reads midPoint's clock ({@code basic}) and the JVM's ({@code new Date()}, {@code System
     * .currentTimeMillis()} from Groovy, {@code java.time}): all of them see the advanced time.
     */
    @Test
    @Order(3)
    void scriptsSeeTheAdvancedTime(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        t.clock().advance("P30D");
        long clock = t.clock().now().toEpochMilli();
        assertThat(clock - System.currentTimeMillis()).isGreaterThan(Duration.ofDays(29).toMillis());

        assertThat(near(mp, "basic.currentDateTime().toGregorianCalendar().timeInMillis", clock)).isTrue();
        assertThat(near(mp, "basic.fromNow('P1D').toGregorianCalendar().timeInMillis - 86400000L", clock)).isTrue();
        assertThat(near(mp, "new Date().time", clock)).isTrue();
        assertThat(near(mp, "System.currentTimeMillis()", clock)).isTrue();
        assertThat(near(mp, "java.time.Instant.now().toEpochMilli()", clock)).isTrue();
        assertThat(near(mp, "Calendar.getInstance().timeInMillis", clock)).isTrue();
    }

    /** Whether the script's time (milliseconds) is within 5 minutes of {@code expected}. */
    private static boolean near(MidPoint mp, String expression, long expected) {
        Object out = mp.script("(Math.abs((" + expression + ") - expected) < 300000L) as String")
                .variable("expected", expected, Long.class).evaluate().value().get(0);
        return Boolean.parseBoolean((String) out);
    }
}
