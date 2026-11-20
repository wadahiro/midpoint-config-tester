package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.QueryFactory;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AreaCategoryType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentHolderType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.common.MidPointLine;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.internal.MemberOperations;
import com.github.wadahiro.midpoint.tester.internal.MemberTask;

/**
 * A members panel as a user sees it ({@link Actor#membersPanel}, {@link Actor#governancePanel},
 * {@link Actor#orgTreeMembersPanel}): whether the GUI shows the user its Assign and Unassign, which relations and
 * types it offers, and its operations run as the user, the way the line's GUI runs them. Three panels run the same
 * code: the Members and the Governance panel of a role's, org's or service's details page, and the members panel of
 * an org selected in the organization tree. Assign, and an Unassign of selected members or of all, start a task owned
 * by the user (waited for here); an Unassign from a member's row is the GUI's own change on 4.4 and later. Each
 * operation returns what midPoint did ({@link Effects}, as {@link Actor#modify}), without the GUI's task; what
 * midPoint refused fails the call ({@link MidPointConfigTester#expectFailure}) with midPoint's message (the task's
 * last failure for a task). What the GUI does not offer the user (a hidden button, a relation or type the panel has
 * not, a member the user's search does not find) is an {@code AssertionError}. A failed claim lists the user's
 * authorizations.
 *
 * <pre>{@code
 * t.actor("site-admin").orgTreeMembersPanel(t.org("site-1")).assign(SchemaConstants.ORG_MANAGER, Users.JACK);
 * t.user(Users.JACK).hasAssignment("site-1", SchemaConstants.ORG_MANAGER);
 * t.actor("site-admin").orgTreeMembersPanel(t.org("site-1")).unassign(SchemaConstants.ORG_MANAGER, Users.JACK);
 * t.actor("helpdesk").governancePanel(t.org("site-1")).unassignButton().isHidden();
 * t.expectFailure(() -> t.actor("ui-only").membersPanel(t.org("site-1")).assign(Users.JACK));
 * }</pre>
 *
 * <p>Per line: the buttons are shown as the GUI's {@code WebComponentUtil.isAuthorized} decides them, by Wicket's roles
 * of the session (an authorization that names the panel's action, {@code #all} or {@code #guiAll}, a deny too, any
 * object). The action is the panel's: {@code #adminAssignOrgMember} / {@code #adminUnassignOrgMember} on an org's
 * panels, {@code #adminAssignMember} / {@code #adminUnassignMember} on a role's or service's; the Governance panel of
 * 4.0 has {@code #adminAssignGovernance} / {@code #adminUnassignGovernance}, of 4.4 and later the Members panel's. The
 * task is checked when it starts: 4.0 and 4.4 {@code #executeScript}, 4.8 and later
 * {@code authorization-bulk-3#assign} or {@code #unassign} (or {@code authorization-bulk-3#all}) in the execution
 * phase; it runs as the user, whose model authorizations ({@code #assign}, {@code #unassign}) then apply to each
 * member.</p>
 *
 * <p>Not modeled: the panel's {@code searchBoxConfiguration} (4.4+: relations and types it may narrow), the
 * relation-specific buttons of the Assign popup (an archetype's {@code assignmentRelation}), its "Other types" tab of
 * an org, indirect members and the organization tree's subtree scope, the 4.4 GUI with {@code useNewDesign} false.</p>
 */
public final class MembersPanel {

    /** Which panel: the details page's Members or Governance panel, or the organization tree's members panel. */
    public enum Kind {
        /** The Members panel of a role's, org's or service's page: the default relation. */
        MEMBERS,
        /** The Governance panel of a role's, org's or service's page: manager, approver, owner. */
        GOVERNANCE,
        /** The members panel of an org selected in the organization tree: default, manager, approver, owner. */
        ORG_TREE
    }

    private static final String UI = "http://midpoint.evolveum.com/xml/ns/public/security/authorization-ui-3#";

    private final MidPointConfigTester tester;
    private final Actor actor;
    private final AbstractRole<?> role;
    private final Kind kind;

    MembersPanel(MidPointConfigTester tester, Actor actor, AbstractRole<?> role, Kind kind) {
        if (kind == Kind.ORG_TREE && !OrgType.class.equals(role.type)) {
            throw new IllegalArgumentException("The organization tree has orgs only, not " + role.kind + " "
                    + role.name);
        }
        this.tester = tester;
        this.actor = actor;
        this.role = role;
        this.kind = kind;
    }

