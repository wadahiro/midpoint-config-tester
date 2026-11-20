package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.RoleSelectionSpecification;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.tester.MidPoint;

/**
 * The model calls of {@code configtester.RequestAccessPage}, as the requester logs in to the GUI: the page's model
 * ({@link LineDrivers#accessRequestModel}), the specification of what may be assigned to the target
 * ({@code ModelInteractionService.getAssignableRoleSpecification}, the same binary signature on every line), searches
 * as the requester (their {@code #search} / {@code #read} narrow them) and as the administrator (the repository, for
 * the explanation of a failure). A call midPoint refuses the requester is an empty answer, as the GUI shows it.
 */
public final class AccessRequests {

    private AccessRequests() {
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

    /** The objects of the type the requester's search with the filter finds (null: all), by name; refused: none. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static List<ObjectType> searchAs(MidPoint api, String requester, Class<? extends ObjectType> type,
            ObjectFilter filter) {
        MidPointImpl mp = (MidPointImpl) api;
        return mp.queryAs(requester, null, "search " + type.getSimpleName() + " as " + requester, (task, result) -> {
            List<ObjectType> out = new ArrayList<>();
            ObjectQuery query = mp.prismContext().queryFactory().createQuery(filter);
            try {
                for (Object object : mp.model().searchObjects((Class) type, query, null, task,
                        new OperationResult("search"))) {
                    out.add((ObjectType) ((PrismObject<?>) object).asObjectable());
                }
            } catch (Exception e) {
                return out;
            }
            out.sort((a, b) -> a.getName().getOrig().compareToIgnoreCase(b.getName().getOrig()));
            return out;
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
