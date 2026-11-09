package com.github.wadahiro.midpoint.configtester;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tester's SMTP server, where midPoint's mail transports are redirected ({@link MidPointConfigTester#mailServer()}): a test
 * makes it refuse mails, for what the configuration does when a mail cannot be sent. A refused mail fails at the
 * client as a real server's error would ({@code 451} to its data) and is not in {@link MidPointConfigTester#mails()}. The server
 * takes every mail again in the next test.
 *
 * <pre>{@code
 * t.mailServer().reject();                        // every mail from now on
 * t.mailServer().reject("jdoe@example.com");      // the mails to these recipients
 * }</pre>
 */
public final class MailServer {

    private final MidPointConfigTester tester;

    MailServer(MidPointConfigTester tester) {
        this.tester = tester;
    }

    /** Refuses every mail from now on in this test. */
    public MailServer reject() {
        tester.deployment().rejectMails(recipient -> true);
        return this;
    }

    /** Refuses the mails with one of these recipients (ignoring case) from now on in this test. */
    public MailServer reject(String... recipients) {
        Set<String> refused = Arrays.stream(recipients).map(r -> r.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        tester.deployment()
                .rejectMails(recipient -> refused.contains(recipient.toLowerCase(Locale.ROOT)));
        return this;
    }

    /** Takes every mail again. */
    public MailServer accept() {
        tester.deployment().rejectMails(null);
        return this;
    }
}
