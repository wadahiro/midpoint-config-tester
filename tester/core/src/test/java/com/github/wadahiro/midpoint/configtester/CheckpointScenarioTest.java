package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;
import com.github.wadahiro.midpoint.tester.internal.Checkpoint;

/**
 * A checkpoint in the middle of a test (what the Kotest layer's containers share): back to it, midPoint has what it
 * had then, the clock where it was (also back in time), and the test sees the mails of then, not the later ones.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class CheckpointScenarioTest {

    static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    @Test
    void backToTheCheckpoint(MidPointConfigTester t) {
        t.midpoint().addObjectXml("<user xmlns='" + NS + "'><name>before</name></user>");
        Instant stopped = t.clock().now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        t.clock().stopAt(stopped);
        t.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
        int mails = t.mails().count();
        assertThat(mails).isPositive();
        Checkpoint checkpoint = t.deployment().checkpoint();
        try {
            t.midpoint().addObjectXml("<user xmlns='" + NS + "'><name>after</name></user>");
            t.clock().advance("P30D");
            t.workItems().assignedTo(Users.ROLE_OWNER).single().approve(t.as(Users.ROLE_OWNER), "fine by me");
            assertThat(t.mails().count()).isGreaterThan(mails);

            t.deployment().restore(checkpoint);

            assertThat(t.midpoint().as("administrator").search(UserType.class, null).value())
                    .extracting(u -> u.getName().getOrig()).contains("before").doesNotContain("after");
            assertThat(t.clock().now()).isEqualTo(stopped);
            assertThat(t.mails().count()).isEqualTo(mails);
            // the work item of then is open again
            t.workItems().assignedTo(Users.ROLE_OWNER).single();

            // and the checkpoint serves again
            t.midpoint().addObjectXml("<user xmlns='" + NS + "'><name>again</name></user>");
            t.deployment().restore(checkpoint);
            assertThat(t.midpoint().as("administrator").search(UserType.class, null).value())
                    .extracting(u -> u.getName().getOrig()).doesNotContain("again");
        } finally {
            t.deployment().release(checkpoint);
        }
    }
}