    /** Members, Governance or the organization tree's members panel. */
    public Kind kind() {
        return kind;
    }

    // ---- what the panel offers ----------------------------------------------------------------------------------

    /**
     * The relations the panel's Assign popup and Unassign dialog offer, in the GUI's order, from the deployment's
     * relation definitions (the same rule on every line): Members the {@code ADMINISTRATION} relations without the
     * {@code GOVERNANCE} ones ({@code default}), Governance the {@code GOVERNANCE} ones ({@code manager, approver,
     * owner}), the organization tree the {@code ORGANIZATION} ones ({@code default, manager, approver, owner}).
     */
    public List<QName> relations() {
        switch (kind) {
            case MEMBERS:
                List<QName> out = MemberOperations.relations(tester.deployment().api(),
                        AreaCategoryType.ADMINISTRATION);
                List<QName> governance = MemberOperations.relations(tester.deployment().api(),
                        AreaCategoryType.GOVERNANCE);
                out.removeIf(r -> contains(governance, r));
                return out;
            case GOVERNANCE:
                return MemberOperations.relations(tester.deployment().api(), AreaCategoryType.GOVERNANCE);
            default:
                return MemberOperations.relations(tester.deployment().api(), AreaCategoryType.ORGANIZATION);
        }
    }

    /** The types the Assign popup has a tab for: User, Role, Org, Service, and on 4.9 and later Policy. */
    @SuppressWarnings("unchecked")
    public List<Class<? extends AssignmentHolderType>> memberTypes() {
        List<Class<? extends AssignmentHolderType>> out = new ArrayList<>(
                Arrays.asList(UserType.class, RoleType.class, OrgType.class, ServiceType.class));
        if (MidPointLine.compare(tester.line(), "4.9") >= 0) {
            try {
                out.add((Class<? extends AssignmentHolderType>) Class.forName(
                        "com.evolveum.midpoint.xml.ns._public.common.common_3.PolicyType"));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("No PolicyType on " + tester.line(), e);
            }
        }
        return out;
    }

    // ---- the buttons as the user sees them ------------------------------------------------------------------------

    /**
     * The Assign of the panel (its toolbar button and header menu item). On 4.0 the toolbar's Assign, the icon right of
     * the "+", is shown to every user who opens the panel, as the "+" is (4.0 AbstractRoleMemberPanel
     * createToolbarButtonsList); only the header menu's Assign needs the action. A user without the authorizations then
     * fails when the task starts or on each member.
     */
    public Button assignButton() {
        return new Button("Assign", action(true), tester.line().equals("4.0"));
    }

    /** The Unassign of the panel (its toolbar button, header menu item and, on 4.4 and later, a row's menu). */
    public Button unassignButton() {
        return new Button("Unassign", action(false), false);
    }

    /**
     * A button of the panel as the user sees it: the GUI shows it when an authorization of the user names its action,
     * {@code #all} or {@code #guiAll} (Wicket's roles of the session, whatever the authorization's decision and
     * object), or, 4.0's toolbar Assign, to every user.
     */
    public final class Button {

        private final String label;
        private final String action;
        private final boolean toEveryone;

        private Button(String label, String action, boolean toEveryone) {
            this.label = label;
            this.action = action;
            this.toEveryone = toEveryone;
        }

        /**
         * The GUI action that shows it on this line and panel ({@code ...authorization-ui-3#adminAssignOrgMember}); on
         * 4.0 the Assign's shows only the header menu's.
         */
        public String action() {
            return action;
        }

        /** Whether the GUI shows the user the button. */
        public boolean shown() {
            return toEveryone || !actor.naming(action).isEmpty();
        }

        /** The GUI shows the user the button. */
        public Button isShown() {
            if (!shown()) {
                throw new AssertionError(actor + " does not see " + label + " on " + panel()
                        + ": no authorization names " + fragment(action) + ", #all or #guiAll");
            }
            return this;
        }

        /** The GUI does not show the user the button. */
        public Button isHidden() {
            if (toEveryone) {
                throw new AssertionError(actor + " sees " + label + " on " + panel() + ": on " + tester.line()
                        + " the toolbar's " + label + " is shown to every user (only the header menu's needs "
                        + fragment(action) + ")");
            }
            List<String> naming = new ArrayList<>();
            for (AuthorizationType authorization : actor.naming(action)) {
                naming.add(Actor.describe(authorization));
            }
            if (!naming.isEmpty()) {
                throw new AssertionError(actor + " sees " + label + " on " + panel() + " (" + String.join("; ", naming)
                        + ")");
            }
            return this;
        }

