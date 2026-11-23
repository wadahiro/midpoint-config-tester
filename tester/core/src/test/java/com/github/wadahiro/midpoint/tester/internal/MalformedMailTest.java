package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.tester.Mail;

/**
 * Mails with headers the RFCs do not allow, sent to the tester's SMTP server by a plain SMTP client (as a sender
 * outside midPoint, or midPoint 4.10+ with {@code mail.mime.address.strict=false}, would): each is listed with what
 * could be read, the others and the runs after them are not affected.
 */
@MidPointConfigTest("classpath:scenarios/scripted-mail/midpoint-config-tester.yaml")
class MalformedMailTest {

    @Test
    void aMailWithAddressesTheRfcsDoNotAllowIsListedWithTheOthers(MidPointConfigTester t) throws Exception {
        int port = ((MidPointImpl) t.midpoint()).drivers().smtpPort();
        send(port, "ops@example.com", List.of("john..doe@example.com", "ann@example.com", "kim@example.com"),
                "From: Operations <ops.@example.com>\r\n"
                        + "To: john..doe@example.com, \"Ann\" <ann@example.com>\r\n"
                        + "Cc: <kim@example.com\r\n"
                        + "Subject: Malformed addresses\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "Hello\r\n");
        send(port, "ops@example.com", List.of("ann@example.com"),
                "From: ops@example.com\r\nTo: ann@example.com\r\nSubject: Well-formed\r\n\r\nHello again\r\n");

        // a run after them reads every mail (its effects): it does not fail
        t.user("jdoe").add(u -> u.emailAddress("jdoe@example.com"));

        List<Mail> mails = t.midpoint().mails();
        assertThat(mails).extracting(Mail::subject).containsExactly("Malformed addresses", "Well-formed",
                "Welcome jdoe");

        Mail malformed = mails.get(0);
        assertThat(malformed.recipients()).containsExactly("john..doe@example.com", "ann@example.com",
                "kim@example.com");
        assertThat(malformed.to()).containsExactly("john..doe@example.com", "ann@example.com");
        assertThat(malformed.from()).isEqualTo("ops.@example.com");
        // no lenient reading gives a plain address: the header as written
        assertThat(malformed.cc()).containsExactly("<kim@example.com");
        assertThat(malformed.bcc()).containsExactly("kim@example.com");
        assertThat(malformed.body()).contains("Hello");
        assertThat(malformed.unreadable()).anySatisfy(u -> assertThat(u).startsWith("Cc: <kim@example.com: ")
                .endsWith("(kept as written)"));
        if (!"4.0".equals(t.line())) {
            // the javax.mail 1.4.5 of the 4.0 line takes the dots as they are
            assertThat(malformed.unreadable()).anySatisfy(u -> assertThat(u)
                    .startsWith("To: john..doe@example.com, \"Ann\" <ann@example.com>: ").endsWith("(read leniently)"));
            assertThat(malformed.unreadable()).anySatisfy(u -> assertThat(u)
                    .startsWith("From: Operations <ops.@example.com>: ").endsWith("(read leniently)"));
        }
        assertThat(malformed.toString()).contains("unreadable: ");

        assertThat(mails.get(1).unreadable()).isEmpty();
        assertThat(mails.get(1).to()).containsExactly("ann@example.com");
        t.mails().to("john..doe@example.com").single().hasBodyContaining("Hello");
        t.mails().to("jdoe@example.com").withSubject("Welcome jdoe").exactly(1);
    }

    /** One SMTP transaction, as a plain client sends it. */
    private static void send(int port, String from, List<String> recipients, String data) throws Exception {
        try (Socket socket = new Socket(SmtpServer.HOST, port)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                    StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();
            expect(in, "220");
            command(out, in, "EHLO localhost", "250");
            command(out, in, "MAIL FROM:<" + from + ">", "250");
            for (String recipient : recipients) {
                command(out, in, "RCPT TO:<" + recipient + ">", "250");
            }
            command(out, in, "DATA", "354");
            command(out, in, data + ".", "250");
            command(out, in, "QUIT", "221");
        }
    }

    private static void command(OutputStream out, BufferedReader in, String line, String reply) throws Exception {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        expect(in, reply);
    }

    /** Reads a reply (its continuation lines too) and checks its code. */
    private static void expect(BufferedReader in, String code) throws Exception {
        String line;
        do {
            line = in.readLine();
            assertThat(line).as("SMTP reply").isNotNull().startsWith(code);
        } while (line.length() > 3 && line.charAt(3) == '-');
    }
}
