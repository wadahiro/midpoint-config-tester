package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.github.wadahiro.midpoint.tester.Mail;

/**
 * Mails midPoint sent in the test ({@link Scenario#mails()}), narrowed by recipient, sender, subject and body, e.g.
 * {@code s.mails().to("jdoe@example.com").withSubject("Welcome").exactly(1)}. Each narrowing returns a new selection.
 *
 * <p>A mail is one SMTP transaction: 4.0 and 4.4 send one mail to all recipients of a notification, 4.8+ one mail per
 * recipient. {@link #to(String)} and {@link #perRecipient()} count per recipient and so read the same on every
 * line.</p>
 */
public final class Mails {

    private final List<Mail> all;
    private final List<Mail> selected;
    private final String description;

    private Mails(List<Mail> all, List<Mail> selected, String description) {
        this.all = all;
        this.selected = selected;
        this.description = description;
    }

    static Mails of(List<Mail> mails) {
        List<Mail> copy = Collections.unmodifiableList(new ArrayList<>(mails));
        return new Mails(copy, copy, "mails");
    }

    /** The mails with this address among their recipients (To, Cc or Bcc), ignoring case. */
    public Mails to(String address) {
        List<Mail> out = new ArrayList<>();
        for (Mail mail : selected) {
            if (mail.recipients().stream().anyMatch(r -> r.equalsIgnoreCase(address))) {
                out.add(mail);
            }
        }
        return new Mails(all, out, description + " to " + address);
    }

    /** The mails from this address ({@link Mail#from()}), ignoring case. */
    public Mails from(String address) {
        List<Mail> out = new ArrayList<>();
        for (Mail mail : selected) {
            if (mail.from() != null && mail.from().equalsIgnoreCase(address)) {
                out.add(mail);
            }
        }
        return new Mails(all, out, description + " from " + address);
    }

    /** The mails whose body ({@link Mail#body()}) contains the text. */
    public Mails withBodyContaining(String text) {
        List<Mail> out = new ArrayList<>();
        for (Mail mail : selected) {
            if (mail.body() != null && mail.body().contains(text)) {
                out.add(mail);
            }
        }
        return new Mails(all, out, description + " with a body containing '" + text + "'");
    }

    /** The mails whose subject contains the text. */
    public Mails withSubject(String text) {
        List<Mail> out = new ArrayList<>();
        for (Mail mail : selected) {
            if (mail.subject() != null && mail.subject().contains(text)) {
                out.add(mail);
            }
        }
        return new Mails(all, out, description + " with a subject containing '" + text + "'");
    }

    /**
     * How many of the selected mails each address received (To, Cc or Bcc; lower case, sorted), e.g.
     * {@code assertThat(s.mails().perRecipient()).isEqualTo(Map.of("ann@example.com", 2))} for "these and nobody else".
     */
    public Map<String, Integer> perRecipient() {
        Map<String, Integer> out = new TreeMap<>();
        for (Mail mail : selected) {
            mail.recipients().stream().map(r -> r.toLowerCase(java.util.Locale.ROOT)).distinct()
                    .forEach(r -> out.merge(r, 1, Integer::sum));
        }
        return Collections.unmodifiableMap(out);
    }

    /** The subjects of the selected mails, in the order sent: {@code assertThat(mails.subjects()).containsExactly(...)}. */
    public List<String> subjects() {
        List<String> out = new ArrayList<>();
        selected.forEach(mail -> out.add(mail.subject()));
        return out;
    }

    /** The bodies of the selected mails, in the order sent. */
    public List<String> bodies() {
        List<String> out = new ArrayList<>();
        selected.forEach(mail -> out.add(mail.body()));
        return out;
    }

    public List<Mail> list() {
        return selected;
    }

    public int count() {
        return selected.size();
    }

    public Mails exactly(int expected) {
        if (selected.size() != expected) {
            throw new AssertionError("Expected " + expected + " " + description + ", found " + selected.size()
                    + "; sent in this test: " + summary());
        }
        return this;
    }

    public Mails none() {
        return exactly(0);
    }

    /** The one mail selected; fails if there are none or several. */
    public Mail single() {
        exactly(1);
        return selected.get(0);
    }

    private String summary() {
        if (all.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        for (Mail mail : all) {
            out.add(mail.recipients() + " '" + mail.subject() + "'");
        }
        return String.join("; ", out);
    }
}
