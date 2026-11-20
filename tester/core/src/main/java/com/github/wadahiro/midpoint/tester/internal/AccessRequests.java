package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.RoleSelectionSpecification;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.prism.query.ObjectOrdering;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.OrderDirection;
import com.evolveum.midpoint.prism.query.OrgFilter;
import com.evolveum.midpoint.prism.query.QueryFactory;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ServiceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.tester.MidPoint;

/**
 * The model calls of {@code configtester.RequestAccessPage}, as the requester logs in to the GUI: the page's model
 * ({@link LineDrivers#accessRequestModel}), the specification of what may be assigned to the target
 * ({@code ModelInteractionService.getAssignableRoleSpecification}, the same binary signature on every line), searches
 * as the requester (their {@code #search} / {@code #read} narrow them) and as the administrator (the repository, for
 * the explanation of a failure). A call midPoint refuses the requester is an empty answer, as the GUI shows it.
 * <p>
 * The GUI shows its lists a page at a time, {@link #PAGE_SIZE} items, every page reachable (4.0, 4.4: a tree node's
 * children, MidpointSubtree with OrgTreePagingPanel, ordered by display name then name; the catalog's grid,
 * GridViewComponent 5 rows of 4 with BoxedPagingPanel, by name; 4.8+: the tiles and the table of RoleCatalogPanel,
 * the user profile's paging size of PAGE_REQUEST_ACCESS_ROLE_CATALOG, by name; the menu of org folders unpaged, by
 * display name then name). The searches here read every page with the GUI's ordering, so nothing past the first page
 * is lost and the order is the GUI's.
 */
public final class AccessRequests {

    /** The GUI's page of a list: 4.0, 4.4 MidpointSubtree and GridViewComponent, 4.8+ DEFAULT_PAGING_SIZE. */
    static final int PAGE_SIZE = 20;

    private AccessRequests() {
    }

    /**
     * The org folders one level under the org, searched with the task's principal (the requester), as the GUI reads
     * a tree node's or a menu item's children: ordered by display name, then name (4.0, 4.4 OrgTreeProvider
     * .createPaging; 4.8+ RoleCatalogPanel.loadMenuFromOrgTree), every page. Each lists the objects one level in it
     * as {@link #folderListing} says (4.0, 4.4: {@code rolesAndServices}); its own children are read when asked
     * ({@link #children}). Past {@code maxLevel}: none. Refused: none, as the GUI logs it and shows no folder.
     */
    public static List<AccessRequestModel.Entry> folders(ModelService model, PrismContext prism, String parentOid,
            int level, Integer maxLevel, boolean rolesAndServices, Task task) {
        List<AccessRequestModel.Entry> out = new ArrayList<>();
        if (maxLevel != null && level > maxLevel) {
            return out;
        }
        QueryFactory queries = prism.queryFactory();
        List<ObjectOrdering> orderings = new ArrayList<>();
        orderings.add(queries.createOrdering(OrgType.F_DISPLAY_NAME, OrderDirection.ASCENDING));
        orderings.add(queries.createOrdering(OrgType.F_NAME, OrderDirection.ASCENDING));
        List<ObjectType> orgs;
        try {
            orgs = pages(model, queries, OrgType.class, queries.createOrg(parentOid, OrgFilter.Scope.ONE_LEVEL),
                    orderings, task);
        } catch (Exception e) {
            return out;
        }
        for (ObjectType object : orgs) {
            OrgType org = (OrgType) object;
            AccessRequestModel.Entry entry = new AccessRequestModel.Entry();
            entry.kind = "ORG_FOLDER";
            entry.key = org.getName().getOrig();
            // WebComponentUtil.getDisplayNameOrName
            entry.label = org.getDisplayName() != null && org.getDisplayName().getOrig() != null
                    ? org.getDisplayName().getOrig() : org.getName().getOrig();
            entry.objectOid = org.getOid();
            entry.objectType = OrgType.COMPLEX_TYPE;
            entry.objectName = org.getName().getOrig();
            folderListing(queries, entry, org.getOid(), rolesAndServices);
            entry.level = level;
            entry.maxLevel = maxLevel;
            out.add(entry);
        }
        return out;
    }

    /**
     * What an org folder lists: the objects one level in the org. 4.8+ (RoleCatalogPanel): of the type of the search
     * panel, {@code RoleType} at first ({@code RequestAccessPage.withType}). 4.0, 4.4 (RoleCatalogTabPanel
     * .createContentQuery, getQueryType): roles and services together, searched as {@code AbstractRoleType} with
     * {@code OR(type RoleType (org one level), type ServiceType (org one level))}, the search panel without a type.
     */
    public static void folderListing(QueryFactory queries, AccessRequestModel.Entry entry, String orgOid,
            boolean rolesAndServices) {
        entry.rolesAndServices = rolesAndServices;
        if (rolesAndServices) {
            entry.type = AbstractRoleType.class;
            entry.filter = queries.createOr(
                    queries.createType(RoleType.COMPLEX_TYPE, queries.createOrg(orgOid, OrgFilter.Scope.ONE_LEVEL)),
                    queries.createType(ServiceType.COMPLEX_TYPE, queries.createOrg(orgOid, OrgFilter.Scope.ONE_LEVEL)));
        } else {
            entry.type = RoleType.class;
            entry.filter = queries.createOrg(orgOid, OrgFilter.Scope.ONE_LEVEL);
        }
    }

