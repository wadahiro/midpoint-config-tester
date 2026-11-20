package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.RoleSelectionSpecification;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.prism.query.QueryFactory;
import com.evolveum.midpoint.schema.RelationRegistry;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AreaCategoryType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentConstraintsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RelationDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RelationKindType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.internal.AccessRequestModel;
import com.github.wadahiro.midpoint.tester.internal.AccessRequests;

/**
 * The request-access page as a user sees it ({@link Actor#requestAccess}), computed as the GUI computes it, logged in
 * as them: who they may request for, the relations offered, the catalog's entries and the objects each lists for the
 * target user, relation and type. Nothing is rendered and nothing requested: the request itself is
 * {@link Actor#request()} ({@code request(forUser).assign(role, relation)}), the model operation the page's Submit
 * runs. The page of the line:
 * <ul>
 *   <li>4.0, 4.4 (both designs): "Request a role" ({@code /self/assignmentShoppingCart}), configured by the system
 *       configuration's {@code roleManagement} ({@code roleCatalogRef}, {@code roleCatalogCollections}); any user the
 *       requester's search finds may be the target;</li>
 *   <li>4.8 and later: "Request access" ({@code /self/requestAccess}), configured by the requester's compiled GUI
 *       profile's {@code adminGuiConfiguration/accessRequest} ({@code targetSelection}, {@code relationSelection},
 *       {@code roleCatalog}; {@code roleManagement/roleCatalogRef} is not read); a user is a target the requester's
 *       search finds with the object part of their {@code #assign} authorizations.</li>
 * </ul>
 * Every entry's list is its query AND what the requester may assign to the target with the relation
 * ({@code getAssignableRoleSpecification}), searched as the requester. A role held already is still listed; its add is
 * disabled as the line and {@code roleManagement/defaultAssignmentConstraints} say ({@link #offers}). An org folder
 * lists the objects of the search panel's type, roles first ({@link #withType}). The catalog's tree and lists are read
 * across all of the GUI's pages (the GUI pages them 20 at a time; paging involves no authorization), a folder's
 * children when first asked, as the user expands it. A failed claim says which step hides the role; the requester's
 * authorizations follow. Immutable.
 */
public final class RequestAccessPage {

    private final MidPointConfigTester tester;
    private final Actor actor;
    private final AccessRequestModel model;
    private final String targetOid;
    private final String targetName;
    private final QName chosenRelation;
    private final Class<? extends AbstractRoleType> folderType;
    private final List<CatalogEntry> entries = new ArrayList<>();
    private final Map<Class<?>, RoleSelectionSpecification> specs = new HashMap<>();
    private final Map<CatalogEntry, List<ObjectRef>> listed = new HashMap<>();

    RequestAccessPage(MidPointConfigTester tester, Actor actor, String forUser) {
        this(tester, actor, open(tester, actor), forUser, null, RoleType.class);
    }

    private RequestAccessPage(MidPointConfigTester tester, Actor actor, AccessRequestModel model, String forUser,
            QName relation, Class<? extends AbstractRoleType> folderType) {
        this.tester = tester;
        this.actor = actor;
        this.model = model;
        UserType target = tester.midpoint().find(UserType.class, forUser);
        if (target == null) {
            throw new IllegalArgumentException("No user " + forUser);
        }
        this.targetOid = target.getOid();
        this.targetName = target.getName().getOrig();
        this.chosenRelation = relation;
        this.folderType = folderType;
        for (AccessRequestModel.Entry entry : model.entries) {
            entries.add(new CatalogEntry(this, entry));
        }
    }

    /** The page's URL decided as the GUI's security filter decides it, then the page's model as the requester. */
    private static AccessRequestModel open(MidPointConfigTester tester, Actor actor) {
        actor.explainAuthorizations();
        String url = tester.line().equals("4.0") || tester.line().equals("4.4") ? "/self/assignmentShoppingCart"
                : "/self/requestAccess";
        if (!actor.canOpen(url)) {
            throw new AssertionError(actor + " may not open " + url + " (the request-access page): no #selfAll or"
                    + " #selfRequestAssignments");
        }
        return AccessRequests.model(tester.midpoint(), actor.name());
    }

    /**
     * The page with this relation picked (4.8+: the relation step; 4.0, 4.4: the relation drop-down); by default the
     * configured default relation ({@link #defaultRelation}).
     */
    public RequestAccessPage withRelation(QName relation) {
        return new RequestAccessPage(tester, actor, model, targetName, relation, folderType);
    }

