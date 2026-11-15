package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

/**
 * The time zone of the midPoint server, {@code tester.timeZone}: required, the JVM's from before midPoint starts (this
 * suite's POM gives Asia/Tokyo, not UTC, so a UTC machine exercises the conversion).
 */
class TimeZoneTest {

    @Test
    void theJvmRunsInTheTimeZoneOfTheServer() {
        assertThat(TesterRuntime.zone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Tokyo");
        assertThat(System.getProperty("user.timezone")).isEqualTo("Asia/Tokyo");
    }

    @Test
    void noneOrOneThatIsNoTimeZoneFails() {
        assertThatThrownBy(() -> TesterRuntime.timeZone(null))
                .hasMessage("tester.timeZone is not set: the time zone the midPoint server runs in; set"
                        + " <tester.timeZone>Asia/Tokyo</tester.timeZone> in the POM (the parent passes it to the test"
                        + " JVM)");
        // a POM without the property: Maven leaves the expression as it is
        assertThatThrownBy(() -> TesterRuntime.timeZone("${tester.timeZone}"))
                .hasMessage("tester.timeZone \"${tester.timeZone}\" is not a time zone (an IANA ID, e.g. Asia/Tokyo)");
        assertThat(TesterRuntime.timeZone(" Europe/Berlin ")).isEqualTo(ZoneId.of("Europe/Berlin"));
    }
}
