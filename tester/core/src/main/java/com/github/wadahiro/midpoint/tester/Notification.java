package com.github.wadahiro.midpoint.tester;

import java.util.List;

/**
 * A message midPoint's notifications sent to a {@code dummy} transport ({@code <transport>dummy:hr</transport>}, or a
 * custom transport named {@code dummy...}): what a mail or SMS would have carried.
 */
public final class Notification {

    private final String transport;
    private final List<String> to;
    private final List<String> cc;
    private final List<String> bcc;
    private final String subject;
    private final String body;
    private final String contentType;

    public Notification(String transport, List<String> to, List<String> cc, List<String> bcc, String subject,
            String body, String contentType) {
        this.transport = transport;
        this.to = to != null ? List.copyOf(to) : List.of();
        this.cc = cc != null ? List.copyOf(cc) : List.of();
        this.bcc = bcc != null ? List.copyOf(bcc) : List.of();
        this.subject = subject;
        this.body = body;
        this.contentType = contentType;
    }

    /** The transport as the notifier names it, e.g. {@code dummy:hr}. */
    public String transport() {
        return transport;
    }

    public List<String> to() {
        return to;
    }

    public List<String> cc() {
        return cc;
    }

    public List<String> bcc() {
        return bcc;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public String contentType() {
        return contentType;
    }

    @Override
    public String toString() {
        return transport + " to " + to + ": " + subject;
    }
}
