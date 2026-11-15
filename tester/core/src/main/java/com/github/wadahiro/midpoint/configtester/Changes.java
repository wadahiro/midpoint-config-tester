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
    private final MidPointConfigTester tester;

    private Changes(List<ObjectChange> all, List<ObjectChange> selected, String description, Ledger ledger,
            MidPointConfigTester tester) {
        this.all = all;
        this.selected = selected;
        this.description = description;
        this.ledger = ledger;
        this.tester = tester;
    }

    static Changes of(List<ObjectChange> changes, Ledger ledger, MidPointConfigTester tester) {
        List<ObjectChange> copy = Collections.unmodifiableList(new ArrayList<>(changes));
        return new Changes(copy, copy, "changed objects", ledger, tester);
    }

    /** The changes of the object with this name or OID. */
    public Changes of(String nameOrOid) {
        return select(c -> nameOrOid.equals(c.name()) || nameOrOid.equals(c.oid()), " " + nameOrOid);
    }

    /** The changes of objects of the type: {@code ofType(UserType.class)}, {@code ofType(ShadowType.class)}... */
    public Changes ofType(Class<? extends com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType> type) {
        String name = type.getSimpleName();
        return select(c -> name.equals(c.type()), " of " + name);
    }

    public Changes added() {
        return select(c -> c.change() == ObjectChange.Kind.ADDED, " added");
    }

    public Changes modified() {
        return select(c -> c.change() == ObjectChange.Kind.MODIFIED, " modified");
    }

    public Changes deleted() {
        return select(c -> c.change() == ObjectChange.Kind.DELETED, " deleted");
    }

    public List<ObjectChange> list() {
        return selected;
    }

    /**
     * The users, roles, orgs and services selected that the block added or modified, as handles by OID, e.g. to claim
     * that the configuration is at rest after a verb: {@code hired.changes().focuses().forEach(f ->
     * f.recompute().none())}. A deleted focus is left out, as are shadows, cases, tasks and the other objects
     * (archetypes too). A read, not a claim.
     */
    public List<Focus<?>> focuses() {
        List<Focus<?>> out = new ArrayList<>();
        for (ObjectChange c : selected) {
            if (c.change() == ObjectChange.Kind.DELETED || c.oid() == null) {
                continue;
            }
            switch (c.type()) {
                case "UserType":
                    out.add(tester.user(c.oid()));
                    break;
                case "RoleType":
                    out.add(tester.role(c.oid()));
                    break;
                case "OrgType":
                    out.add(tester.org(c.oid()));
                    break;
                case "ServiceType":
                    out.add(tester.service(c.oid()));
                    break;
                default:
                    break;
            }
        }
        return out;
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
        return new Changes(all, out, description + what, ledger, tester);
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