        @Override
        public String toString() {
            return label + " of " + panel() + " as " + actor;
        }
    }

    // ---- the operations, as the user ------------------------------------------------------------------------------

    /**
     * Assigns the members to the panel's object with the relation, as the user does from the Assign popup: each
     * selected on the tab of its type (one the user's search finds), the relation picked (one the panel offers), Add.
     * The GUI starts a task owned by the user for each tab with a selection; the call waits for them and returns
     * their effects. Fails (AssertionError) when the user does not see Assign, the panel has not the relation or a tab
     * of a member's type, or the user's search does not find a member; fails
     * ({@link MidPointConfigTester#expectFailure}) when the line refuses to start the task (4.0, 4.4
     * {@code #executeScript}; 4.8+ {@code authorization-bulk-3#assign}) or the task fails on a member (the model's
     * {@code #assign}; on 4.11 also the expression profile of the user's bulk actions).
     */
    public Effects assign(QName relation, ObjectRef... members) {
        return assign(relation, resolve(members));
    }

    public Effects assign(QName relation, Focus<?>... members) {
        return assign(relation, resolve(members));
    }

    /** {@link #assign(QName, ObjectRef...)} with the relation {@code default} (the Members panel's only one). */
    public Effects assign(ObjectRef... members) {
        return assign(SchemaConstants.ORG_DEFAULT, resolve(members));
    }

    private Effects assign(QName relation, List<Member> members) {
        actor.explainAuthorizations();
        assignButton().isShown();
        QName picked = offered(relation);
        List<Class<? extends AssignmentHolderType>> tabs = memberTypes();
        Map<Class<? extends AssignmentHolderType>, List<Member>> byTab = new LinkedHashMap<>();
        for (Class<? extends AssignmentHolderType> tab : tabs) {
            byTab.put(tab, new ArrayList<>());
        }
        for (Member member : members) {
            if (!byTab.containsKey(member.type)) {
                throw new AssertionError(actor + ": the Assign popup of " + panel() + " has no tab for "
                        + member.type.getSimpleName() + "; its tabs: " + names(tabs));
            }
            byTab.get(member.type).add(member);
        }
        List<MemberTask> tasks = new ArrayList<>();
        for (Map.Entry<Class<? extends AssignmentHolderType>, List<Member>> tab : byTab.entrySet()) {
            if (tab.getValue().isEmpty()) {
                continue;
            }
            ObjectQuery query = inOid(tab.getValue());
            selectable(tab.getKey(), query, tab.getValue(), "select", "in the Assign popup of " + panel());
            tasks.add(new MemberTask(true, target(), typeName(tab.getKey()), query, List.of(picked), null));
        }
        String label = "assign " + describe(members) + " to " + role.kind + " " + role.name + " as "
                + picked.getLocalPart() + " from " + panel();
        return tester.effectsInGui(() -> {
            for (MemberTask task : tasks) {
                MemberOperations.submit(tester.deployment().api(), actor.name(), label, task);
            }
        });
    }

    /**
     * Unassigns the member with the relation as the user does from the member's row: on 4.4 and later its menu's
     * Unassign, confirmed, which deletes the member's assignments to the panel's object through the model as the
     * user, no task; when the member has several of the panel's relations (its {@code roleMembershipRef} as the user
     * reads it) the GUI asks which, and then starts a task as {@link #unassignSelected} does. 4.0 has no row menu on
     * a members panel: the user selects the row and uses the panel's Unassign, a task with the relation
     * ({@link #unassignSelected}). Fails (AssertionError) when the user does not see Unassign, the user's search does
     * not find the member, its row has no Unassign (4.4+: no direct assignment to the object as the user reads the
     * member), or the GUI would delete other assignments than those with the relation (4.4+: without the
     * question it deletes every assignment of the member to the object).
     */
    public Effects unassign(QName relation, ObjectRef member) {
        return unassign(relation, resolve(member));
    }

    public Effects unassign(QName relation, Focus<?> member) {
        return unassign(relation, resolve(member));
    }

    /** {@link #unassign(QName, ObjectRef)} with the one relation the member has in this panel (fails if several). */
    public Effects unassign(ObjectRef member) {
        return unassign(null, resolve(member));
    }

