package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.task.api.LightweightIdentifier;
import com.evolveum.midpoint.task.api.LightweightIdentifierGenerator;

/**
 * midPoint's lightweight identifiers (the ids of tasks and operations) read the real wall clock: a step back of the
 * host's clock (an NTP adjustment) of up to 10 seconds is allowed on every line (4.4+ by midPoint, 4.0 by the tester),
 * a longer one fails as midPoint fails it. The step back is made by setting the generator's last timestamp ahead.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class IdentifierClockScenarioTest {

    @Test
    void aShortStepBackOfTheHostClockIsAllowed(MidPointConfigTester t) throws Exception {
        LightweightIdentifierGenerator generator = t.midpoint().bean(LightweightIdentifierGenerator.class);
        Field last = lastTimestamp(generator);
        long saved = last.getLong(generator);
        try {
            last.setLong(generator, System.currentTimeMillis() + 5_000);
            LightweightIdentifier id = generator.generate();
            assertThat(id).isNotNull();

            last.setLong(generator, System.currentTimeMillis() + 30_000);
            assertThatThrownBy(generator::generate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("moved back");
        } finally {
            last.setLong(generator, Math.min(saved, System.currentTimeMillis()));
        }
    }

    private static Field lastTimestamp(LightweightIdentifierGenerator generator) throws NoSuchFieldException {
        Field field = generator.getClass().getDeclaredField("lastTimestamp");
        field.setAccessible(true);
        return field;
    }
}
