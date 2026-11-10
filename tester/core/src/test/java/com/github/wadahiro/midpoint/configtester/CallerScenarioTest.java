package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.github.wadahiro.midpoint.tester.Caller;

/**
 * {@code t.callAs(caller, ...)}: the driver calls of the block as a service on a user's behalf makes them: the user
 * logged in (with the user's own authorizations, or privileged), the owner of their tasks, their channel.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class CallerScenarioTest {

    static final String CHANNEL = "http://example.com/channels#portal";
    static final String WHO = "def task = midpoint.getCurrentTask()\n"
            + "[midpoint.getPrincipal().getUsername(), task.getOwner()?.getName()?.getOrig(), task.getChannel()]"
            + ".join('|')";

    @Test
    void theUserTheOwnerAndTheChannel(MidPointConfigTester t) {
        String[] seen = new String[2];

        t.callAs(Caller.user("jack").channel(CHANNEL), () -> seen[0] = who(t));
        t.callAs(Caller.user("jack").owner("administrator"), () -> seen[1] = who(t));

        assertThat(seen[0]).isEqualTo("jack|jack|" + CHANNEL);
        assertThat(seen[1]).startsWith("jack|administrator|");
        // after the block: the administrator again, in the default channel
        assertThat(who(t)).startsWith("administrator|administrator|").doesNotContain(CHANNEL);
    }

    /** A user without the authorization cannot add a role; privileged, the same user can, and is the initiator. */
    @Test
    void privilegedAddsAllAuthorizations(MidPointConfigTester t) {
        String role = "<role xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'><name>From the API</name>"
                + "</role>";

        t.callAs(Caller.user("jack"), () -> assertThat(t.expectFailure(() -> t.midpoint().addObjectXml(role))
                .isNotAuthorized()).isTrue());
        t.audit().ofType("ADD_OBJECT").execution().target("From the API").outcome("SUCCESS").none();

        t.callAs(Caller.user("jack").privileged(), () -> t.midpoint().addObjectXml(role));

        assertThat(t.midpoint().find(RoleType.class, "From the API")).isNotNull();
        assertThat(t.audit().ofType("ADD_OBJECT").execution().target("From the API").outcome("SUCCESS").single()
                .initiatorName())
                .isEqualTo("jack");
    }

    /** An add-on's class the test makes itself: midPoint's beans injected, as Spring makes a component. */
    public static class AddOnService {
        @org.springframework.beans.factory.annotation.Autowired
        com.evolveum.midpoint.security.api.SecurityContextManager security;

        String whoCalls(com.evolveum.midpoint.task.api.Task task) throws Exception {
            return security.getPrincipal().getUsername() + "|" + task.getOwnerRef().getOid() + "|" + task.getChannel();
        }
    }

    /** {@code createBean} and {@code invoke}: the test calls an add-on's code as a driver call, as the caller. */
    @Test
    void codeOfTheDeploymentAsADriverCall(MidPointConfigTester t) {
        AddOnService service = t.midpoint().createBean(AddOnService.class);
        assertThat(service.security).isNotNull();
        String[] seen = new String[1];

        t.callAs(Caller.user("jack").channel(CHANNEL),
                () -> seen[0] = t.midpoint().invoke("who calls", (task, result) -> service.whoCalls(task)).value());

        assertThat(seen[0]).isEqualTo("jack|" + t.midpoint().user("jack").getOid() + "|" + CHANNEL);
        // code that clears the login (as an API add-on's service does at its end) leaves the administrator logged in
        t.midpoint().invoke("clears the login", (task, result) -> {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(null);
            return null;
        });
        assertThat(who(t)).startsWith("administrator|");
        assertThat(t.expectFailure(() -> t.midpoint().invoke("failing", (task, result) -> {
            throw new IllegalStateException("broken add-on");
        })).message()).contains("broken add-on");
    }

    private static String who(MidPointConfigTester t) {
        return (String) t.midpoint().script(WHO).evaluate().value().get(0);
    }
}
