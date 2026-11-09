package com.github.wadahiro.midpoint.configtester;

import java.util.List;
import java.util.stream.Collectors;

import com.github.wadahiro.midpoint.tester.Approvals;

/**
 * An approval case ({@link Scenario#cases()}): what a request waits in. Read anew by every call; assertions return
 * the case for chaining.
 */
public final class Case {

    private final Scenario scenario;
    private final String oid;

    Case(Scenario scenario, String oid) {
        this.scenario = scenario;
        this.oid = oid;
    }

    /** The case's name as midPoint gives it (e.g. {@code Assigning role "Privileged Access" to user "jack"}). */
    public String name() {
        return current().name();
    }

    /** midPoint's state: {@code open}, {@code executing}, {@code closed}... */
    public String state() {
        return current().state();
    }

    /** {@code approve}, {@code reject}, or null while undecided. */
    public String outcome() {
        return current().outcome();
    }

    /** The current stage (from 1); 0 when the case has none. */
    public int stage() {
        Integer stage = current().stageNumber();
        return stage != null ? stage : 0;
    }

    /** The user the request is for. */
    public String user() {
        return scenario.nameOf(current().bean().getObjectRef());
    }

    /** The role (or other target) requested; null for a change of the user itself. */
    public String role() {
        return scenario.nameOf(current().bean().getTargetRef());
    }

    /** The case's open work items. */
    public WorkItems workItems() {
        return scenario.workItems().inCase(oid);
    }

    // ---- assertions -------------------------------------------------------------------------------------------

    public Case isOpen() {
        if (isClosed(current())) {
            throw new AssertionError("Expected case " + this + " to be open");
        }
        return this;
    }

    public Case isClosed() {
        if (!isClosed(current())) {
            throw new AssertionError("Expected case " + this + " to be closed");
        }
        return this;
    }

    /** Closed with the outcome {@code approve}: the requested change has been executed. */
    public Case isApproved() {
        return isClosed().hasOutcome("approve");
    }

    /** Closed with the outcome {@code reject}: nothing was executed. */
    public Case isRejected() {
        return isClosed().hasOutcome("reject");
    }

    public Case hasOutcome(String expected) {
        String actual = outcome();
        if (!expected.equals(actual)) {
            throw new AssertionError("Case " + this + ": expected outcome " + expected + " but was " + actual);
        }
        return this;
    }

    public Case atStage(int expected) {
        if (stage() != expected) {
            throw new AssertionError("Case " + this + ": expected stage " + expected + " but is at stage " + stage()
                    + "; open work items: " + workItems().list());
        }
        return this;
    }

    @Override
    public String toString() {
        Approvals.Case c = current();
        return "'" + c.name() + "' (" + c.state() + ", stage " + c.stageNumber()
                + (c.outcome() != null ? ", " + c.outcome() : "") + ")";
    }

    // ---- package ----------------------------------------------------------------------------------------------

    String oid() {
        return oid;
    }

    Approvals.Case current() {
        List<Approvals.Case> found = scenario.deployment().api().approvals().cases().stream()
                .filter(c -> oid.equals(c.oid())).collect(Collectors.toList());
        if (found.isEmpty()) {
            throw new AssertionError("The case " + oid + " is gone (deleted by midPoint's cleanup?)");
        }
        return found.get(0);
    }

    static boolean isClosed(Approvals.Case c) {
        return "closed".equals(c.state()) || c.state() != null && c.state().endsWith("#closed");
    }
}
