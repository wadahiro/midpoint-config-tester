package com.github.wadahiro.midpoint.configtester;

import java.util.List;

import com.github.wadahiro.midpoint.tester.Certification;

/** An open review work item of a campaign ({@link Reviews#single()}): a reviewer's decision on one assignment. */
public final class Review {

    private final MidPointConfigTester tester;
    private final Certification.WorkItem item;

    Review(MidPointConfigTester tester, Certification.WorkItem item) {
        this.tester = tester;
        this.item = item;
    }

    /** The user whose assignment is reviewed. */
    public String user() {
        return item.objectName();
    }

    /** The role (or other target) of the assignment. */
    public String role() {
        return item.targetName();
    }

    public List<String> reviewers() {
        return item.reviewers();
    }

    public int stage() {
        return item.stageNumber();
    }

    /** The reviewer's response ({@code accept}, {@code revoke}...), null while undecided. */
    public String response() {
        return item.response();
    }

    /** The assignment stays. */
    public Review accept(Actor reviewer) {
        return decide(reviewer, "accept", null);
    }

    public Review accept(Actor reviewer, String comment) {
        return decide(reviewer, "accept", comment);
    }

    /** The assignment goes (at the remediation). */
    public Review revoke(Actor reviewer) {
        return decide(reviewer, "revoke", null);
    }

    public Review revoke(Actor reviewer, String comment) {
        return decide(reviewer, "revoke", comment);
    }

    /**
     * Any of midPoint's responses: {@code accept}, {@code revoke}, {@code reduce}, {@code notDecided},
     * {@code noResponse}.
     */
    public Review decide(Actor reviewer, String response, String comment) {
        tester.deployment().api().certification().decide(item, reviewer.name(), response, comment);
        return this;
    }

    @Override
    public String toString() {
        return item.objectName() + "/" + item.targetName() + " (stage " + item.stageNumber() + ", reviewers "
                + item.reviewers() + ")";
    }
}