    public Effects unassign(Focus<?> member) {
        return unassign(null, resolve(member));
    }

    private Effects unassign(QName relation, Member member) {
        actor.explainAuthorizations();
        unassignButton().isShown();
        QName given = relation != null ? offered(relation) : null;
        if (tester.line().equals("4.0")) {
            QName picked = given != null ? given : onlyRelation(member);
            return unassignTask(List.of(member), List.of(picked), "SELECTED",
                    "unassign " + member + " as " + picked.getLocalPart() + " from " + panel());
        }
        AssignmentHolderType read = asRead(member);
        List<AssignmentType> direct = new ArrayList<>();
        for (AssignmentType assignment : read.getAssignment()) {
            if (assignment.getTargetRef() != null && role.oid().equals(assignment.getTargetRef().getOid())) {
                direct.add(assignment);
            }
        }
        if (direct.isEmpty()) {
            throw new AssertionError(actor + ": the row of " + member + " on " + panel() + " has no Unassign: "
                    + member.name + "'s assignments to " + role.name + " as " + actor + " reads them: none");
        }
        List<QName> available = new ArrayList<>();
        List<QName> all = new ArrayList<>();
        List<QName> offered = relations();
        for (ObjectReferenceType membership : read.getRoleMembershipRef()) {
            if (role.oid().equals(membership.getOid())) {
                all.add(membership.getRelation());
                if (contains(offered, membership.getRelation())) {
                    available.add(membership.getRelation());
                }
            }
        }
        available = inPanelOrder(available);
        if (available.size() > 1) {
            // the GUI asks which of them, then starts a task over the row (the scope of no selection)
            if (given == null || !contains(available, given)) {
                throw new AssertionError(actor + ": the Unassign of the row of " + member + " on " + panel()
                        + " asks which relation to unassign: " + locals(available)
                        + (given == null ? "; give one" : "; not " + given.getLocalPart()));
            }
            return unassignTask(List.of(member), List.of(given), "ALL_DIRECT",
                    "unassign " + member + " as " + given.getLocalPart() + " from " + panel());
        }
        QName used = available.size() == 1 && all.size() > 1 ? available.get(0) : null;
        List<AssignmentType> deleted = new ArrayList<>();
        for (AssignmentType assignment : direct) {
            if (used == null || QNameUtil.match(used, assignment.getTargetRef().getRelation())) {
                deleted.add(assignment);
            }
        }
        List<QName> deletedRelations = new ArrayList<>();
        for (AssignmentType assignment : deleted) {
            deletedRelations.add(relation(assignment.getTargetRef().getRelation()));
        }
        if (given != null && (!contains(deletedRelations, given) || deletedRelations.stream()
                .anyMatch(r -> !QNameUtil.match(r, given)))) {
            throw new AssertionError(actor + ": the Unassign of the row of " + member + " on " + panel()
                    + " deletes its assignments to " + role.name + " with " + locals(deletedRelations) + ", not "
                    + given.getLocalPart() + " alone");
        }
        PrismContext prism = tester.deployment().api().prismContext();
        List<ObjectDelta<? extends ObjectType>> deltas = new ArrayList<>();
        for (AssignmentType assignment : deleted) {
            // AbstractRoleMemberPanel.executeUnassign: one change per assignment, through the model as the user
            try {
                deltas.add(prism.deltaFor(member.type).item(AssignmentHolderType.F_ASSIGNMENT)
                        .delete(assignment.clone()).asObjectDelta(member.oid));
            } catch (com.evolveum.midpoint.util.exception.SchemaException e) {
                throw new IllegalStateException("Cannot unassign " + assignment, e);
            }
        }
        return tester.effectsInGui(() -> {
            for (ObjectDelta<? extends ObjectType> delta : deltas) {
                tester.deployment().api().approvals().request(actor.name(), delta);
            }
        });
    }

    /**
     * Unassigns the selected members as the user does with their rows selected and the panel's Unassign, confirmed:
     * a task owned by the user over the selection, waited for. Which relations go is the GUI's: on 4.4 and later
     * every relation of the panel, unless a single selected member has several of them, when the GUI asks which (the
     * relation given here); on 4.0 the dialog's choice, the relation given or else every relation of the panel. Fails
     * as {@link #assign} does, with {@code authorization-bulk-3#unassign} (4.8+) and the model's {@code #unassign};
     * and when the relation given is not what the GUI unassigns.
     */
    public Effects unassignSelected(QName relation, ObjectRef... members) {
        return unassignSelected(relation, resolve(members));
    }

