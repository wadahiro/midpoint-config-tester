package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The calls midPoint made to the connectors of its resources in a block ({@link Effects#connectorCalls()}), in the
 * order made, narrowed by resource, account and operation, e.g.
 * {@code effects.connectorCalls().to("LDAP").modifies().of("jdoe").single().only("mail")}, or counted:
 * {@code effects.connectorCalls().to("HR").reads().count()}. Each narrowing returns a new selection. In
 * {@link Claims}, {@code exactly}, {@code none}, {@code single} and {@link #ignored()} claim what they select; only
 * writes have to be claimed.
 */
public final class ConnectorCalls {

    private final List<ConnectorCall> all;
    private final List<ConnectorCall> selected;
    private final String description;
    private final Ledger ledger;

    private ConnectorCalls(List<ConnectorCall> all, List<ConnectorCall> selected, String description, Ledger ledger) {
        this.all = all;
        this.selected = selected;
        this.description = description;
        this.ledger = ledger;
    }

    static ConnectorCalls of(List<ConnectorCall> calls, Ledger ledger) {
        List<ConnectorCall> copy = Collections.unmodifiableList(new ArrayList<>(calls));
        return new ConnectorCalls(copy, copy, "connector calls", ledger);
    }

    /** The calls to the connector of the resource with this name. */
    public ConnectorCalls to(String resource) {
        return select(c -> resource.equals(c.resource()), " to " + resource);
    }

    /**
     * The calls about the account with this name ({@link ConnectorCall#account()}), ignoring case: a target system
     * compares names so (an LDAP DN), and midPoint may write one in another case than the configuration.
     */
    public ConnectorCalls of(String account) {
        return select(c -> account.equalsIgnoreCase(c.account()), " of " + account);
    }

    /** The calls about objects of the object class (its local name). */
    public ConnectorCalls ofClass(String objectClass) {
        return select(c -> objectClass.equals(c.objectClass()), " of class " + objectClass);
    }

    public ConnectorCalls adds() {
        return operation("add");
    }

    public ConnectorCalls modifies() {
        return operation("modify");
    }

    public ConnectorCalls deletes() {
        return operation("delete");
    }

    /** The calls that change the target system: add, modify, delete and scripts. */
    public ConnectorCalls writes() {
        return select(ConnectorCall::isWrite, " that write");
    }

    /** The calls that read the target system: fetch, search, count, fetchChanges... */
    public ConnectorCalls reads() {
        return select(c -> !c.isWrite(), " that read");
    }

    /** The calls of the operation ({@link ConnectorCall#operation()}). */
    public ConnectorCalls operation(String operation) {
        return select(c -> operation.equals(c.operation()), " " + operation);
    }

    /** How many of the selected calls each resource got per operation ({@code "HR search" -> 12}), sorted. */
    public Map<String, Integer> perOperation() {
        Map<String, Integer> out = new TreeMap<>();
        selected.forEach(c -> out.merge(c.resource() + " " + c.operation(), 1, Integer::sum));
        return Collections.unmodifiableMap(out);
    }

    public List<ConnectorCall> list() {
        return selected;
    }

    public int count() {
        return selected.size();
    }

    public ConnectorCalls exactly(int expected) {
        if (selected.size() != expected) {
            throw new AssertionError("Expected " + expected + " " + description + ", found " + selected.size()
                    + "; called in the block: " + summary());
        }
        Ledger.claim(ledger, selected);
        return this;
    }

    public ConnectorCalls none() {
        return exactly(0);
    }

    /** The one call selected; fails if there are none or several. */
    public ConnectorCall single() {
        exactly(1);
        return selected.get(0);
    }

    /** Claims the selected calls without checking them ({@link Claims} only). */
    public void ignored() {
        Ledger.requireLedger(ledger);
        ledger.claim(selected);
    }

    private ConnectorCalls select(Predicate<ConnectorCall> keep, String what) {
        List<ConnectorCall> out = new ArrayList<>();
        for (ConnectorCall c : selected) {
            if (keep.test(c)) {
                out.add(c);
            }
        }
        return new ConnectorCalls(all, out, description + what, ledger);
    }

    private String summary() {
        List<String> out = new ArrayList<>();
        all.stream().filter(ConnectorCall::isWrite).forEach(c -> out.add(c.toString()));
        long reads = all.stream().filter(c -> !c.isWrite()).count();
        if (reads > 0) {
            out.add(reads + " reads");
        }
        return out.isEmpty() ? "none" : String.join("; ", out);
    }
}
