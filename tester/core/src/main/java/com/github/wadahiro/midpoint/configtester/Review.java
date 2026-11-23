package com.github.wadahiro.midpoint.configtester;

import java.util.List;

import com.github.wadahiro.midpoint.tester.Certification;

/** An open review work item of a campaign ({@link Reviews#single()}): a reviewer's decision on one assignment. */
public final class Review {

    private final MidPointConfigTester tester;
    private final Certification.WorkItem item;
    /** The comment the row shows: as listed, then as saved through this review (the GUI's row model). */
    private String rowComment;

    Review(MidPointConfigTester tester, Certification.WorkItem item) {
        this.tester = tester;
        this.item = item;
        this.rowComment = item.comment();
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

    /** The reviewer's comment, null if none: as listed, or as saved through this review since. */
    public String comment() {
        return rowComment;
    }

    /**
     * Saves a comment alone, as the GUI's comment column does: the response stays as midPoint has it now (undecided
     * stays undecided). A later {@link #accept}, {@link #revoke} or {@link #decide} is a save of its own, with its own
     * comment.
     */
    public Effects comment(Actor reviewer, String comment) {
        Effects effects = tester.effectsInGui(
                () -> tester.deployment().api().certification().comment(item, reviewer.name(), comment));
        rowComment = comment;
        return effects;
    }

    /**
     * The assignment stays. The comment stays too: the GUI's response buttons send the comment the row shows (this
     * review's {@link #comment()}; a review listed before a comment saved through another one sends the older comment,
     * as a page not reloaded does: list it again).
     */
    public Effects accept(Actor reviewer) {
        return decide(reviewer, "accept", rowComment);
    }

    public Effects accept(Actor reviewer, String comment) {
        return decide(reviewer, "accept", comment);
    }

    /** The assignment goes (at the remediation). The comment saved before stays, as with {@link #accept(Actor)}. */
    public Effects revoke(Actor reviewer) {
        return decide(reviewer, "revoke", rowComment);
    }

    public Effects revoke(Actor reviewer, String comment) {
        return decide(reviewer, "revoke", comment);
    }

    /**
     * Any of midPoint's responses: {@code accept}, {@code revoke}, {@code reduce}, {@code notDecided},
     * {@code noResponse}, with this comment (null: none, replacing a comment saved before).
     */
    public Effects decide(Actor reviewer, String response, String comment) {
        Effects effects = tester.effectsInGui(
                () -> tester.deployment().api().certification().decide(item, reviewer.name(), response, comment));
        rowComment = comment;
        return effects;
    }

    @Override
    public String toString() {
        return item.objectName() + "/" + item.targetName() + " (stage " + item.stageNumber() + ", reviewers "
                + item.reviewers() + ")";
    }
}
