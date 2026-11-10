package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import com.github.wadahiro.midpoint.tester.AuditRecord;

/**
 * What midPoint wrote to its audit log in the test ({@link MidPointConfigTester#audit()}) or in a part of it
 * ({@link MidPointConfigTester#audit(Runnable)}), narrowed by event type, stage, target and outcome, e.g.
 * {@code t.audit().ofType("DELETE_OBJECT").execution().target("jdoe").single().outcome()}: what an audit report, or
 * another system that reads the audit table, would see. Each narrowing returns a new selection.
 */
public final class Audit {

    private final List<AuditRecord> all;
    private final List<AuditRecord> selected;
    private final String description;
    /** Where the elements come from, for the failure message: {@code "in this test"}, {@code "in the block"}. */
    private final String scope;
    /** The claims of a verification ({@link Claims}), or null. */
    private final Ledger ledger;

    private Audit(List<AuditRecord> all, List<AuditRecord> selected, String description, String scope, Ledger ledger) {
        this.all = all;
        this.selected = selected;
        this.description = description;
        this.scope = scope;
        this.ledger = ledger;
    }

    static Audit of(List<AuditRecord> records) {
        List<AuditRecord> copy = Collections.unmodifiableList(new ArrayList<>(records));
        return new Audit(copy, copy, "audit records", "in this test", null);
    }

    /** The elements of a block ({@link Effects}), claimed in {@code ledger} if it is not null. */
    static Audit ofBlock(List<AuditRecord> elements, Ledger ledger) {
        List<AuditRecord> copy = Collections.unmodifiableList(new ArrayList<>(elements));
        return new Audit(copy, copy, "audit records", "in the block", ledger);
    }

    /** The records of the event type: {@code ADD_OBJECT}, {@code MODIFY_OBJECT}, {@code DELETE_OBJECT}... */
    public Audit ofType(String eventType) {
        return select(r -> eventType.equals(r.eventType()), " of " + eventType);
    }

    /** The records of the execution stage (what was done, with its outcome). */
    public Audit execution() {
        return select(r -> "EXECUTION".equals(r.stage()), " at EXECUTION");
    }

    /** The records of the request stage (what was asked for). */
    public Audit request() {
        return select(r -> "REQUEST".equals(r.stage()), " at REQUEST");
    }

    /** The records whose target has this name or OID. */
    public Audit target(String nameOrOid) {
        return select(r -> nameOrOid.equals(r.targetName()) || nameOrOid.equals(r.targetOid()),
                " of target " + nameOrOid);
    }

    /** The records whose target is of this type ({@code UserType}, {@code RoleType}...). */
    public Audit targetType(String type) {
        return select(r -> type.equals(r.targetType()), " of a " + type);
    }

    /** The records with this outcome ({@code SUCCESS}, {@code FATAL_ERROR}, {@code PARTIAL_ERROR}...). */
    public Audit outcome(String outcome) {
        return select(r -> outcome.equals(r.outcome()), " with outcome " + outcome);
    }

    /** The records whose initiator has this name or OID. */
    public Audit initiator(String nameOrOid) {
        return select(r -> nameOrOid.equals(r.initiatorName()) || nameOrOid.equals(r.initiatorOid()),
                " initiated by " + nameOrOid);
    }

    /** The records of the channel (its URI). */
    public Audit channel(String channelUri) {
        return select(r -> channelUri.equals(r.channel()), " in channel " + channelUri);
    }

    /** The selected records, in the order written. */
    public List<AuditRecord> list() {
        return selected;
    }

    public int count() {
        return selected.size();
    }

    public Audit exactly(int expected) {
        if (selected.size() != expected) {
            throw new AssertionError("Expected " + expected + " " + description + ", found " + selected.size()
                    + "; written " + scope + ": " + summary());
        }
        Ledger.claim(ledger, selected);
        return this;
    }

    /** Claims the selected elements without checking them ({@link Claims} only). */
    public void ignored() {
        Ledger.requireLedger(ledger);
        ledger.claim(selected);
    }

    public Audit none() {
        return exactly(0);
    }

    public AuditRecord single() {
        exactly(1);
        return selected.get(0);
    }

    private Audit select(Predicate<AuditRecord> keep, String what) {
        List<AuditRecord> out = new ArrayList<>();
        for (AuditRecord r : selected) {
            if (keep.test(r)) {
                out.add(r);
            }
        }
        return new Audit(all, out, description + what, scope, ledger);
    }

    private String summary() {
        if (all.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        all.forEach(r -> out.add(r.toString()));
        return String.join("; ", out);
    }
}
