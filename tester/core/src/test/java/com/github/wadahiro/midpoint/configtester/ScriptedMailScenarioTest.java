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
        t.midpoint().addObjectXml("<user xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                + "<name>jdoe</name><emailAddress>jdoe@example.com</emailAddress></user>");

        t.mails().to("jdoe@example.com").withSubject("Welcome jdoe").exactly(1);
    }
}
