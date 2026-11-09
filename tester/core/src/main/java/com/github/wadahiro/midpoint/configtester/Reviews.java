package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.tester.Certification;

/**
 * The review work items of a campaign's current stage, decided or not (they close with the stage) ({@link Campaign#reviews()}), narrowed by the filters;
 * read when a terminal call is made.
 */
public final class Reviews {

    private final Scenario scenario;
    private final Campaign campaign;
    private final BiPredicate<Certification.WorkItem, Certification.Case> filter;
    private final String description;

    Reviews(Scenario scenario, Campaign campaign, BiPredicate<Certification.WorkItem, Certification.Case> filter,
            String description) {
        this.scenario = scenario;
        this.campaign = campaign;
        this.filter = filter;
        this.description = description;
    }

    /** Items of this reviewer. */
    public Reviews forReviewer(String reviewer) {
        return and((w, c) -> w.reviewers().contains(reviewer), "of reviewer " + reviewer);
    }

    /** Items of assignments of this user. */
    public Reviews forUser(String user) {
        return and((w, c) -> user.equals(w.objectName()), "for user " + user);
    }

    /** Items of assignments of this role (or other target). */
    public Reviews forRole(String role) {
        return and((w, c) -> role.equals(w.targetName()), "of " + role);
    }

    public Reviews forReviewer(UserRef reviewer) {
        return forReviewer(reviewer.on(scenario.line()));
    }

    public Reviews forUser(UserRef user) {
        return forUser(user.on(scenario.line()));
    }

    public Reviews forRole(AssignableRef role) {
        return forRole(role.on(scenario.line()));
    }

    /** Items without a decision yet (a decided item stays open until the stage closes; it can be decided again). */
    public Reviews undecided() {
        return and((w, c) -> w.response() == null, "undecided");
    }

    // ---- terminal ---------------------------------------------------------------------------------------------

    public List<Review> list() {
        List<Review> out = new ArrayList<>();
        Certification.Campaign current = campaign.current();
        for (Certification.Case c : current.cases()) {
            for (Certification.WorkItem w : c.workItems()) {
                if (w.isOpen() && w.stageNumber() == current.stageNumber() && filter.test(w, c)) {
                    out.add(new Review(scenario, w));
                }
            }
        }
        return out;
    }

    public int count() {
        return list().size();
    }

    public Reviews exactly(int expected) {
        List<Review> found = list();
        if (found.size() != expected) {
            throw new AssertionError("Expected " + expected + " open review(s) " + description + " in " + campaign
                    + ", found " + found.size() + ": " + found + "; all open: " + all());
        }
        return this;
    }

    public Reviews none() {
        return exactly(0);
    }

    public Review single() {
        List<Review> found = list();
        if (found.size() != 1) {
            throw new AssertionError("Expected one open review " + description + " in " + campaign + ", found "
                    + found.size() + ": " + found + "; all open: " + all());
        }
        return found.get(0);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Reviews and(BiPredicate<Certification.WorkItem, Certification.Case> more, String what) {
        return new Reviews(scenario, campaign, filter.and(more), description.isEmpty() ? what : description + ", " + what);
    }

    private List<Review> all() {
        return new Reviews(scenario, campaign, (w, c) -> true, "").list();
    }
}
