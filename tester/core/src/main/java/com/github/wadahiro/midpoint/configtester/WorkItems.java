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
 * The open approval work items of the scenario ({@link MidPointConfigTester#workItems()}), narrowed by the filters; read when a
 * terminal call ({@link #list()}, {@link #single()}, {@link #exactly}...) is made.
 *
 * <pre>{@code
 * t.workItems().assignedTo("cc-owner").forRole("Privileged Access").single().approve(t.as("cc-owner"));
 * t.workItems().forUser("jack").none();
 * }</pre>
 */
public final class WorkItems {

    private final MidPointConfigTester tester;
    /** The work item, and its case. */
    private final BiPredicate<Approvals.WorkItem, Approvals.Case> filter;
    private final String description;

    WorkItems(MidPointConfigTester tester, BiPredicate<Approvals.WorkItem, Approvals.Case> filter, String description) {
        this.tester = tester;
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
                out.add(new WorkItem(tester, entry.getKey()));
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
            throw new AssertionError("Expected " + expected + " open work item(s) " + description + ", found "
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
            throw new AssertionError("Expected one open work item " + description + ", found " + found.size() + ": "
                    + found + all());
        }
        return found.get(0);
    }

    /** {@link #single()}, approved. */
    public WorkItem approve(Actor approver) {
        return single().approve(approver);
    }

    /** {@link #single()}, rejected. */
    public WorkItem reject(Actor approver) {
        return single().reject(approver);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private WorkItems and(BiPredicate<Approvals.WorkItem, Approvals.Case> more, String what) {
        return new WorkItems(tester, filter.and(more), description.isEmpty() ? what : description + ", " + what);
    }

    /** The open work items, each with its case, in the order of the cases. */
    private Map<Approvals.WorkItem, Approvals.Case> open() {
        Map<Approvals.WorkItem, Approvals.Case> out = new java.util.LinkedHashMap<>();
        for (Approvals.Case c : tester.deployment().api().approvals().cases()) {
            for (Approvals.WorkItem w : c.workItems()) {
                if (w.isOpen()) {
                    out.put(w, c);
                }
            }
        }
        return out;
    }

    private String all() {
        return "; all open work items: " + open().keySet().stream().map(w -> new WorkItem(tester, w).toString())
                .collect(Collectors.toList());
    }
}
