package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * The objects a block added, modified or deleted in midPoint's repository ({@link Effects#changes()}), narrowed by
 * object, type and kind, e.g. {@code effects.changes().of("jdoe").single().only("activation/validTo")}. Each
 * narrowing returns a new selection. In {@link Claims}, {@code exactly}, {@code none}, {@code single} and
 * {@link #ignored()} claim what they select.
 */
public final class Changes {

    private final List<ObjectChange> all;
    private final List<ObjectChange> selected;
    private final String description;
    private final Ledger ledger;

    private Changes(List<ObjectChange> all, List<ObjectChange> selected, String description, Ledger ledger) {
        this.all = all;
        this.selected = selected;
        this.description = description;
        this.ledger = ledger;
    }

    static Changes of(List<ObjectChange> changes, Ledger ledger) {
        List<ObjectChange> copy = Collections.unmodifiableList(new ArrayList<>(changes));
        return new Changes(copy, copy, "changed objects", ledger);
    }

    /** The changes of the object with this name or OID. */
    public Changes of(String nameOrOid) {
        return select(c -> nameOrOid.equals(c.name()) || nameOrOid.equals(c.oid()), " " + nameOrOid);
    }

    /** The changes of objects of the type, by its simple name: {@code UserType}, {@code ShadowType}... */
    public Changes ofType(String type) {
        return select(c -> type.equals(c.type()), " of " + type);
    }

    public Changes added() {
        return select(c -> c.kind() == ObjectChange.Kind.ADDED, " added");
    }

    public Changes modified() {
        return select(c -> c.kind() == ObjectChange.Kind.MODIFIED, " modified");
    }

    public Changes deleted() {
        return select(c -> c.kind() == ObjectChange.Kind.DELETED, " deleted");
    }

    public List<ObjectChange> list() {
        return selected;
    }

    public int count() {
        return selected.size();
    }

    public Changes exactly(int expected) {
        if (selected.size() != expected) {
            throw new AssertionError("Expected " + expected + " " + description + ", found " + selected.size()
                    + "; changed in the block: " + summary());
        }
        Ledger.claim(ledger, selected);
        return this;
    }

    public Changes none() {
        return exactly(0);
    }

    /** The one change selected; fails if there are none or several. */
    public ObjectChange single() {
        exactly(1);
        return selected.get(0);
    }

    /** Claims the selected changes without checking them ({@link Claims} only). */
    public void ignored() {
        Ledger.requireLedger(ledger);
        ledger.claim(selected);
    }

    private Changes select(Predicate<ObjectChange> keep, String what) {
        List<ObjectChange> out = new ArrayList<>();
        for (ObjectChange c : selected) {
            if (keep.test(c)) {
                out.add(c);
            }
        }
        return new Changes(all, out, description + what, ledger);
    }

    private String summary() {
        if (all.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        all.forEach(c -> out.add(c.toString()));
        return String.join("; ", out);
    }
}
