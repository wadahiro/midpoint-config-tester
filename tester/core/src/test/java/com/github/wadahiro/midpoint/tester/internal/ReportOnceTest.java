package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** A warning of a resource's import is printed once per JVM ({@link MidPointImpl#reportOnce}). */
class ReportOnceTest {

    @Test
    void aWarningIsReportedTheFirstTimeOnly() {
        // a warning of this run only: a rerun in the same JVM reports its own once too
        String warning = "resource R: a warning " + UUID.randomUUID();

        assertThat(MidPointImpl.reportOnce(warning)).isTrue();
        assertThat(MidPointImpl.reportOnce(warning)).isFalse();
        assertThat(MidPointImpl.reportOnce(warning + " of another resource")).isTrue();
    }
}
