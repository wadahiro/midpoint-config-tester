package com.github.wadahiro.midpoint.tester.internal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.mail.Header;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.mail.MovingMessage;
import com.icegreen.greenmail.user.MessageDeliveryHandler;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.github.wadahiro.midpoint.tester.Mail;

/**
 * The in-memory SMTP server (GreenMail 1.6, javax.mail) the mail transports of the system configuration are
 * redirected to. It keeps every SMTP transaction with its envelope recipients, in the order received; GreenMail
 * delivers before it answers {@code 250} to the end of {@code DATA}, so a mail is here when midPoint's send returns.
 * A mail is read part by part: what the RFCs do not allow (an address with two dots in a row) is read leniently or
 * kept as written and noted in {@link Mail#unreadable()}, so one such mail never fails the reading of the others.
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
            java.util.function.Predicate<String> rejecting = rejected;
            if (rejecting != null && rejecting.test(address.getEmail())) {
                // GreenMail answers 451 to the data: the client's send fails
                throw new IllegalStateException("rejected by the test: " + address.getEmail());
            }
            record(message, address.getEmail());
            return delivery.handle(message, address);
        });
    }

    /** The recipients whose mails are refused (see {@code LineDrivers.rejectMails}), or null. */
    private volatile java.util.function.Predicate<String> rejected;

    void reject(java.util.function.Predicate<String> recipient) {
        rejected = recipient;
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
            out.add(toMail(r.message, r.recipients));
        }
        return out;
    }

    /** Forgets the mails after the first {@code count} (a checkpoint of the test, restored). */
    void keep(int count) {
        synchronized (received) {
            while (received.size() > count) {
                received.remove(received.size() - 1);
            }
        }
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

    /** The reading of one part of a mail, which may fail. */
    private interface Reading {
        void read() throws Exception;
    }

    private static Mail toMail(MovingMessage received, List<String> recipients) {
        Mail.Builder mail = Mail.builder().recipients(recipients);
        MimeMessage message;
        try {
            message = received.getMessage();
        } catch (RuntimeException e) {
            return mail.unreadable("message: " + e).build();
        }
        read(mail, "raw", () -> {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            message.writeTo(raw);
            mail.raw(raw.toString(StandardCharsets.UTF_8));
        });
        read(mail, "headers", () -> {
            for (Enumeration<Header> e = message.getAllHeaders(); e.hasMoreElements(); ) {
                Header header = e.nextElement();
                mail.header(header.getName(), header.getValue());
            }
        });
        read(mail, "From", () -> {
            // as getFrom(): the Sender when there is no From
            List<String> from = message.getHeader("From") != null || message.getHeader("Sender") == null
                    ? addresses(message, "From", mail) : addresses(message, "Sender", mail);
            mail.from(from.isEmpty() ? null : from.get(0));
        });
        read(mail, "To", () -> mail.to(addresses(message, "To", mail)));
        read(mail, "Cc", () -> mail.cc(addresses(message, "Cc", mail)));
        read(mail, "Subject", () -> mail.subject(message.getSubject()));
        read(mail, "Content-Type", () -> mail.contentType(message.getContentType()));
        read(mail, "body", () -> walk(message, mail));
        return mail.build();
    }

    private static void read(Mail.Builder mail, String what, Reading reading) {
        try {
            reading.read();
        } catch (Exception e) {
            mail.unreadable(what + ": " + e);
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

    /** An address of one local part and one domain, without spaces, brackets, quotes or comments. */
    private static final java.util.regex.Pattern PLAIN_ADDRESS =
            java.util.regex.Pattern.compile("[^\\s<>()\\[\\],;:\"@]+@[^\\s<>()\\[\\],;:\"@]+");

    /**
     * The addresses of the header: as the RFCs say; else, noted, as a lenient reading gives them when each is a
     * plain address ({@code john..doe@example.com}, {@code ops.@example.com}: what midPoint 4.10+ sends with
     * {@code mail.mime.address.strict=false}); else, noted, the header as written, as one entry (a lenient reading of a
     * broken header gives addresses nobody wrote).
     */
    private static List<String> addresses(MimeMessage message, String header, Mail.Builder mail) throws Exception {
        String value = message.getHeader(header, ",");
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        try {
            for (InternetAddress address : InternetAddress.parseHeader(value, true)) {
                out.add(address.getAddress());
            }
            return out;
        } catch (AddressException strict) {
            String written = MimeUtility.unfold(value).trim();
            String problem = header + ": " + written + ": " + strict.getMessage();
            try {
                for (InternetAddress address : InternetAddress.parseHeader(value, false)) {
                    if (address.getAddress() == null || !PLAIN_ADDRESS.matcher(address.getAddress()).matches()) {
                        throw new AddressException("not a plain address: " + address.getAddress());
                    }
                    out.add(address.getAddress());
                }
                mail.unreadable(problem + " (read leniently)");
            } catch (AddressException lenient) {
                out.clear();
                out.add(written);
                mail.unreadable(problem + " (kept as written)");
            }
            return out;
        }
    }
}
