package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.model.api.authentication.CompiledGuiProfile;
import com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView;
import com.evolveum.midpoint.model.api.authentication.GuiProfiledPrincipal;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.prism.query.QueryFactory;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.repo.common.expression.ExpressionFactory;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.security.api.SecurityUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AccessRequestType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.GroupSelectionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RelationSelectionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleCatalogType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleCollectionViewType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleManagementConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RolesOfTeammateType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TargetSelectionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * The request-access page of 4.8 and later: the wizard of PageRequestAccess ({@code /self/requestAccess}) as the
 * logged-in user's compiled GUI profile's {@code accessRequest} makes it ({@link AccessRequestModel}); 4.8 to 4.11 have
 * the same code here.
 * <ul>
 *   <li>persons of interest (PersonOfInterestPanel): Myself unless {@code targetSelection/allowRequestForMyself} is
 *       false, Others unless {@code allowRequestForOthers} is: the users the requester's search finds with the object
 *       part of their {@code #assign} authorizations ({@code getAccessibleForAssignmentObjectsFilter}: the select
 *       manually popup); a group's tile ANDs its collection, compiled as the user, its expressions evaluated
 *       (createObjectFilterFromGroupSelection);</li>
 *   <li>relations (RequestAccess.getAvailableRelations, findDefaultRelation): {@code relationSelection};</li>
 *   <li>the menu (RoleCatalogPanel.loadRoleCatalogMenu): the orgs under {@code roleCatalog/roleCatalogRef} one level
 *       at a time down to {@code roleCatalogDepth} (default 3), searched as the user, by display name and name, a
 *       level read when its parent is opened (loadMenuFromOrgTree's LoadableModel: AccessRequests.children), each
 *       listing the objects one level in it of the search panel's type (RoleType first, switchable: refresh); the
 *       {@code collection} items (an object
 *       collection loaded as the user, its type and filter as written, or a static collection); Roles of teammate
 *       unless {@code showRolesOfTeammate} / {@code rolesOfTeammate/enabled} is false;</li>
 *   <li>a role held (RequestAccess.canAddTemplateAssignment): the target's {@code roleMembershipRef} with
 *       {@code roleManagement/defaultAssignmentConstraints}. {@code roleManagement/roleCatalogRef} is not read.</li>
 * </ul>
 */
final class AccessRequestModels {

    private static final int DEFAULT_ROLE_CATALOG_DEPTH = 3;

    private AccessRequestModels() {
    }

    static AccessRequestModel model(Harness harness, Task task, OperationResult result) throws Exception {
        AccessRequestModel model = new AccessRequestModel();
        model.url = "/self/requestAccess";
        model.page = "PageRequestAccess";
        model.wizard = true;
        model.configuration = "adminGuiConfiguration/accessRequest of the requester's GUI profile"
                + " (roleManagement/roleCatalogRef is not read on this line)";
        model.heldByMembership = true;
        CompiledGuiProfile profile = ((GuiProfiledPrincipal) SecurityUtil.getPrincipal()).getCompiledGuiProfile();
        AccessRequestType config = profile.getAccessRequest();
        SystemConfigurationType system = harness.bean(RepositoryService.class).getObject(SystemConfigurationType.class,
                "00000000-0000-0000-0000-000000000001", null, result).asObjectable();
        RoleManagementConfigurationType roleManagement = system.getRoleManagement();
        model.constraints = roleManagement != null ? roleManagement.getDefaultAssignmentConstraints() : null;
        PrismContext prism = harness.bean(PrismContext.class);
        QueryFactory queries = prism.queryFactory();
        ModelInteractionService mis = harness.bean(ModelInteractionService.class);

        TargetSelectionType targets = config != null && config.getTargetSelection() != null
                ? config.getTargetSelection() : new TargetSelectionType();
        model.allowMyself = !Boolean.FALSE.equals(targets.isAllowRequestForMyself());
        model.allowOthers = !Boolean.FALSE.equals(targets.isAllowRequestForOthers());
        model.targetFilter = mis.getAccessibleForAssignmentObjectsFilter(UserType.class, null, task, result);
        for (GroupSelectionType group : targets.getGroup()) {
            ObjectFilter filter = model.targetFilter;
            if (group.getCollection() != null) {
                CompiledObjectCollectionView view = mis.compileObjectCollectionView(group.getCollection(),
                        UserType.class, task, result);
                ObjectFilter collection = view.getFilter() == null ? null
                        : LineSupport.evaluateFilterExpressions(view.getFilter(), new VariablesMap(),
                                harness.bean(ExpressionFactory.class), "group " + group.getIdentifier(), task, result);
                filter = collection == null ? filter : filter == null ? collection
                        : queries.createAnd(filter, collection);
            }
            model.groups.put(group.getIdentifier(), filter);
        }

        RelationSelectionType relations = config != null ? config.getRelationSelection() : null;
        if (relations != null) {
            model.defaultRelation = relations.getDefaultRelation();
            model.allowOtherRelations = relations.isAllowOtherRelations();
        }

        RoleCatalogType catalog = config != null && config.getRoleCatalog() != null ? config.getRoleCatalog()
                : new RoleCatalogType();
        if (catalog.getRoleCatalogRef() != null && catalog.getRoleCatalogRef().getOid() != null) {
            int depth = catalog.getRoleCatalogDepth() != null ? catalog.getRoleCatalogDepth()
                    : DEFAULT_ROLE_CATALOG_DEPTH;
            // the first level; a folder's children read as the user opens it (AccessRequests.children)
            model.entries.addAll(AccessRequests.folders(harness.bean(ModelService.class), prism,
                    catalog.getRoleCatalogRef().getOid(), 1, depth, false, task));
        }
        for (RoleCollectionViewType collection : catalog.getCollection()) {
            AccessRequestModel.Entry entry = collection(harness, prism, collection, task, result);
            if (entry != null) {
                model.entries.add(entry);
            }
        }
        RolesOfTeammateType teammate = catalog.getRolesOfTeammate();
        boolean rolesOfTeammate = teammate == null ? !Boolean.FALSE.equals(catalog.isShowRolesOfTeammate())
                : !Boolean.FALSE.equals(teammate.isEnabled());
        if (rolesOfTeammate) {
            AccessRequestModel.Entry entry = new AccessRequestModel.Entry();
            entry.kind = "ROLES_OF_TEAMMATE";
            entry.key = "rolesOfTeammate";
            entry.label = "Roles of teammate";
            model.entries.add(entry);
        }
        return model;
    }

    /** createMenuFromRoleCollections and updateQueryModel: an object collection (loaded as the user) or a static one. */
    @SuppressWarnings("unchecked")
    private static AccessRequestModel.Entry collection(Harness harness, PrismContext prism,
            RoleCollectionViewType collection, Task task, OperationResult result) throws Exception {
        AccessRequestModel.Entry entry = new AccessRequestModel.Entry();
        entry.kind = "COLLECTION";
        if (collection.getCollectionRef() != null && collection.getCollectionRef().getOid() != null) {
            PrismObject<ObjectCollectionType> object;
            try {
                object = harness.bean(ModelService.class).getObject(ObjectCollectionType.class,
                        collection.getCollectionRef().getOid(), null, task, new OperationResult("collection"));
            } catch (com.evolveum.midpoint.util.exception.CommonException e) {
                return null;
            }
            ObjectCollectionType oc = object.asObjectable();
            entry.key = oc.getName().getOrig();
            // getDisplayNameOrName: an object collection has no display name, its name
            entry.label = oc.getName().getOrig();
            entry.objectOid = oc.getOid();
            entry.objectType = ObjectCollectionType.COMPLEX_TYPE;
            entry.objectName = oc.getName().getOrig();
            entry.type = oc.getType() != null
                    ? (Class<? extends AbstractRoleType>) (Class<?>) ObjectTypes.getObjectTypeFromTypeQName(
                            oc.getType()).getClassDefinition()
                    : RoleType.class;
            // updateQueryFromCollectionRef: the filter as written, its expressions not evaluated
            entry.filter = oc.getFilter() == null ? null
                    : prism.getQueryConverter().createObjectFilter((Class<? extends ObjectType>) entry.type,
                            oc.getFilter());
            return entry;
        }
        String identifier = collection.getCollectionIdentifier();
        if (identifier == null) {
            return null;
        }
        switch (identifier) {
            case "allRoles":
                entry.kind = "ALL_ROLES";
                entry.label = "All roles";
                entry.type = RoleType.class;
                break;
            case "allOrgs":
                entry.kind = "ALL_ORGS";
                entry.label = "All organizations";
                entry.type = OrgType.class;
                break;
            case "allServices":
                entry.kind = "ALL_SERVICES";
                entry.label = "All services";
                entry.type = ServiceType.class;
                break;
            case "userAssignments":
                entry.label = "User's assignments";
                entry.type = AbstractRoleType.class;
                break;
            case "roleCatalog":
                entry.label = "Role catalog";
                entry.type = AbstractRoleType.class;
                break;
            default:
                return null;
        }
        entry.key = identifier;
        return entry;
    }
}