    /** The folder's children, read as the requester the first time they are asked, as the user expands it. */
    public static List<AccessRequestModel.Entry> children(MidPoint api, String requester,
            AccessRequestModel.Entry parent) {
        if (!parent.childrenRead) {
            MidPointImpl mp = (MidPointImpl) api;
            List<AccessRequestModel.Entry> children = parent.objectOid == null || !"ORG_FOLDER".equals(parent.kind)
                    ? List.of()
                    : mp.queryAs(requester, null, "folders of " + parent.key + " as " + requester,
                            (task, result) -> folders(mp.model(), mp.prismContext(), parent.objectOid,
                                    parent.level + 1, parent.maxLevel, parent.rolesAndServices, task));
            parent.children.addAll(children);
            parent.childrenRead = true;
        }
        return parent.children;
    }

    /** Every page of the search, {@link #PAGE_SIZE} at a time with the orderings, as the user pages through it. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<ObjectType> pages(ModelService model, QueryFactory queries, Class<? extends ObjectType> type,
            ObjectFilter filter, List<ObjectOrdering> orderings, Task task) throws Exception {
        List<ObjectType> out = new ArrayList<>();
        for (int offset = 0;; offset += PAGE_SIZE) {
            ObjectQuery query = queries.createQuery(filter);
            query.setPaging(queries.createPaging(offset, PAGE_SIZE, orderings));
            List<?> page = model.searchObjects((Class) type, query, null, task, new OperationResult("search"));
            for (Object object : page) {
                out.add((ObjectType) ((PrismObject<?>) object).asObjectable());
            }
            if (page.size() < PAGE_SIZE) {
                return out;
            }
        }
    }

    /** The page as its configuration makes it for the requester. */
    public static AccessRequestModel model(MidPoint api, String requester) {
        MidPointImpl mp = (MidPointImpl) api;
        return mp.queryAs(requester, null, "request access of " + requester,
                (task, result) -> mp.drivers().accessRequestModel(task, result));
    }

    /**
     * What the requester may assign to the target, of the type ({@code getAssignableRoleSpecification(target, type,
     * 0)}, the target read as the requester: their own user as the principal has it; another user as they get it).
     */
    public static RoleSelectionSpecification assignable(MidPoint api, String requester, String targetOid,
            Class<? extends AbstractRoleType> type) {
        MidPointImpl mp = (MidPointImpl) api;
        return mp.queryAs(requester, null, "assignable " + type.getSimpleName() + " as " + requester,
                (task, result) -> {
                    OperationResult own = new OperationResult("assignable");
                    PrismObject<UserType> target = read(mp, targetOid, task, own);
                    return mp.bean(ModelInteractionService.class).getAssignableRoleSpecification(target, type, 0,
                            task, own);
                });
    }

    /** The user as the requester gets them, or (refused) as stored. */
    private static PrismObject<UserType> read(MidPointImpl mp, String oid, com.evolveum.midpoint.task.api.Task task,
            OperationResult result) {
        try {
            return mp.model().getObject(UserType.class, oid, null, task, result);
        } catch (Exception e) {
            return mp.existing(UserType.class, oid).asPrismObject();
        }
    }

    /** The user as the requester gets them, or null: refused. */
    public static UserType readAs(MidPoint api, String requester, String oid) {
        MidPointImpl mp = (MidPointImpl) api;
        return mp.queryAs(requester, null, "get user as " + requester, (task, result) -> {
            try {
                return mp.model().getObject(UserType.class, oid, null, task, new OperationResult("get"))
                        .asObjectable();
            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * The objects of the type the requester's search with the filter finds (null: all), every page in the GUI's
     * order (by name: 4.0, 4.4 and 4.8+ the list's default sort of BaseSortableDataProvider); refused: none.
     */
    public static List<ObjectType> searchAs(MidPoint api, String requester, Class<? extends ObjectType> type,
            ObjectFilter filter) {
        MidPointImpl mp = (MidPointImpl) api;
        return mp.queryAs(requester, null, "search " + type.getSimpleName() + " as " + requester, (task, result) -> {
            QueryFactory queries = mp.prismContext().queryFactory();
            List<ObjectOrdering> byName = new ArrayList<>();
            byName.add(queries.createOrdering(ObjectType.F_NAME, OrderDirection.ASCENDING));
            try {
                return pages(mp.model(), queries, type, filter, byName, task);
            } catch (Exception e) {
                return new ArrayList<>();
            }
        });
    }

    /** The objects of the type the filter selects in the repository (null: all): for explanations only. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static List<ObjectType> searchAsAdministrator(MidPoint api, Class<? extends ObjectType> type,
            ObjectFilter filter) {
        MidPointImpl mp = (MidPointImpl) api;
        return mp.query("search " + type.getSimpleName(), (task, result) -> {
            List<ObjectType> out = new ArrayList<>();
            ObjectQuery query = mp.prismContext().queryFactory().createQuery(filter);
            try {
                for (Object object : mp.bean(RepositoryService.class).searchObjects((Class) type, query, null,
                        new OperationResult("search"))) {
                    out.add((ObjectType) ((PrismObject<?>) object).asObjectable());
                }
            } catch (Exception e) {
                return out;
            }
            return out;
        });
    }
}