    public Effects unassignSelected(QName relation, Focus<?>... members) {
        return unassignSelected(relation, resolve(members));
    }

    /** {@link #unassignSelected(QName, ObjectRef...)} with the relations the GUI picks. */
    public Effects unassignSelected(ObjectRef... members) {
        return unassignSelected(null, resolve(members));
    }

    public Effects unassignSelected(Focus<?>... members) {
        return unassignSelected(null, resolve(members));
    }

    private Effects unassignSelected(QName relation, List<Member> members) {
        actor.explainAuthorizations();
        unassignButton().isShown();
        QName given = relation != null ? offered(relation) : null;
        List<QName> offered = relations();
        List<QName> relations;
        if (tester.line().equals("4.0")) {
            relations = given != null ? List.of(given) : offered;
        } else {
            List<QName> available = new ArrayList<>();
            if (members.size() == 1) {
                AssignmentHolderType read = readByUser(members.get(0));
                for (ObjectReferenceType membership : read != null ? read.getRoleMembershipRef()
                        : List.<ObjectReferenceType>of()) {
                    if (role.oid().equals(membership.getOid()) && contains(offered, membership.getRelation())) {
                        available.add(membership.getRelation());
                    }
                }
            }
            available = inPanelOrder(available);
            if (available.size() > 1) {
                if (given == null || !contains(available, given)) {
                    throw new AssertionError(actor + ": the Unassign of the selected " + members.get(0) + " on "
                            + panel() + " asks which relation to unassign: " + locals(available)
                            + (given == null ? "; give one" : "; not " + given.getLocalPart()));
                }
                relations = List.of(given);
            } else {
                if (given != null && !(offered.size() == 1 && QNameUtil.match(offered.get(0), given))) {
                    throw new AssertionError(actor + ": the Unassign of the selected members on " + panel()
                            + " does not ask for a relation: it unassigns every relation of the panel ("
                            + locals(offered) + "); call unassignSelected without a relation");
                }
                relations = offered;
            }
        }
        return unassignTask(members, relations, "SELECTED", "unassign " + describe(members) + " as "
                + locals(relations) + " from " + panel());
    }

    /**
     * Unassigns all direct members of the type with the relations, as the user does with the panel's type set to it
     * and nothing selected: the panel's Unassign, the dialog's type and relations, then a task owned by the user over
     * the members that have an assignment to the object with one of the relations. No relation given: the dialog's
     * default, every relation of the panel (4.4+: the panel's relation filter, which is "any" unless the panel has one
     * relation; 4.0 preselects none, here every one). Indirect members and the organization tree's subtree are not
     * modeled.
     */
    public Effects unassignAll(Class<? extends AssignmentHolderType> type, QName... relations) {
        actor.explainAuthorizations();
        unassignButton().isShown();
        List<QName> picked = new ArrayList<>();
        for (QName relation : relations) {
            picked.add(offered(relation));
        }
        if (picked.isEmpty()) {
            picked.addAll(relations());
        }
        QName typeName = typeName(type);
        PrismContext prism = tester.deployment().api().prismContext();
        List<PrismReferenceValue> targets = new ArrayList<>();
        for (QName relation : picked) {
            targets.add(new ObjectReferenceType().oid(role.oid()).type(typeName(role.type)).relation(relation)
                    .asReferenceValue());
        }
        // MemberOperationsQueryUtil.createDirectMemberQuery (4.8; MemberOperationsHelper on 4.0 and 4.4)
        ObjectQuery query = AssignmentHolderType.class.equals(type)
                ? prism.queryFor(AssignmentHolderType.class).exists(AssignmentHolderType.F_ASSIGNMENT).block()
                        .item(AssignmentType.F_TARGET_REF).ref(targets).endBlock().build()
                : prism.queryFor(AssignmentHolderType.class).type(typeName).exists(AssignmentHolderType.F_ASSIGNMENT)
                        .block().item(AssignmentType.F_TARGET_REF).ref(targets).endBlock().build();
        MemberTask task = new MemberTask(false, target(), typeName, query, picked, "ALL_DIRECT");
        String label = "unassign all direct " + type.getSimpleName() + " members as " + locals(picked) + " from "
                + panel();
        return tester.effectsInGui(() -> MemberOperations.submit(tester.deployment().api(), actor.name(), label,
                task));
    }

