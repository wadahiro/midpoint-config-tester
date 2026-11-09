package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code t.inChannel}: the driver calls of the block run in the channel (their tasks carry it, midPoint's model context
 * takes it from there), the calls after it in the default one again (midPoint's user channel, which differs by line).
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class ChannelScenarioTest {

    static final String GRPC = "http://midpoint.evolveum.com/xml/ns/public/model/channels-3#grpc";

    @Test
    void theCallsOfTheBlockRunInTheChannel(MidPointConfigTester t) {
        List<Object> before = channel(t);
        List<Object> inside = new ArrayList<>();
        t.inChannel(GRPC, () -> inside.addAll(channel(t)));

        assertThat(inside).containsExactly(GRPC);
        assertThat(before).doesNotContain(GRPC);
        assertThat(channel(t)).isEqualTo(before);
    }

    private static List<Object> channel(MidPointConfigTester t) {
        return t.midpoint().script("midpoint.currentTask.channel ?: 'none'").evaluate().value();
    }
}
