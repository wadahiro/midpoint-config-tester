package com.github.wadahiro.midpoint.tester;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opentest4j.AssertionFailedError;

/**
 * A mail midPoint's mail transport delivered by SMTP to the tester's in-memory mail server: the message as it went
 * over the wire, parsed back (addresses, decoded subject, body, attachments, headers, raw MIME).
 *
 * The mail servers of the system configuration are redirected to the in-memory server on import (see the README,
 * "Mail notifications"); midPoint builds and sends the message with its own mail transport and JavaMail / Jakarta
 * Mail. One SMTP transaction is one {@code Mail}, whatever the number of recipients.
 */
public final class Mail {

    private final String from;
    private final List<String> to;
    private final List<String> cc;
    private final List<String> bcc;
    private final List<String> recipients;
    private final String subject;
    private final String contentType;
    private final String body;
    private final String bodyContentType;
    private final List<Attachment> attachments;
    private final Map<String, List<String>> headers;
    private final String raw;

    private Mail(Builder b) {
        this.from = b.from;
        this.to = List.copyOf(b.to);
        this.cc = List.copyOf(b.cc);
        this.recipients = List.copyOf(b.recipients);
        List<String> blind = new ArrayList<>();
        for (String recipient : b.recipients) {
            if (!containsIgnoreCase(to, recipient) && !containsIgnoreCase(cc, recipient)) {
                blind.add(recipient);
            }
        }
        this.bcc = List.copyOf(blind);
        this.subject = b.subject;
        this.contentType = b.contentType;
        this.body = b.body;
        this.bodyContentType = b.bodyContentType;
        this.attachments = List.copyOf(b.attachments);
        Map<String, List<String>> h = new LinkedHashMap<>();
        b.headers.forEach((k, v) -> h.put(k, List.copyOf(v)));
        this.headers = Collections.unmodifiableMap(h);
        this.raw = b.raw;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The {@code From} address (midPoint: the notifier's {@code fromExpression}, else the transport's
     * {@code defaultFrom}).
     */
    public String from() {
        return from;
    }

    /** The {@code To} header addresses. */
    public List<String> to() {
        return to;
    }

    /** The {@code Cc} header addresses. */
    public List<String> cc() {
        return cc;
    }

    /**
     * The blind copies: the SMTP envelope recipients that are in neither {@code To} nor {@code Cc} (JavaMail sends no
     * {@code Bcc} header).
     */
    public List<String> bcc() {
        return bcc;
    }

    /** The SMTP envelope recipients ({@code RCPT TO}), in the order the server received them. */
    public List<String> recipients() {
        return recipients;
    }

    /** The subject, decoded (e.g. from {@code =?UTF-8?B?...?=}). */
    public String subject() {
        return subject;
    }

    /** The {@code Content-Type} of the message (midPoint: {@code multipart/mixed; boundary=...}). */
    public String contentType() {
        return contentType;
    }

    /** The text of the body: the first text part that is not an attachment, decoded; null if none. */
    public String body() {
        return body;
    }

    /** Fails unless the body contains each of the texts; gives the body. */
    public Mail hasBodyContaining(String... texts) {
        for (String text : texts) {
            if (body == null || !body.contains(text)) {
                throw new AssertionError("Expected the body of " + this + " to contain '" + text + "', body:\n" + body);
            }
        }
        return this;
    }

    /**
     * What midPoint 4.8+ appends to every notification body without an active subscription (English, the tester's
     * locale), after a single line break.
     */
    public static final String SUBSCRIPTION_FOOTER =
            "No active subscription. Please support midPoint by purchasing a subscription.";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)(?::(.*?))?}}");
    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "any", "[^\\n]*",
            "oid", "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
            "date", "\\d{4}-\\d{2}-\\d{2}(T[0-9:.]+(Z|[+-]\\d{2}:\\d{2})?)?",
            "duration", "-?P[0-9YMWDTHS.]+",
            "url", "https?://\\S+");

    /**
     * The body as the same text on every line: line breaks as {@code \n}, no trailing whitespace on a line, no
     * trailing blank lines, and the 4.8+ {@link #SUBSCRIPTION_FOOTER} (a last line of exactly that text) removed.
     */
    public static String normalizeBody(String body) {
        if (body == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            lines.add(line.stripTrailing());
        }
        trimBlankEnd(lines);
        if (!lines.isEmpty() && lines.get(lines.size() - 1).trim().equals(SUBSCRIPTION_FOOTER)) {
            lines.remove(lines.size() - 1);
            trimBlankEnd(lines);
        }
        return String.join("\n", lines);
    }

    private static void trimBlankEnd(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    /** The body normalized ({@link #normalizeBody}): to compare whole bodies on every line. */
    public String normalizedBody() {
        return normalizeBody(body);
    }

    /**
     * Fails unless the normalized body is the expected text (normalized the same way). A line of the expected text
     * may hold placeholders for what changes from run to run: {@code {{any}}}, {@code {{oid}}}, {@code {{date}}},
     * {@code {{duration}}}, {@code {{url}}} and {@code {{regex:...}}}; each matches within its line. The failure
     * carries the expected and the actual body for an IDE's diff (placeholder lines that matched shown as actual).
     */
    public Mail hasBody(String expected) {
        String actual = normalizedBody();
        String wanted = normalizeBody(expected);
        if (actual == null) {
            throw new AssertionError("Expected the body of " + this + " to be:\n" + wanted + "\nbut it has none");
        }
        if (!wanted.contains("{{")) {
            if (!wanted.equals(actual)) {
                throw new AssertionFailedError("Body of " + this + " differs at line " + firstDifference(wanted, actual)
                        + ":\n" + actual, wanted, actual);
            }
            return this;
        }
        String[] want = wanted.split("\n", -1);
        String[] got = actual.split("\n", -1);
        StringBuilder shown = new StringBuilder();
        int differing = 0;
        for (int i = 0; i < want.length; i++) {
            boolean matches = i < got.length && lineRegex(want[i]).matcher(got[i]).matches();
            if (!matches && differing == 0) {
                differing = i + 1;
            }
            shown.append(i > 0 ? "\n" : "").append(matches ? got[i] : want[i]);
        }
        if (differing == 0 && got.length != want.length) {
            differing = want.length + 1;
        }
        if (differing != 0) {
            throw new AssertionFailedError("Body of " + this + " differs at line " + differing + ":\n" + actual,
                    shown.toString(), actual);
        }
        return this;
    }

    private static Pattern lineRegex(String line) {
        StringBuilder regex = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(line);
        int at = 0;
        while (m.find()) {
            regex.append(Pattern.quote(line.substring(at, m.start())));
            String kind = m.group(1);
            String pattern = "regex".equals(kind) ? m.group(2) : PLACEHOLDERS.get(kind);
            if (pattern == null) {
                throw new IllegalArgumentException("Unknown placeholder {{" + kind + "}} in: " + line);
            }
            regex.append("(?:").append(pattern).append(")");
            at = m.end();
        }
        return Pattern.compile(regex.append(Pattern.quote(line.substring(at))).toString());
    }

    private static int firstDifference(String a, String b) {
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) {
                return i + 1;
            }
        }
        return Math.min(x.length, y.length) + 1;
    }

    /** The {@code Content-Type} of the body part, e.g. {@code text/html; charset=UTF-8}. */
    public String bodyContentType() {
        return bodyContentType;
    }

    /** Whether the body part is {@code text/html}. */
    public boolean isHtml() {
        return bodyContentType != null && bodyContentType.toLowerCase().startsWith("text/html");
    }

    /** The other parts, in MIME order. */
    public List<Attachment> attachments() {
        return attachments;
    }

    /** The headers of the message as received (including the server's {@code Received} and {@code Return-Path}). */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** The first value of the header (name case-insensitive), or null. */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /** The message as received, MIME text. */
    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "mail from " + from + " to " + recipients + ": " + subject;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        return list.stream().anyMatch(v -> v.equalsIgnoreCase(value));
    }

    /** One non-body part of a {@link Mail}. */
    public static final class Attachment {

        private final String fileName;
        private final String contentType;
        private final String contentId;
        private final byte[] content;

        public Attachment(String fileName, String contentType, String contentId, byte[] content) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.contentId = contentId;
            this.content = content != null ? content.clone() : new byte[0];
        }

        /** The file name (midPoint adds an extension for the content type when the name has none). */
        public String fileName() {
            return fileName;
        }

        /** The part's {@code Content-Type} header, e.g. {@code text/plain; charset=us-ascii; name=report.txt}. */
        public String contentType() {
            return contentType;
        }

        /** The {@code Content-ID} header, or null. */
        public String contentId() {
            return contentId;
        }

        /** The decoded content. */
        public byte[] content() {
            return content.clone();
        }

        /** The decoded content as UTF-8 text. */
        public String text() {
            return new String(content, StandardCharsets.UTF_8);
        }

        public int size() {
            return content.length;
        }

        @Override
        public String toString() {
            return fileName + " (" + contentType + ", " + content.length + " bytes)";
        }
    }

    /** Used by the line modules' SMTP servers. */
    public static final class Builder {

        private String from;
        private final List<String> to = new ArrayList<>();
        private final List<String> cc = new ArrayList<>();
        private final List<String> recipients = new ArrayList<>();
        private String subject;
        private String contentType;
        private String body;
        private String bodyContentType;
        private final List<Attachment> attachments = new ArrayList<>();
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private String raw;

        private Builder() {
        }

        public Builder from(String from) {
            this.from = from;
            return this;
        }

        public Builder to(List<String> addresses) {
            to.addAll(addresses);
            return this;
        }

        public Builder cc(List<String> addresses) {
            cc.addAll(addresses);
            return this;
        }

        public Builder recipients(List<String> addresses) {
            recipients.addAll(addresses);
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder body(String body, String contentType) {
            this.body = body;
            this.bodyContentType = contentType;
            return this;
        }

        public boolean hasBody() {
            return bodyContentType != null;
        }

        public Builder attachment(Attachment attachment) {
            attachments.add(attachment);
            return this;
        }

        public Builder header(String name, String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder raw(String raw) {
            this.raw = raw;
            return this;
        }

        public Mail build() {
            return new Mail(this);
        }
    }
}
