package com.github.wadahiro.midpoint.configtester;

/**
 * A deployment whose post-initial bulk action configures the mail server ({@code scenarios/scripted-mail}): the
 * tester redirects the stored configuration after the import, and midPoint's transports take it, so a mail reaches
 * the tester's server and not the configured one ({@code smtp.invalid}).
 */
@MidPointConfigTest("classpath:scenarios/scripted-mail/midpoint-config-tester.yaml")
class ScriptedMailScenarioTest {

    @org.junit.jupiter.api.Test
    void aMailGoesToTheTestersServer(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.emailAddress("jdoe@example.com"));

        t.mails().to("jdoe@example.com").withSubject("Welcome jdoe").exactly(1);
    }
}