    /**
     * The page with the search panel's type switched, which an org folder lists the objects of ({@code RoleType} at
     * first; {@code ServiceType}, {@code OrgType}, {@code AbstractRoleType}). Collections and the fixed entries keep
     * their own type.
     */
    public RequestAccessPage withType(Class<? extends AbstractRoleType> type) {
        return new RequestAccessPage(tester, actor, model, targetName, chosenRelation, type);
    }

    /** The target user (4.8+: the person of interest), by default the requester. */
    public ObjectRef target() {
        return ObjectRef.of("UserType", targetName, targetOid);
    }

    /** The relation the page requests with: picked, else the default. */
    public QName relation() {
        return chosenRelation != null ? chosenRelation : defaultRelation();
    }

    /** The type an org folder lists ({@link #withType}). */
    public Class<? extends AbstractRoleType> type() {
        return folderType;
    }

    // ---- target selection ---------------------------------------------------------------------------------------

    /**
     * Whether the page lets the requester pick the user as the target: 4.8+ Myself unless
     * {@code allowRequestForMyself} is false; another user unless {@code allowRequestForOthers} is false and when the
     * requester's search with the object filter of their {@code #assign} authorizations finds them (the select manually
     * popup: a group's collection narrows only its tile's autocomplete, {@link #inGroup}); 4.0 and 4.4 the requester,
     * or any user their search finds.
     */
    public boolean canRequestFor(ObjectRef user) {
        return targetReason(user) == null;
    }

    /** The page lets the requester pick the user. */
    public RequestAccessPage offersTarget(ObjectRef user) {
        String reason = targetReason(user);
        if (reason != null) {
            throw new AssertionError(actor + ": the request-access page does not let " + actor + " request for "
                    + describe(user) + ": " + reason);
        }
        return this;
    }

    /** The page does not let the requester pick the user. */
    public RequestAccessPage offersNoTarget(ObjectRef user) {
        if (targetReason(user) == null) {
            throw new AssertionError(actor + ": the request-access page lets " + actor + " request for "
                    + describe(user) + (model.wizard ? " (" + (isRequester(user) ? "Myself" : "found by " + actor
                            + "'s search with the object filter of their #assign authorizations") + ")"
                            : " (any user their search finds)"));
        }
        return this;
    }

    /**
     * 4.8+: whether the group tile's autocomplete lists the user: the target filter and the group's collection (its
     * expressions evaluated for the requester). A group the configuration has not (on 4.0 and 4.4 none):
     * IllegalArgumentException.
     */
    public boolean inGroup(String groupIdentifier, ObjectRef user) {
        if (!model.groups.containsKey(groupIdentifier)) {
            throw new IllegalArgumentException(actor + ": the request-access page has no group " + groupIdentifier
                    + "; its groups: " + (model.groups.isEmpty() ? "none" : String.join(", ", model.groups.keySet())));
        }
        return model.allowOthers && found(UserType.class, model.groups.get(groupIdentifier), user);
    }

    private String targetReason(ObjectRef user) {
        if (model.wizard) {
            if (isRequester(user)) {
                return model.allowMyself ? null : "targetSelection/allowRequestForMyself is false";
            }
            if (!model.allowOthers) {
                return "targetSelection/allowRequestForOthers is false";
            }
            return found(UserType.class, model.targetFilter, user) ? null : describe(user) + " is not found by "
                    + actor + "'s search with the object filter of " + actor + "'s #assign authorizations";
        }
        if (isRequester(user)) {
            return null;
        }
        return found(UserType.class, null, user) ? null : tester.line() + " lets a user pick any user their search"
                + " finds: " + actor + "'s search does not find " + describe(user);
    }

    private boolean isRequester(ObjectRef user) {
        return user.name().equals(actor.name());
    }

    // ---- relations ----------------------------------------------------------------------------------------------

    /**
     * The relations the page offers for the target: the relations of the requester's {@code #assign} authorizations
     * for the target when none is global, else the self-service relations ({@code relationDefinitions} with the
     * category {@code selfService}); 4.8+ only the default when {@code relationSelection/allowOtherRelations} is
     * false.
     */
    public List<QName> relations() {
        RoleSelectionSpecification spec = spec(RoleType.class);
        List<QName> out = new ArrayList<>();
        if (spec != null && spec.getGlobalFilter() == null && spec.getRelationMap() != null) {
            out.addAll(spec.getRelationMap().keySet());
        }
        if (out.isEmpty()) {
            out.addAll(selfServiceRelations());
        }
        if (Boolean.FALSE.equals(model.allowOtherRelations)) {
            QName defaultRelation = defaultRelation();
            out.removeIf(r -> !QNameUtil.match(r, defaultRelation));
        }
        return Collections.unmodifiableList(out);
    }

