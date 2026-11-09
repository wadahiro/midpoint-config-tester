package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.tester.Approvals;

/**
 * The approval cases of the scenario ({@link MidPointConfigTester#cases()}), narrowed by the filters; read when a terminal call
 * ({@link #list()}, {@link #single()}, {@link #exactly}...) is made.
 *
 * <pre>{@code
 * t.cases().forUser("jack").forRole("Privileged Access").single().isOpen().atStage(1);
 * }</pre>
 */
public final class Cases {

    private final MidPointConfigTester tester;
    private final Predicate<Approvals.Case> filter;
    private final String description;

    Cases(MidPointConfigTester tester, Predicate<Approvals.Case> filter, String description) {
        this.tester = tester;
        this.filter = filter;
        this.description = description;
    }

    /** Cases of requests for this user. */
    public Cases forUser(String user) {
        return and(c -> user.equals(tester.nameOf(c.bean().getObjectRef())), "for user " + user);
    }

    /** Cases of requests of this role (or other target). */
    public Cases forRole(String role) {
        return and(c -> role.equals(tester.nameOf(c.bean().getTargetRef())), "of " + role);
    }

    public Cases forUser(UserRef user) {
        return forUser(user.on(tester.line()));
    }

    public Cases forRole(AssignableRef role) {
        return forRole(role.on(tester.line()));
    }

    /** Cases whose name contains the text. */
    public Cases named(String text) {
        return and(c -> c.name() != null && c.name().contains(text), "named *" + text + "*");
    }

    public Cases open() {
        return and(c -> !Case.isClosed(c), "open");
    }

    public Cases closed() {
        return and(Case::isClosed, "closed");
    }

    // ---- terminal ---------------------------------------------------------------------------------------------

    /** The cases, oldest first. */
    public List<Case> list() {
        List<Case> out = new ArrayList<>();
        for (Approvals.Case c : tester.deployment().api().approvals().cases()) {
            if (filter.test(c)) {
                out.add(new Case(tester, c.oid()));
            }
        }
        return out;
    }

    public int count() {
        return list().size();
    }

    public Cases exactly(int expected) {
        List<Case> found = list();
        if (found.size() != expected) {
            throw new AssertionError("Expected " + expected + " case(s) " + description + ", found " + found.size()
                    + ": " + found + all());
        }
        return this;
    }

    public Cases none() {
        return exactly(0);
    }

    /** The one case; fails when there is none or more. */
    public Case single() {
        List<Case> found = list();
        if (found.size() != 1) {
            throw new AssertionError("Expected one case " + description + ", found " + found.size() + ": " + found
                    + all());
        }
        return found.get(0);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Cases and(Predicate<Approvals.Case> more, String what) {
        return new Cases(tester, filter.and(more), description.isEmpty() ? what : description + ", " + what);
    }

    private String all() {
        List<String> all = new ArrayList<>();
        for (Approvals.Case c : tester.deployment().api().approvals().cases()) {
            all.add(new Case(tester, c.oid()).toString());
        }
        return "; all cases: " + all;
    }
}
