package com.github.wadahiro.midpoint.configtester;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.tester.Approvals;

/**
 * An approval work item, open when found ({@link WorkItems#single()}): who it is assigned to, and the decisions on it.
 * A decision returns when midPoint has finished with it, including the execution of an approved change. The getters
 * show the item as found; {@link #isOpen()}, {@link #isApprovedBy}, {@link #isRejectedBy} and {@link #isCancelled()}
 * read it anew, so a test can check an item after another one's decision closed it (an approver given as a group,
 * expanded on work item creation, gives each member an item; with {@code firstDecides} the first decision closes the
 * others).
 */
public final class WorkItem {

    private final Scenario scenario;
    private final Approvals.WorkItem item;

    WorkItem(Scenario scenario, Approvals.WorkItem item) {
        this.scenario = scenario;
        this.item = item;
    }

    public String name() {
        return item.name();
    }

    /**
     * The users it is assigned to, as refs equal to the generated names:
     * {@code assertThat(item.assignees()).containsExactly(Users.SEC_OFFICER)}.
     */
    public List<UserRef> assignees() {
        List<UserRef> out = new ArrayList<>();
        item.assignees().forEach(user -> out.add(new UserRef(user, null, Lines.ALL)));
        return out;
    }

    /**
     * The orgs, roles or users it is offered to, by name: an approver given as a group without
     * {@code groupExpansion onWorkItemCreation}. Such an item has no assignee, and midPoint mails no one for it.
     */
    public List<String> candidates() {
        return item.candidates();
    }

    /** The approval stage (from 1). */
    public int stage() {
        return item.stageNumber() != null ? item.stageNumber() : 0;
    }

    /** 0 until the item has been escalated; each escalation (a timed action of its stage) adds one. */
    public int escalationLevel() {
        return item.escalationLevel();
    }

    /**
     * When it is due: the creation plus the stage's {@code duration}, after an escalation its {@code duration} from the
     * escalation; null without one. The stage's timed actions run at (or, {@code notifyBeforeAction}, before) this time,
     * when the trigger scanner runs ({@link ScenarioTime#runDue()}).
     */
    public Instant deadline() {
        return item.bean().getDeadline() != null
                ? item.bean().getDeadline().toGregorianCalendar().toInstant() : null;
    }

    /** The case the item is in. */
    public Case caseOf() {
        return new Case(scenario, item.caseOid());
    }

    // ---- decisions --------------------------------------------------------------------------------------------

    /** Approves as the approver (who must be an assignee, and authorized to complete work items). */
    public WorkItem approve(Actor approver) {
        return approve(approver, null);
    }

    public WorkItem approve(Actor approver, String comment) {
        scenario.deployment().api().approvals().complete(item, approver.name(), true, comment);
        return this;
    }

    public WorkItem reject(Actor approver) {
        return reject(approver, null);
    }

    public WorkItem reject(Actor approver, String comment) {
        scenario.deployment().api().approvals().complete(item, approver.name(), false, comment);
        return this;
    }

    /**
     * Delegates the item to these users (by name), as the delegator (an assignee): they become assignees besides the
     * present ones (the GUI's "Delegate", method {@code addAssignees}). The item as found keeps its old assignees: find
     * it again to see the new ones.
     */
    public WorkItem delegate(Actor delegator, String... users) {
        scenario.deployment().api().approvals().delegate(item, delegator.name(), users);
        return this;
    }

    /** {@link #delegate(Actor, String...)} by the generated users. */
    public WorkItem delegate(Actor delegator, UserRef... users) {
        return delegate(delegator, names(users));
    }

    /** {@link #forward(Actor, String...)} by the generated users. */
    public WorkItem forward(Actor forwarder, UserRef... users) {
        return forward(forwarder, names(users));
    }

    private static String[] names(UserRef... users) {
        String[] out = new String[users.length];
        for (int i = 0; i < users.length; i++) {
            out[i] = users[i].name();
        }
        return out;
    }

    /** Forwards the item to these users (by name), as the forwarder: they replace its assignees (the GUI's "Forward"). */
    public WorkItem forward(Actor forwarder, String... users) {
        scenario.deployment().api().approvals().forward(item, forwarder.name(), users);
        return this;
    }

