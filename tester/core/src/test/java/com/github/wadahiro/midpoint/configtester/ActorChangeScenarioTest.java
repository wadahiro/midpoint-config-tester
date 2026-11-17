package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;
import com.github.wadahiro.midpoint.tester.AuditRecord;

/**
 * Changes and deletions as a logged-in user ({@link Actor#modify}, {@link Actor#delete}) over the sample deployment
 * {@code scenarios/access}: the Help desk may change the telephone number of the IT department's users and nothing
 * else, and may delete no one; the administrator may. The operations of an actor run in the GUI's channel, as the
 * audit records them, unless {@link MidPointConfigTester#inChannel} sets another.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class ActorChangeScenarioTest {

    private static final String REST = "http://midpoint.evolveum.com/xml/ns/public/common/channels-3#rest";

    @Test
    void theHelpDeskChangesATelephoneNumberInTheGuiChannel(MidPointConfigTester t) {
        t.actor(Users.HELPDESK).modify(t.user(Users.JACK), m -> m.set("telephoneNumber", "+1 555 0100"));

        t.user(Users.JACK).hasItem("telephoneNumber", "+1 555 0100");
        AuditRecord change = t.audit().ofType("MODIFY_OBJECT").execution().target(Users.JACK.name()).single();
        assertThat(change.initiatorName()).isEqualTo(Users.HELPDESK.name());
        assertThat(change.channel()).isEqualTo(MidPointConfigTester.GUI_CHANNEL);
    }

    @Test
    void anItemTheActorMayNotChangeIsRefused(MidPointConfigTester t) {
        t.expectFailure(() -> t.actor(Users.HELPDESK).modify(t.user(Users.JACK), m -> m.set("costCenter", "Sales")));

        t.user(Users.JACK).hasItem("costCenter", "IT");
    }

    @Test
    void aDeletionNeedsTheAuthorization(MidPointConfigTester t) {
        t.expectFailure(() -> t.actor(Users.HELPDESK).delete(t.user(Users.ANN)));
        t.user(Users.ANN).exists();

        t.actor("administrator").delete(t.user(Users.ANN));

        t.user(Users.ANN).doesNotExist();
        // the refused deletion is audited too (FATAL_ERROR): the administrator's is the successful one
        AuditRecord deletion = t.audit().ofType("DELETE_OBJECT").execution().target(Users.ANN.name())
                .outcome("SUCCESS").single();
        assertThat(deletion.channel()).isEqualTo(MidPointConfigTester.GUI_CHANNEL);
    }

    @Test
    void anotherChannelSetForTheBlockIsKept(MidPointConfigTester t) {
        t.inChannel(REST, () -> t.actor(Users.HELPDESK)
                .modify(t.user(Users.JACK), m -> m.set("telephoneNumber", "+1 555 0101")));

        assertThat(t.audit().ofType("MODIFY_OBJECT").execution().target(Users.JACK.name()).single().channel())
                .isEqualTo(REST);
    }

    @Test
    void aRequestRunsInTheGuiChannel(MidPointConfigTester t) {
        t.actor(Users.JACK).request().assign(Roles.AUDITOR);

        assertThat(t.audit().ofType("MODIFY_OBJECT").execution().target(Users.JACK.name()).single().channel())
                .isEqualTo(MidPointConfigTester.GUI_CHANNEL);
    }
}