    /** The relation the page preselects: 4.8+ {@code relationSelection/defaultRelation}, else {@code default}. */
    public QName defaultRelation() {
        return model.defaultRelation != null ? model.defaultRelation : SchemaConstants.ORG_DEFAULT;
    }

    /** RelationUtil.getCategoryRelationChoices: by the relation's kind, then its label. */
    private List<QName> selfServiceRelations() {
        List<RelationDefinitionType> defs = new ArrayList<>(
                tester.midpoint().bean(RelationRegistry.class).getRelationDefinitions());
        defs.removeIf(d -> !d.getCategory().contains(AreaCategoryType.SELF_SERVICE));
        defs.sort((a, b) -> {
            int compare = Integer.compare(kindOrder(a), kindOrder(b));
            if (compare != 0) {
                return compare;
            }
            String la = a.getDisplay() != null && a.getDisplay().getLabel() != null ? a.getDisplay().getLabel().getOrig()
                    : "";
            String lb = b.getDisplay() != null && b.getDisplay().getLabel() != null ? b.getDisplay().getLabel().getOrig()
                    : "";
            return String.CASE_INSENSITIVE_ORDER.compare(la, lb);
        });
        List<QName> out = new ArrayList<>();
        defs.forEach(d -> out.add(d.getRef()));
        return out;
    }

    private static int kindOrder(RelationDefinitionType def) {
        RelationKindType kind = def.getDefaultFor();
        if (kind == null) {
            for (RelationKindType k : def.getKind()) {
                if (kind == null || kind.ordinal() < k.ordinal()) {
                    kind = k;
                }
            }
        }
        return kind != null ? kind.ordinal() : 100;
    }

    // ---- the catalog --------------------------------------------------------------------------------------------

    /**
     * The page's entries as the requester sees them: 4.8+ the menu (org folders with their children, collections,
     * roles of teammate), 4.0 and 4.4 the tabs (the role catalog, its org with its folders; all roles, all orgs, all
     * services, user's assignments).
     */
    public List<CatalogEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * An entry, at any depth, by its label as shown, its org's or collection's name, or its key ({@code allRoles},
     * {@code roleCatalog}): the first found, the shallowest level first, in the page's order, the folders read only as
     * far as the search goes (as the user expands them); none: IllegalArgumentException naming the entries.
     */
    public CatalogEntry entry(String name) {
        CatalogEntry found = find(entries, name);
        if (found != null) {
            return found;
        }
        throw new IllegalArgumentException(actor + ": the request-access page has no entry \"" + name
                + "\"; its entries: " + entriesText());
    }

    /**
     * The entry by the name, level by level (every entry of a level before the folders' children), the folders read
     * only as far as the search goes.
     */
    private static CatalogEntry find(List<CatalogEntry> entries, String name) {
        List<CatalogEntry> level = entries;
        while (!level.isEmpty()) {
            for (CatalogEntry entry : level) {
                if (name.equals(entry.label()) || name.equals(entry.key()) || name.equals(entry.model.objectName)) {
                    return entry;
                }
            }
            List<CatalogEntry> next = new ArrayList<>();
            for (CatalogEntry entry : level) {
                next.addAll(entry.children());
            }
            level = next;
        }
        return null;
    }

    /** A folder's children as the requester reads them when expanding it ({@link CatalogEntry#children}). */
    List<AccessRequestModel.Entry> children(AccessRequestModel.Entry folder) {
        return AccessRequests.children(tester.midpoint(), actor.name(), folder);
    }

    /** Every entry, its children after it. */
    private List<CatalogEntry> all() {
        List<CatalogEntry> out = new ArrayList<>();
        collect(entries, out);
        return out;
    }

    private static void collect(List<CatalogEntry> entries, List<CatalogEntry> out) {
        for (CatalogEntry entry : entries) {
            out.add(entry);
            collect(entry.children(), out);
        }
    }

