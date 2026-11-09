package com.github.wadahiro.midpoint.configtester;

import java.util.List;
import java.util.stream.Collectors;

import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.tester.Certification.Case;

/**
 * A certification campaign ({@link Certification#start()}), read anew by every call: its stages, its review work
 * items and the outcome of each case (a user's assignment of a role).
 */
public final class Campaign {

    private final Scenario scenario;
    private final String oid;

    Campaign(Scenario scenario, String oid) {
        this.scenario = scenario;
        this.oid = oid;
    }

    public String name() {
        return current().name();
    }

    /**
     * midPoint's state: {@code CREATED}, {@code IN_REVIEW_STAGE}, {@code REVIEW_STAGE_DONE}, {@code IN_REMEDIATION},
     * {@code CLOSED}.
     */
    public String state() {
        return current().state();
    }

    /** The current stage (from 1; 0 before the first). */
    public int stage() {
        return current().stageNumber();
    }

    /** The review work items of the current stage (decided or not, until the stage closes). */
    public Reviews reviews() {
        return new Reviews(scenario, this, (w, c) -> true, "");
    }

    /**
     * The outcome of the case of the user's assignment of the role: {@code accept}, {@code revoke}, {@code reduce},
     * {@code notDecided}, {@code noResponse}; null while it has none.
     */
    public String outcomeOf(String user, String role) {
        return caseOf(user, role).outcome();
    }

    /** The reviewers of the case in the stage, by name. */
    public List<String> reviewersOf(String user, String role, int stage) {
        return caseOf(user, role).reviewers(stage);
    }

    /** The users and roles of the campaign's cases, as {@code user/role}. */
    public List<String> cases() {
        return current().cases().stream().map(c -> c.objectName() + "/" + c.targetName()).collect(Collectors.toList());
    }

    // ---- moving on --------------------------------------------------------------------------------------------

    /** Opens the next stage (the first one of a campaign just created): its reviewers get their work items. */
    public Campaign nextStage() {
        scenario.deployment().api().certification().openNextStage(oid);
        return this;
    }

    /** Closes the current stage: the cases get the stage's outcome. */
    public Campaign closeStage() {
        scenario.deployment().api().certification().closeCurrentStage(oid);
        return this;
    }

    /**
     * Starts the remediation after the last stage: with automated remediation, midPoint removes the revoked
     * assignments (waited for).
     */
    public Campaign remediate() {
        scenario.deployment().api().certification().startRemediation(oid);
        return this;
    }

    public Campaign close() {
        scenario.deployment().api().certification().closeCampaign(oid);
        return this;
    }

    // ---- assertions -------------------------------------------------------------------------------------------

    public Campaign isInStage(int expected) {
        if (stage() != expected) {
            throw new AssertionError("Campaign " + this + ": expected stage " + expected + " but is in " + stage());
        }
        return this;
    }

    public Campaign hasState(String expected) {
        if (!expected.equals(state())) {
            throw new AssertionError("Campaign " + this + ": expected state " + expected + " but is " + state());
        }
        return this;
    }

    public Campaign hasOutcome(String user, String role, String expected) {
        String actual = outcomeOf(user, role);
        if (!expected.equals(actual)) {
            throw new AssertionError("Campaign " + this + ": expected outcome " + expected + " for " + user + "/"
                    + role + " but was " + actual);
        }
        return this;
    }

    public Campaign hasOutcome(UserRef user, AssignableRef role, String expected) {
        return hasOutcome(user.on(scenario.line()), role.on(scenario.line()), expected);
    }

    public String outcomeOf(UserRef user, AssignableRef role) {
        return outcomeOf(user.on(scenario.line()), role.on(scenario.line()));
    }

    @Override
    public String toString() {
        com.github.wadahiro.midpoint.tester.Certification.Campaign c = current();
        return "'" + c.name() + "' (" + c.state() + ", stage " + c.stageNumber() + ")";
    }

    // ---- package ----------------------------------------------------------------------------------------------

    String oid() {
        return oid;
    }

    com.github.wadahiro.midpoint.tester.Certification.Campaign current() {
        return scenario.deployment().api().certification().campaign(oid);
    }

    private Case caseOf(String user, String role) {
        List<Case> found = current().cases().stream()
                .filter(c -> user.equals(c.objectName()) && role.equals(c.targetName())).collect(Collectors.toList());
        if (found.isEmpty()) {
            throw new AssertionError("Campaign " + this + " has no case of " + user + "/" + role + "; cases: "
                    + cases());
        }
        return found.get(0);
    }
}
