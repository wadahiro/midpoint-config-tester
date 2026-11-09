package com.github.wadahiro.midpoint.tester.internal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.mail.MovingMessage;
import com.icegreen.greenmail.user.MessageDeliveryHandler;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.github.wadahiro.midpoint.tester.Mail;

/**
 * The in-memory SMTP server (GreenMail 2.1, Jakarta Mail) the mail transports of the system configuration are
 * redirected to. It keeps every SMTP transaction with its envelope recipients, in the order received; GreenMail
 * delivers before it answers {@code 250} to the end of {@code DATA}, so a mail is here when midPoint's send returns.
 */
final class SmtpServer {

    static final String HOST = "127.0.0.1";

    private final GreenMail greenMail;
    private final List<Received> received = new ArrayList<>();

    private static final class Received {
        final MovingMessage message;
        final List<String> recipients = new ArrayList<>();

        Received(MovingMessage message) {
            this.message = message;
        }
    }

    SmtpServer() {
        greenMail = new GreenMail(new ServerSetup(3025, HOST, ServerSetup.PROTOCOL_SMTP).dynamicPort());
        // any credentials, any recipient (a mailbox is created on delivery)
        greenMail.withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
        greenMail.start();
        UserManager users = greenMail.getUserManager();
        MessageDeliveryHandler delivery = users.getMessageDeliveryHandler();
        users.setMessageDeliveryHandler((message, address) -> {
            record(message, address.getEmail());
            return delivery.handle(message, address);
        });
    }

    int port() {
        return greenMail.getSmtp().getPort();
    }

    private void record(MovingMessage message, String recipient) {
        synchronized (received) {
            Received last = received.isEmpty() ? null : received.get(received.size() - 1);
            if (last == null || last.message != message) {
                last = new Received(message);
                received.add(last);
            }
            last.recipients.add(recipient);
        }
    }

    List<Mail> mails() {
        List<Received> copy;
        synchronized (received) {
            copy = new ArrayList<>(received);
        }
        List<Mail> out = new ArrayList<>();
        for (Received r : copy) {
            out.add(toMail(r.message.getMessage(), r.recipients));
        }
        return out;
    }

    void clear() {
        synchronized (received) {
            received.clear();
        }
        try {
            greenMail.purgeEmailFromAllMailboxes();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot purge the in-memory mailboxes", e);
        }
    }

    private static Mail toMail(MimeMessage message, List<String> recipients) {
        try {
            Mail.Builder mail = Mail.builder()
                    .from(first(message.getFrom()))
                    .to(addresses(message.getRecipients(RecipientType.TO)))
                    .cc(addresses(message.getRecipients(RecipientType.CC)))
                    .recipients(recipients)
                    .subject(message.getSubject())
                    .contentType(message.getContentType());
            for (Enumeration<Header> e = message.getAllHeaders(); e.hasMoreElements(); ) {
                Header header = e.nextElement();
                mail.header(header.getName(), header.getValue());
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            message.writeTo(raw);
            mail.raw(raw.toString(StandardCharsets.UTF_8));
            walk(message, mail);
            return mail.build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the received mail", e);
        }
    }

    /** The first text part that is no attachment is the body, every other leaf part an attachment. */
    private static void walk(Part part, Mail.Builder mail) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                walk(multipart.getBodyPart(i), mail);
            }
            return;
        }
        boolean attached = part.getFileName() != null || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
        if (!mail.hasBody() && !attached && part.isMimeType("text/*")) {
            mail.body(String.valueOf(part.getContent()), part.getContentType());
            return;
        }
        String[] contentId = part.getHeader("Content-ID");
        try (InputStream in = part.getInputStream()) {
            mail.attachment(new Mail.Attachment(part.getFileName(), part.getContentType(),
                    contentId != null && contentId.length > 0 ? contentId[0] : null, in.readAllBytes()));
        }
    }

    private static String first(Address[] addresses) {
        List<String> list = addresses(addresses);
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<String> addresses(Address[] addresses) {
        List<String> out = new ArrayList<>();
        if (addresses != null) {
            for (Address address : addresses) {
                out.add(address instanceof InternetAddress ? ((InternetAddress) address).getAddress()
                        : address.toString());
            }
        }
        return out;
    }
}