    /** {@link #unassignAll(Class, QName...)} of the panel's default type (User; 4.0: Focus on a role or service). */
    public Effects unassignAll(QName... relations) {
        return unassignAll(searchType(), relations);
    }

    @Override
    public String toString() {
        return panel() + " as " + actor;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A member: its type, OID and name. */
    private static final class Member {
        final Class<? extends AssignmentHolderType> type;
        final String oid;
        final String name;

        Member(Class<? extends AssignmentHolderType> type, String oid, String name) {
            this.type = type;
            this.oid = oid;
            this.name = name;
        }

        @Override
        public String toString() {
            return type.getSimpleName().replaceAll("Type$", "") + " " + name;
        }
    }

    private List<Member> resolve(ObjectRef... refs) {
        List<Member> out = new ArrayList<>();
        for (ObjectRef ref : refs) {
            out.add(resolve(ref));
        }
        return out;
    }

    private Member resolve(ObjectRef ref) {
        String key = ref.oid() != null ? ref.oid() : ref.on(tester.line());
        ObjectType object = tester.deployment().api().find(Actor.typeOf(ref), key);
        if (object == null) {
            throw new AssertionError("No " + ref.type() + " " + key);
        }
        return member(object);
    }

    private List<Member> resolve(Focus<?>... focuses) {
        List<Member> out = new ArrayList<>();
        for (Focus<?> focus : focuses) {
            out.add(resolve(focus));
        }
        return out;
    }

    private Member resolve(Focus<?> focus) {
        return member(focus.existing());
    }

    private static Member member(ObjectType object) {
        if (!(object instanceof AssignmentHolderType)) {
            throw new IllegalArgumentException("Not an assignment holder: " + object);
        }
        return new Member(((AssignmentHolderType) object).getClass(), object.getOid(), object.getName().getOrig());
    }

    /** A task over the members: the user's search of the panel's type must find them (else the task finds none). */
    private Effects unassignTask(List<Member> members, List<QName> relations, String scope, String label) {
        Class<? extends AssignmentHolderType> type = searchType();
        ObjectQuery query = inOid(members);
        selectable(type, query, members, "unassign", "from " + panel());
        MemberTask task = new MemberTask(false, target(), typeName(type), query, relations, scope);
        return tester.effectsInGui(() -> MemberOperations.submit(tester.deployment().api(), actor.name(), label,
                task));
    }

    /**
     * The type the panel searches its members by, which the GUI's unassign task takes: User (the panel's default type
     * on 4.4 and later; 4.0's on an org); 4.0's Focus on a role or service.
     */
    private Class<? extends AssignmentHolderType> searchType() {
        return tester.line().equals("4.0") && !OrgType.class.equals(role.type) ? FocusType.class : UserType.class;
    }

    /** The members are among what the user's search of the type with the query finds. */
    private void selectable(Class<? extends AssignmentHolderType> type, ObjectQuery query, List<Member> members,
            String verb, String where) {
        List<String> found = new ArrayList<>();
        for (Object object : tester.midpoint().actor(actor.name()).search(type, query).value()) {
            found.add(((ObjectType) object).getOid());
        }
        List<String> missing = new ArrayList<>();
        for (Member member : members) {
            if (!found.contains(member.oid)) {
                missing.add(member.toString());
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError(actor + " cannot " + verb + " " + String.join(", ", missing) + " " + where
                    + ": a search by " + actor + " of " + type.getSimpleName() + " does not find "
                    + (missing.size() == 1 ? "it" : "them") + " (#search or #read)");
        }
    }

    /** The member as the user reads it (the row of the panel), or null if the user's search does not find it. */
    private AssignmentHolderType readByUser(Member member) {
        List<? extends ObjectType> found = tester.midpoint().actor(actor.name()).search(member.type, inOid(
                List.of(member))).value();
        return found.isEmpty() ? null : (AssignmentHolderType) found.get(0);
    }

    private AssignmentHolderType asRead(Member member) {
        AssignmentHolderType read = readByUser(member);
        if (read == null) {
            throw new AssertionError(actor + ": " + panel() + " has no row of " + member + ": a search by " + actor
                    + " of " + member.type.getSimpleName() + " does not find it (#search or #read)");
        }
        return read;
    }

    /** The one relation of the panel the member has (its assignments to the object), for a call without one. */
    private QName onlyRelation(Member member) {
        AssignmentHolderType object = (AssignmentHolderType) tester.deployment().api().find(member.type, member.oid);
        List<QName> offered = relations();
        List<QName> has = new ArrayList<>();
        for (AssignmentType assignment : object.getAssignment()) {
            if (assignment.getTargetRef() != null && role.oid().equals(assignment.getTargetRef().getOid())
                    && contains(offered, relation(assignment.getTargetRef().getRelation()))) {
                has.add(relation(assignment.getTargetRef().getRelation()));
            }
        }
        has = inPanelOrder(has);
        if (has.size() != 1) {
            throw new AssertionError(actor + ": " + member + " has " + (has.isEmpty() ? "no relation"
                    : "the relations " + locals(has)) + " in " + panel() + "; give the one to unassign");
        }
        return has.get(0);
    }

    /** The relation as the panel offers it (null: default), or an AssertionError naming the panel's. */
    private QName offered(QName relation) {
        QName wanted = relation(relation);
        for (QName offered : relations()) {
            if (QNameUtil.match(offered, wanted)) {
                return offered;
            }
        }
        throw new AssertionError(actor + ": " + panel() + " does not offer relation " + wanted.getLocalPart()
                + "; it offers " + locals(relations()));
    }

    /** The relations in the order of the panel's ({@link #relations()}), each once. */
    private List<QName> inPanelOrder(List<QName> relations) {
        List<QName> out = new ArrayList<>();
        for (QName offered : relations()) {
            if (contains(relations, offered)) {
                out.add(offered);
            }
        }
        return out;
    }

    private static QName relation(QName relation) {
        return relation != null ? relation : SchemaConstants.ORG_DEFAULT;
    }

    private static boolean contains(List<QName> relations, QName relation) {
        for (QName r : relations) {
            if (QNameUtil.match(r, relation(relation))) {
                return true;
            }
        }
        return false;
    }

    private static String locals(List<QName> relations) {
        List<String> out = new ArrayList<>();
        for (QName relation : relations) {
            out.add(relation(relation).getLocalPart());
        }
        return String.join(", ", out);
    }

    private static String names(List<Class<? extends AssignmentHolderType>> types) {
        List<String> out = new ArrayList<>();
        for (Class<?> type : types) {
            out.add(type.getSimpleName());
        }
        return String.join(", ", out);
    }

    private static String describe(List<Member> members) {
        List<String> out = new ArrayList<>();
        for (Member member : members) {
            out.add(member.toString());
        }
        return String.join(", ", out);
    }

    private ObjectQuery inOid(List<Member> members) {
        QueryFactory queries = tester.deployment().api().prismContext().queryFactory();
        List<String> oids = new ArrayList<>();
        for (Member member : members) {
            oids.add(member.oid);
        }
        return queries.createQuery(queries.createInOid(oids));
    }

    private com.evolveum.midpoint.prism.PrismObject<? extends AbstractRoleType> target() {
        return ((AbstractRoleType) role.existing()).asPrismObject();
    }

    private QName typeName(Class<? extends ObjectType> type) {
        return tester.deployment().api().prismContext().getSchemaRegistry().findObjectDefinitionByCompileTimeClass(
                type).getTypeName();
    }

    /** The panel's action of Assign or Unassign on this line ({@code GuiAuthorizationConstants}). */
    private String action(boolean assign) {
        if (kind == Kind.GOVERNANCE && tester.line().equals("4.0")) {
            // 4.0: the Governance tab's own map (AbstractRoleMainPanel.getGovernanceTabAuthorizations)
            return UI + (assign ? "adminAssignGovernance" : "adminUnassignGovernance");
        }
        // AbstractRoleMemberPanel's map by the object's type (4.4+: every panel; the org tree's on every line)
        if (OrgType.class.equals(role.type)) {
            return UI + (assign ? "adminAssignOrgMember" : "adminUnassignOrgMember");
        }
        return UI + (assign ? "adminAssignMember" : "adminUnassignMember");
    }

    /** {@code the Governance panel of Org site-1}. */
    private String panel() {
        switch (kind) {
            case MEMBERS:
                return "the Members panel of " + role.kind + " " + role.name;
            case GOVERNANCE:
                return "the Governance panel of " + role.kind + " " + role.name;
            default:
                return "the members panel of " + role.kind + " " + role.name + " in the organization tree";
        }
    }

    private static String fragment(String action) {
        return action.substring(action.lastIndexOf('#'));
    }
}
