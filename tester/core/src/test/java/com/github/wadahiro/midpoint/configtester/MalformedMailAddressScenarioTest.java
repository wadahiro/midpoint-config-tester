package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.Mail;

/**
 * midPoint sends a welcome mail ({@code scenarios/scripted-mail}) to an address the RFCs do not allow (two dots in a
 * row in the local part). midPoint 4.10+ sends it when {@code mail.mime.address.strict} is {@code false} (its mail
 * transport reads the addresses with that property of the JVM), 4.0 always (javax.mail 1.4.5 takes the dots); the
 * tester's server lists it, read leniently, and the runs after it are not affected. 4.4, 4.8 and 4.9 refuse the
 * address before sending (their transport parses it strictly, whatever the property): no mail.
 */
@MidPointConfigTest("classpath:scenarios/scripted-mail/midpoint-config-tester.yaml")
class MalformedMailAddressScenarioTest {

    /** The lines whose midPoint sends the address; see the class comment. */
    private static final Set<String> SENDING = Set.of("4.0", "4.10", "4.11");

    private String strictBefore;

    @BeforeEach
    void lenientSender() {
        strictBefore = System.getProperty("mail.mime.address.strict");
        System.setProperty("mail.mime.address.strict", "false");
    }

    @AfterEach
    void strictSenderAgain() {
        if (strictBefore == null) {
            System.clearProperty("mail.mime.address.strict");
        } else {
            System.setProperty("mail.mime.address.strict", strictBefore);
        }
    }

    @Test
    void aMailToAnAddressWithTwoDotsInARowIsListed(MidPointConfigTester t) {
        Effects added = t.user("jdoe").add(u -> u.emailAddress("john..doe@example.com"));

        if (SENDING.contains(t.line())) {
            Mail mail = added.mails().to("john..doe@example.com").withSubject("Welcome jdoe").single();
            assertThat(mail.to()).containsExactly("john..doe@example.com");
            assertThat(mail.body()).isNotNull();
            if (!"4.0".equals(t.line())) {
                assertThat(mail.unreadable()).singleElement().asString().startsWith("To: john..doe@example.com: ")
                        .endsWith("(read leniently)");
            }
        } else {
            t.mails().to("john..doe@example.com").none();
        }

        // the runs after it read every mail again: a well-formed one is listed with it
        t.user("ann").add(u -> u.emailAddress("ann@example.com"));
        t.clock().runDue();
        t.mails().to("ann@example.com").withSubject("Welcome ann").exactly(1);
        assertThat(t.midpoint().mails()).hasSize(SENDING.contains(t.line()) ? 2 : 1);
    }
}
