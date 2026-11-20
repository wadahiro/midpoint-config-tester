package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.OrgFilter;
import com.evolveum.midpoint.prism.query.QueryFactory;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionUseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleManagementConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;

/**
 * The request-access page of 4.0 and 4.4 (both designs): PageAssignmentShoppingCart ({@code /self/assignmentShoppingCart})
 * as the system configuration's {@code roleManagement} makes it ({@link AccessRequestModel}).
 * <ul>
 *   <li>tabs (getTabsList, getTabVisibleBehaviour): Role catalog when {@code roleCatalogRef} is set (whatever
 *       {@code roleCatalogCollections} says), All roles, All organizations, All services, User's assignments, each
 *       shown when {@code roleCatalogCollections} is empty or names its URI;</li>
 *   <li>the role catalog (RoleCatalogTabPanel): the tree of orgs under {@code roleCatalogRef} (OrgTreePanel, searched
 *       as the user, at any depth, a node's children read when it is expanded: AccessRequests.children; 20 a page,
 *       MidpointSubtree), the catalog org selected first; a folder lists the objects one level in it of the type the
 *       search panel shows (roles first, switchable);</li>
 *   <li>target users (UserSelectionButton): any user the requester's search finds; the relations and the held check
 *       over the target's assignments (AbstractShoppingCartTabPanel, AssignmentEditorDto.isAssignable).</li>
 * </ul>
 */
final class ShoppingCartModels {

    private ShoppingCartModels() {
    }

    static AccessRequestModel model(Harness harness, Task task, OperationResult result) throws Exception {
        AccessRequestModel model = new AccessRequestModel();
        model.url = "/self/assignmentShoppingCart";
        model.page = "PageAssignmentShoppingCart";
        model.wizard = false;
        model.configuration = "the system configuration's roleManagement";
        model.heldByMembership = false;
        // the page reads the system configuration through ModelInteractionService, privileged
        SystemConfigurationType config = harness.bean(RepositoryService.class).getObject(SystemConfigurationType.class,
                SystemObjectsOid.SYSTEM_CONFIGURATION, null, result).asObjectable();
        RoleManagementConfigurationType roleManagement = config.getRoleManagement();
        model.constraints = roleManagement != null ? roleManagement.getDefaultAssignmentConstraints() : null;
        PrismContext prism = harness.bean(PrismContext.class);
        QueryFactory queries = prism.queryFactory();

        String catalog = roleManagement != null && roleManagement.getRoleCatalogRef() != null
                ? roleManagement.getRoleCatalogRef().getOid() : null;
        if (catalog != null && !catalog.isEmpty()) {
            AccessRequestModel.Entry root = folder(queries, catalog, null);
            root.key = "roleCatalog";
            root.label = "Role catalog view";
            PrismObject<OrgType> org = getAsUser(harness, catalog, task, result);
            root.objectName = org != null ? org.getName().getOrig() : null;
            model.entries.add(root);
        }
        add(model, roleManagement, SchemaConstants.OBJECT_COLLECTION_ALL_ROLES_URI, "ALL_ROLES", "allRoles",
                "All roles view", RoleType.class);
        add(model, roleManagement, SchemaConstants.OBJECT_COLLECTION_ALL_ORGS_URI, "ALL_ORGS", "allOrgs",
                "All organizations view", OrgType.class);
        add(model, roleManagement, SchemaConstants.OBJECT_COLLECTION_ALL_SERVICES_URI, "ALL_SERVICES", "allServices",
                "All services view", ServiceType.class);
        if (shown(roleManagement, SchemaConstants.OBJECT_COLLECTION_USER_ASSIGNMENTS_URI)) {
            AccessRequestModel.Entry user = new AccessRequestModel.Entry();
            user.kind = "USER_ASSIGNMENTS";
            user.key = "userAssignments";
            user.label = "User's assignments";
            model.entries.add(user);
        }
        return model;
    }

    /** A folder: the objects one level in the org (RoleCatalogTabPanel.createContentQuery). */
    private static AccessRequestModel.Entry folder(QueryFactory queries, String oid, String name) {
        AccessRequestModel.Entry entry = new AccessRequestModel.Entry();
        entry.kind = "ORG_FOLDER";
        entry.key = name;
        entry.label = name;
        entry.objectOid = oid;
        entry.objectType = OrgType.COMPLEX_TYPE;
        entry.objectName = name;
        // the objects one level in it, of the type the search panel shows (roles first; the page's withType)
        entry.type = RoleType.class;
        entry.filter = queries.createOrg(oid, OrgFilter.Scope.ONE_LEVEL);
        // the tree's root; its children read as the user expands it (AccessRequests.children), at any depth
        entry.level = 0;
        entry.maxLevel = null;
        return entry;
    }

    private static PrismObject<OrgType> getAsUser(Harness harness, String oid, Task task, OperationResult result) {
        try {
            return harness.bean(ModelService.class).getObject(OrgType.class, oid, null, task, new OperationResult("org"));
        } catch (Exception e) {
            return null;
        }
    }

    private static void add(AccessRequestModel model, RoleManagementConfigurationType roleManagement, String uri,
            String kind, String key, String label, Class<? extends AbstractRoleType> type) {
        if (!shown(roleManagement, uri)) {
            return;
        }
        AccessRequestModel.Entry entry = new AccessRequestModel.Entry();
        entry.kind = kind;
        entry.key = key;
        entry.label = label;
        entry.type = type;
        model.entries.add(entry);
    }

    /** getTabVisibleBehaviour: no roleCatalogCollections, or one naming the URI. */
    private static boolean shown(RoleManagementConfigurationType roleManagement, String uri) {
        if (roleManagement == null || roleManagement.getRoleCatalogCollections() == null
                || roleManagement.getRoleCatalogCollections().getCollection().isEmpty()) {
            return true;
        }
        for (ObjectCollectionUseType use : roleManagement.getRoleCatalogCollections().getCollection()) {
            if (uri.equals(use.getCollectionUri())) {
                return true;
            }
        }
        return false;
    }

    /** midPoint's OID of the system configuration (SystemObjectsType.SYSTEM_CONFIGURATION). */
    private static final class SystemObjectsOid {
        static final String SYSTEM_CONFIGURATION = "00000000-0000-0000-0000-000000000001";
    }
}