    /** The objects any entry lists for the target and relation (roles of teammate / user's assignments not). */
    public List<ObjectRef> roles() {
        List<ObjectRef> out = new ArrayList<>();
        for (CatalogEntry entry : all()) {
            for (ObjectRef role : entry.roles()) {
                if (!out.contains(role)) {
                    out.add(role);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether some entry lists the role (shown, maybe with its add disabled). */
    public boolean shows(ObjectRef role) {
        return roles().contains(role);
    }

    /** Whether some entry lists the role and lets the requester add it (not held already, per the constraints). */
    public boolean offers(ObjectRef role) {
        return shows(role) && addEnabled(role);
    }

    /** Some entry lists the role. */
    public RequestAccessPage showsRole(ObjectRef role) {
        if (!shows(role)) {
            throw new AssertionError(subject(role) + "does not show " + describe(role) + ": " + why(role));
        }
        return this;
    }

    /** No entry lists the role. */
    public RequestAccessPage showsNoRole(ObjectRef role) {
        if (shows(role)) {
            throw new AssertionError(subject(role) + "shows " + describe(role) + " in: " + listingEntries(role));
        }
        return this;
    }

    /** Some entry lists the role, its add enabled. */
    public RequestAccessPage offersRole(ObjectRef role) {
        if (!shows(role)) {
            throw new AssertionError(subject(role) + "does not show " + describe(role) + ": " + why(role));
        }
        if (!addEnabled(role)) {
            throw new AssertionError(subject(role) + "shows " + describe(role) + " but does not offer it: " + heldText(role));
        }
        return this;
    }

    /** No entry lists the role, or its add is disabled. */
    public RequestAccessPage offersNoRole(ObjectRef role) {
        if (offers(role)) {
            String held = heldAs(role);
            throw new AssertionError(subject(role) + "offers " + describe(role) + " in: " + listingEntries(role)
                    + (held != null ? " (held as " + held + ", which " + constraintsText() + " allow again)" : ""));
        }
        return this;
    }

    /** Why the role is where it is: the entries that list it, whether it is assignable, found, its add enabled. */
    public RoleVisibility explain(ObjectRef role) {
        List<CatalogEntry> listedIn = new ArrayList<>();
        QueryFactory queries = tester.midpoint().prismContext().queryFactory();
        String oid = oid(role);
        for (CatalogEntry entry : all()) {
            Class<? extends AbstractRoleType> type = type(entry);
            if (type == null) {
                continue;
            }
            ObjectFilter filter = and(queries, entry.model.filter, queries.createInOid(oid));
            if (!AccessRequests.searchAsAdministrator(tester.midpoint(), type, filter).isEmpty()) {
                listedIn.add(entry);
            }
        }
        Class<? extends AbstractRoleType> roleType = roleType(role);
        boolean assignable = !AccessRequests.searchAsAdministrator(tester.midpoint(), roleType,
                and(queries, assignableFilter(roleType), queries.createInOid(oid))).isEmpty();
        boolean found = found(roleType, null, role);
        return new RoleVisibility(listedIn, assignable, found, addEnabled(role), heldAs(role));
    }

    // ---- what the page computes ---------------------------------------------------------------------------------

    /** The objects an entry lists, as the requester (teammate: the roles of the teammate's assignments). */
    List<ObjectRef> listed(CatalogEntry entry, ObjectRef teammate) {
        if (teammate == null && listed.containsKey(entry)) {
            return listed.get(entry);
        }
        QueryFactory queries = tester.midpoint().prismContext().queryFactory();
        Class<? extends AbstractRoleType> type = type(entry);
        ObjectFilter filter = entry.model.filter;
        if (type == null) {
            if (teammate == null) {
                return List.of();
            }
            // 4.8+ updateQueryForRolesOfTeammate, 4.0 UserViewTabPanel: the teammate's assignment targets with the
            // relation, not archetypes, the teammate read as the requester
            UserType mate = AccessRequests.readAs(tester.midpoint(), actor.name(), oid(teammate));
            List<String> oids = new ArrayList<>();
            QName relation = relation();
            if (mate != null) {
                for (AssignmentType assignment : mate.getAssignment()) {
                    ObjectReferenceType ref = assignment.getTargetRef();
                    if (ref == null || ref.getOid() == null
                            || ref.getType() != null && QNameUtil.match(ArchetypeType.COMPLEX_TYPE, ref.getType())) {
                        continue;
                    }
                    boolean sameRelation = model.wizard ? QNameUtil.match(relation, ref.getRelation())
                            : relation.equals(ref.getRelation());
                    if (sameRelation) {
                        oids.add(ref.getOid());
                    }
                }
            }
            type = AbstractRoleType.class;
            filter = queries.createInOid(oids);
        }
        filter = and(queries, filter, assignableFilter(type));
        List<ObjectRef> out = new ArrayList<>();
        for (ObjectType object : AccessRequests.searchAs(tester.midpoint(), actor.name(), type, filter)) {
            out.add(ObjectRef.of(object.getClass().getSimpleName(), object.getName().getOrig(), object.getOid()));
        }
        List<ObjectRef> result = Collections.unmodifiableList(out);
        if (teammate == null) {
            listed.put(entry, result);
        }
        return result;
    }

    /** The type the entry lists: an org folder the page's type, else its own; null: roles of a teammate. */
    private Class<? extends AbstractRoleType> type(CatalogEntry entry) {
        if (entry.kind() == CatalogEntry.Kind.ROLES_OF_TEAMMATE || entry.kind() == CatalogEntry.Kind.USER_ASSIGNMENTS) {
            return null;
        }
        return entry.kind() == CatalogEntry.Kind.ORG_FOLDER ? folderType : entry.model.type;
    }

    /** WebComponentUtil.getAssignableRolesFilter: the specification's global filter OR its filter of the relation. */
    private ObjectFilter assignableFilter(Class<? extends AbstractRoleType> type) {
        RoleSelectionSpecification spec = spec(type);
        if (spec == null) {
            return null;
        }
        ObjectFilter global = spec.getGlobalFilter();
        ObjectFilter ofRelation = spec.getRelationFilter(relation());
        if (global == null) {
            return ofRelation;
        }
        if (ofRelation == null) {
            return global;
        }
        return tester.midpoint().prismContext().queryFactory().createOr(global, ofRelation);
    }

    private RoleSelectionSpecification spec(Class<? extends AbstractRoleType> type) {
        return specs.computeIfAbsent(type, t -> AccessRequests.assignable(tester.midpoint(), actor.name(), targetOid,
                type));
    }

    /**
     * Whether the add is enabled: 4.8+ RequestAccess.canAddTemplateAssignment over the target's
     * {@code roleMembershipRef}; 4.0 and 4.4 AssignmentEditorDto.isAssignable over its assignments; both with
     * {@code roleManagement/defaultAssignmentConstraints} (allowing the same target and relation unless set).
     */
    boolean addEnabled(ObjectRef role) {
        List<QName> held = heldRelations(role);
        if (held.isEmpty()) {
            return true;
        }
        AssignmentConstraintsType constraints = model.constraints;
        boolean sameTarget = constraints == null || !Boolean.FALSE.equals(constraints.isAllowSameTarget());
        boolean sameRelation = constraints == null || !Boolean.FALSE.equals(constraints.isAllowSameRelation());
        if (model.heldByMembership) {
            if (!sameTarget) {
                return false;
            }
            if (sameRelation) {
                return true;
            }
            QName relation = relation();
            return held.stream().noneMatch(r -> QNameUtil.match(r == null ? SchemaConstants.ORG_DEFAULT : r, relation));
        }
        if (constraints == null || sameTarget && sameRelation) {
            return true;
        }
        if (sameTarget) {
            long administration = tester.midpoint().bean(RelationRegistry.class).getRelationDefinitions().stream()
                    .filter(d -> d.getCategory().contains(AreaCategoryType.ADMINISTRATION)).count();
            return held.size() < administration;
        }
        return false;
    }

    /** The relations the target holds the role with (4.8+ roleMembershipRef, 4.0 and 4.4 assignment). */
    private List<QName> heldRelations(ObjectRef role) {
        String oid = oid(role);
        // the requester's own user as the principal has it; another as the requester gets them
        UserType target = targetOid.equals(oidOfActor()) ? tester.midpoint().find(UserType.class, targetOid)
                : AccessRequests.readAs(tester.midpoint(), actor.name(), targetOid);
        List<QName> out = new ArrayList<>();
        if (target == null) {
            return out;
        }
        if (model.heldByMembership) {
            for (ObjectReferenceType ref : target.getRoleMembershipRef()) {
                if (oid.equals(ref.getOid())) {
                    out.add(ref.getRelation());
                }
            }
        } else {
            for (AssignmentType assignment : target.getAssignment()) {
                if (assignment.getTargetRef() != null && oid.equals(assignment.getTargetRef().getOid())) {
                    out.add(assignment.getTargetRef().getRelation());
                }
            }
        }
        return out;
    }

    private String heldAs(ObjectRef role) {
        List<QName> held = heldRelations(role);
        if (held.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        held.forEach(r -> names.add(r == null ? "default" : r.getLocalPart()));
        return String.join(", ", names);
    }

    private String oidOfActor() {
        UserType user = tester.midpoint().find(UserType.class, actor.name());
        return user != null ? user.getOid() : null;
    }

    private boolean found(Class<? extends ObjectType> type, ObjectFilter filter, ObjectRef object) {
        QueryFactory queries = tester.midpoint().prismContext().queryFactory();
        return !AccessRequests.searchAs(tester.midpoint(), actor.name(), type,
                and(queries, filter, queries.createInOid(oid(object)))).isEmpty();
    }

    private static ObjectFilter and(QueryFactory queries, ObjectFilter a, ObjectFilter b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return queries.createAnd(a, b);
    }

    private String oid(ObjectRef object) {
        if (object.oid() != null) {
            return object.oid();
        }
        ObjectType found = tester.midpoint().find(Actor.typeOf(object), object.on(tester.line()));
        if (found == null) {
            throw new IllegalArgumentException("No " + object);
        }
        return found.getOid();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AbstractRoleType> roleType(ObjectRef role) {
        Class<?> type = Actor.typeOf(role);
        if (!AbstractRoleType.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(role + " is no role, org or service");
        }
        return (Class<? extends AbstractRoleType>) type;
    }

    // ---- failures -----------------------------------------------------------------------------------------------

    /** {@code jack: the request-access page for jack (relation default) }. */
    private String subject(ObjectRef role) {
        return actor + ": the request-access page for " + targetName + " (relation " + relation().getLocalPart()
                + (folderType != RoleType.class ? ", folders of " + folderType.getSimpleName() : "") + ") ";
    }

    /** Which step hides the role. */
    private String why(ObjectRef role) {
        RoleVisibility v = explain(role);
        if (v.listedIn().isEmpty()) {
            return "no entry lists it; its entries: " + entriesText() + " (the configuration is " + model.configuration
                    + ")";
        }
        if (!v.assignable()) {
            return "it is in the catalog (" + v.listedIn() + "), but " + actor + "'s assign authorizations do not allow"
                    + " assigning it to " + targetName + " with relation " + relation().getLocalPart();
        }
        if (!v.foundByRequester()) {
            return "it is in the catalog (" + v.listedIn() + ") and assignable, but " + actor + "'s search of "
                    + roleType(role).getSimpleName() + " does not find it (#search or #read)";
        }
        return "it is in the catalog (" + v.listedIn() + "), assignable and found by " + actor
                + ", but no entry's search as " + actor + " lists it";
    }

    private String listingEntries(ObjectRef role) {
        List<String> out = new ArrayList<>();
        for (CatalogEntry entry : all()) {
            if (entry.roles().contains(role)) {
                out.add(entry.toString());
            }
        }
        return String.join(", ", out);
    }

    private String heldText(ObjectRef role) {
        return targetName + " holds it already (" + heldAs(role) + ") and " + constraintsText() + " do not allow it"
                + " again (" + (model.heldByMembership ? "4.8 and later: its roleMembershipRef" : "its assignments")
                + ")";
    }

    private String constraintsText() {
        AssignmentConstraintsType c = model.constraints;
        return c == null ? "no roleManagement/defaultAssignmentConstraints"
                : "roleManagement/defaultAssignmentConstraints (allowSameTarget " + c.isAllowSameTarget()
                        + ", allowSameRelation " + c.isAllowSameRelation() + ")";
    }

    private String entriesText() {
        List<String> out = new ArrayList<>();
        for (CatalogEntry entry : all()) {
            out.add(entry.toString());
        }
        return out.isEmpty() ? "none" : String.join(", ", out);
    }

    private static String describe(ObjectRef object) {
        String type = object.type();
        return (type.endsWith("Type") ? type.substring(0, type.length() - 4) : type) + " " + object.name();
    }

    @Override
    public String toString() {
        return "the request-access page of " + actor + " for " + targetName + " (relation "
                + relation().getLocalPart() + "): " + entriesText();
    }
}
