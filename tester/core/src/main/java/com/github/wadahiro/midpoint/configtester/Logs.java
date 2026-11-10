package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import com.github.wadahiro.midpoint.tester.LogEntry;

/**
 * What the configuration's scripts logged in the test ({@link MidPointConfigTester#logs()}), INFO and up, narrowed by level and
 * text, e.g. {@code t.logs().info().containing("reissued").exactly(1)}. Scripts' {@code log} is captured, and the
 * loggers of the manifest's {@code logs.capture} (a custom library's). Each narrowing returns a new selection.
 */
public final class Logs {

    private final List<LogEntry> all;
    private final List<LogEntry> selected;
    private final String description;
    /** Where the elements come from, for the failure message: {@code "in this test"}, {@code "in the block"}. */
    private final String scope;
    /** The claims of a verification ({@link Claims}), or null. */
    private final Ledger ledger;

    private Logs(List<LogEntry> all, List<LogEntry> selected, String description, String scope, Ledger ledger) {
        this.all = all;
        this.selected = selected;
        this.description = description;
        this.scope = scope;
        this.ledger = ledger;
    }

    static Logs of(List<LogEntry> entries) {
        List<LogEntry> copy = Collections.unmodifiableList(new ArrayList<>(entries));
        return new Logs(copy, copy, "log messages", "in this test", null);
    }

    /** The elements of a block ({@link Effects}), claimed in {@code ledger} if it is not null. */
    static Logs ofBlock(List<LogEntry> elements, Ledger ledger) {
        List<LogEntry> copy = Collections.unmodifiableList(new ArrayList<>(elements));
        return new Logs(copy, copy, "log messages", "in the block", ledger);
    }

    public Logs info() {
        return select(e -> "INFO".equals(e.level()), " at INFO");
    }

    public Logs warnings() {
        return select(e -> "WARN".equals(e.level()), " at WARN");
    }

    public Logs errors() {
        return select(e -> "ERROR".equals(e.level()), " at ERROR");
    }

    /** The messages that contain the text. */
    public Logs containing(String text) {
        return select(e -> e.message() != null && e.message().contains(text), " containing '" + text + "'");
    }

    /** The messages of this logger or below it (a package prefix). */
    public Logs from(String logger) {
        return select(e -> e.logger().equals(logger) || e.logger().startsWith(logger + "."), " from " + logger);
    }

    /** The selected messages, in the order logged. */
    public List<String> messages() {
        List<String> out = new ArrayList<>();
        selected.forEach(e -> out.add(e.message()));
        return out;
    }

    public List<LogEntry> list() {
        return selected;
    }

    public int count() {
        return selected.size();
    }

    public Logs exactly(int expected) {
        if (selected.size() != expected) {
            throw new AssertionError("Expected " + expected + " " + description + ", found " + selected.size()
                    + "; logged " + scope + ": " + summary());
        }
        Ledger.claim(ledger, selected);
        return this;
    }

    /** Claims the selected elements without checking them ({@link Claims} only). */
    public void ignored() {
        Ledger.requireLedger(ledger);
        ledger.claim(selected);
    }

    public Logs none() {
        return exactly(0);
    }

    public LogEntry single() {
        exactly(1);
        return selected.get(0);
    }

    private Logs select(Predicate<LogEntry> keep, String what) {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : selected) {
            if (keep.test(e)) {
                out.add(e);
            }
        }
        return new Logs(all, out, description + what, scope, ledger);
    }

    private String summary() {
        if (all.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        all.forEach(e -> out.add(e.toString()));
        return String.join("; ", out);
    }
}