    /** The user claims the item offered to a group they are a member of, and becomes its assignee. */
    public WorkItem claim(Actor user) {
        scenario.deployment().api().approvals().claim(item, user.name());
        return this;
    }

    /** The user releases the item they claimed, back to the group. */
    public WorkItem release(Actor user) {
        scenario.deployment().api().approvals().release(item, user.name());
        return this;
    }

    // ---- assertions -------------------------------------------------------------------------------------------

    public WorkItem hasAssignee(String user) {
        if (!item.assignees().contains(user)) {
            throw new AssertionError("Work item " + this + ": expected assignee " + user + ", assignees "
                    + item.assignees());
        }
        return this;
    }

    public WorkItem hasAssignee(UserRef user) {
        return hasAssignee(user.on(scenario.line()));
    }

    /** Still open, as midPoint stores it now. */
    public WorkItem isOpen() {
        Approvals.WorkItem now = current();
        if (!now.isOpen()) {
            throw new AssertionError("Work item " + this + ": expected open, but it is closed"
                    + (now.outcome() != null ? " (" + now.outcome() + " by " + now.performer() + ")" : ""));
        }
        return this;
    }

    /** Closed, approved by this user, as midPoint stores it now. */
    public WorkItem isApprovedBy(String user) {
        return isDecided("approve", user);
    }

    public WorkItem isApprovedBy(UserRef user) {
        return isApprovedBy(user.on(scenario.line()));
    }

    /** Closed, rejected by this user, as midPoint stores it now. */
    public WorkItem isRejectedBy(String user) {
        return isDecided("reject", user);
    }

    public WorkItem isRejectedBy(UserRef user) {
        return isRejectedBy(user.on(scenario.line()));
    }

    /**
     * Closed without a decision, as midPoint stores it now: another approver's decision closed its stage
     * ({@code firstDecides}), or the case was cancelled.
     */
    public WorkItem isCancelled() {
        Approvals.WorkItem now = current();
        if (now.isOpen() || now.outcome() != null) {
            throw new AssertionError("Work item " + this + ": expected closed without a decision, but it is "
                    + state(now));
        }
        return this;
    }

    public WorkItem atStage(int expected) {
        if (stage() != expected) {
            throw new AssertionError("Work item " + this + ": expected stage " + expected + " but is at " + stage());
        }
        return this;
    }

    public WorkItem atEscalationLevel(int expected) {
        if (escalationLevel() != expected) {
            throw new AssertionError("Work item " + this + ": expected escalation level " + expected + " but is at "
                    + escalationLevel());
        }
        return this;
    }

    @Override
    public String toString() {
        return "'" + item.name() + "' (stage " + item.stageNumber() + ", assignees " + item.assignees()
                + (item.candidates().isEmpty() ? "" : ", candidates " + item.candidates()) + ")";
    }

    // ---- package ----------------------------------------------------------------------------------------------

    Approvals.WorkItem item() {
        return item;
    }

    /** The item as midPoint stores it now. */
    private Approvals.WorkItem current() {
        for (Approvals.Case c : scenario.deployment().api().approvals().cases()) {
            if (c.oid().equals(item.caseOid())) {
                for (Approvals.WorkItem w : c.workItems()) {
                    if (w.id() == item.id()) {
                        return w;
                    }
                }
            }
        }
        throw new AssertionError("Work item " + this + " is gone (its case deleted by midPoint's cleanup?)");
    }

    private WorkItem isDecided(String outcome, String performer) {
        Approvals.WorkItem now = current();
        if (now.isOpen() || !outcome.equals(now.outcome()) || !performer.equals(now.performer())) {
            throw new AssertionError("Work item " + this + ": expected closed with " + outcome + " by " + performer
                    + ", but it is " + state(now));
        }
        return this;
    }

    private static String state(Approvals.WorkItem w) {
        return w.isOpen() ? "open" : w.outcome() != null ? "closed with " + w.outcome() + " by " + w.performer()
                : "closed without a decision";
    }
}
