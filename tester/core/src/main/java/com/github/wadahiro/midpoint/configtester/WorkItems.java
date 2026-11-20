package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import com.github.wadahiro.midpoint.configtester.names.AssignableRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.tester.Approvals;

/**
 * The open approval work items of the scenario ({@link MidPointConfigTester#workItems()}, read as the administrator), or
 * those a user sees in the GUI's "My work items" ({@link Actor#workItems()}, searched as the user), narrowed by the
 * filters; read when a terminal call ({@link #list()}, {@link #single()}, {@link #exactly}...) is made.
 *
 * <pre>{@code
 * t.workItems().assignedTo("cc-owner").forRole("Privileged Access").single().approve(t.actor("cc-owner"));
 * t.workItems().forUser("jack").none();
 * t.actor("cc-owner").workItems().forRole("Privileged Access").approve();
 * }</pre>
 */
public final class WorkItems {

    private final MidPointConfigTester tester;
    /** The user whose "My work items" these are, or null: every open work item, read as the administrator. */
    private final Actor actor;
    /** The work item, and its case. */
    private final BiPredicate<Approvals.WorkItem, Approvals.Case> filter;
    private final String description;

    WorkItems(MidPointConfigTester tester, BiPredicate<Approvals.WorkItem, Approvals.Case> filter, String description) {
        this(tester, null, filter, description);
    }

    WorkItems(MidPointConfigTester tester, Actor actor, BiPredicate<Approvals.WorkItem, Approvals.Case> filter,
            String description) {
        this.tester = tester;
        this.actor = actor;
        this.filter = filter;
        this.description = description;
    }

    /** Items assigned to this user (each member of an approver group expanded on work item creation has one). */
    public WorkItems assignedTo(String user) {
        return and((w, c) -> w.assignees().contains(user), "assigned to " + user);
    }

    /** Items of requests for this user. */
    public WorkItems forUser(String user) {
        return and((w, c) -> user.equals(tester.nameOf(c.bean().getObjectRef())), "for user " + user);
    }

    /** Items of requests of this role (or other target). */
    public WorkItems forRole(String role) {
        return and((w, c) -> role.equals(tester.nameOf(c.bean().getTargetRef())), "of " + role);
    }

    public WorkItems assignedTo(UserRef user) {
        return assignedTo(user.on(tester.line()));
    }

    public WorkItems forUser(UserRef user) {
        return forUser(user.on(tester.line()));
    }

    public WorkItems forRole(AssignableRef role) {
        return forRole(role.on(tester.line()));
    }

    public WorkItems inStage(int stage) {
        return and((w, c) -> w.stageNumber() != null && w.stageNumber() == stage, "in stage " + stage);
    }

    WorkItems inCase(String caseOid) {
        return and((w, c) -> caseOid.equals(c.oid()), "of case " + caseOid);
    }

    // ---- terminal ---------------------------------------------------------------------------------------------

    public List<WorkItem> list() {
        List<WorkItem> out = new ArrayList<>();
        for (Map.Entry<Approvals.WorkItem, Approvals.Case> entry : open().entrySet()) {
            if (filter.test(entry.getKey(), entry.getValue())) {
                out.add(new WorkItem(tester, entry.getKey(), actor));
            }
        }
        return out;
    }

    public int count() {
        return list().size();
    }

    public WorkItems exactly(int expected) {
        List<WorkItem> found = list();
        if (found.size() != expected) {
            throw new AssertionError("Expected " + expected + " open work item(s) " + what() + ", found "
                    + found.size() + ": " + found + all());
        }
        return this;
    }

    public WorkItems none() {
        return exactly(0);
    }

    public WorkItem single() {
        List<WorkItem> found = list();
        if (found.size() != 1) {
            throw new AssertionError("Expected one open work item " + what() + ", found " + found.size() + ": "
                    + found + all());
        }
        return found.get(0);
    }

    /** {@link #single()}, approved. */
    public Effects approve(Actor approver) {
        return single().approve(approver);
    }

    /** {@link #single()}, rejected. */
    public Effects reject(Actor approver) {
        return single().reject(approver);
    }

    /** {@link #single()}, approved by the user whose work items these are ({@link Actor#workItems()}). */
    public Effects approve() {
        return single().approve();
    }

    /** {@link #single()}, rejected by the user whose work items these are ({@link Actor#workItems()}). */
    public Effects reject() {
        return single().reject();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private WorkItems and(BiPredicate<Approvals.WorkItem, Approvals.Case> more, String what) {
        return new WorkItems(tester, actor, filter.and(more), description.isEmpty() ? what : description + ", " + what);
    }

    /** {@code assigned to cc-owner} with what the items are: {@code in cc-owner's work items, of Privileged Access}. */
    private String what() {
        if (actor == null) {
            return description;
        }
        return "in " + actor.name() + "'s work items" + (description.isEmpty() ? "" : ", " + description);
    }

    /**
     * The open work items, each with its case (as the administrator reads them): all of them in the order of the
     * cases, or those the user's "My work items" finds, in its order.
     */
    private Map<Approvals.WorkItem, Approvals.Case> open() {
        Map<Approvals.WorkItem, Approvals.Case> all = new java.util.LinkedHashMap<>();
        List<Approvals.Case> cases = tester.deployment().api().approvals().cases();
        for (Approvals.Case c : cases) {
            for (Approvals.WorkItem w : c.workItems()) {
                if (w.isOpen()) {
                    all.put(w, c);
                }
            }
        }
        if (actor == null) {
            return all;
        }
        actor.explainAuthorizations();
        Map<Approvals.WorkItem, Approvals.Case> out = new java.util.LinkedHashMap<>();
        for (Approvals.WorkItem seen : tester.deployment().api().approvals().workItemsOf(actor.name())) {
            for (Map.Entry<Approvals.WorkItem, Approvals.Case> entry : all.entrySet()) {
                if (entry.getKey().caseOid().equals(seen.caseOid()) && entry.getKey().id() == seen.id()) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return out;
    }

    private String all() {
        Map<Approvals.WorkItem, Approvals.Case> all = actor == null ? open()
                : new WorkItems(tester, null, (w, c) -> true, "").open();
        return "; all open work items: " + all.keySet().stream().map(w -> new WorkItem(tester, w, null).toString())
                .collect(Collectors.toList());
    }
}
