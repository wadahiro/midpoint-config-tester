package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.query.NoneFilter;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.QueryFactory;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.util.exception.SecurityViolationException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationPhaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.Access;
import com.github.wadahiro.midpoint.tester.Answer;
import com.github.wadahiro.midpoint.tester.CompiledAuthorization;
import com.github.wadahiro.midpoint.tester.OperationFailed;
import com.github.wadahiro.midpoint.tester.Run;

/** {@link Access}: each call logs the user in and the administrator back in, whatever happens. */
final class AccessImpl implements Access {

    private final MidPointImpl mp;
    private final TesterRuntime runtime;
    private final String username;

    AccessImpl(MidPointImpl mp, TesterRuntime runtime, String username) {
        this.mp = mp;
        this.runtime = runtime;
        this.username = username;
    }

    @Override
    public String username() {
        return username;
    }

    @Override
    public <O extends ObjectType> Answer<List<O>> search(Class<O> type, ObjectQuery query) {
        List<O> found = new ArrayList<>();
        Run run = as("search " + type.getSimpleName(), (task, result) -> {
            for (PrismObject<O> object : mp.model().searchObjects(type, query, null, task, result)) {
                found.add(object.asObjectable());
            }
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(found, checked(run));
    }

    @Override
    public Answer<List<ObjectType>> collection(String nameOrOid) {
        String oid = mp.existing(com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionType.class,
                nameOrOid).getOid();
        List<ObjectType> found = new ArrayList<>();
        Run run = as("collection " + nameOrOid, (task, result) -> {
            for (PrismObject<? extends ObjectType> object : mp.drivers().searchCollection(oid, task, result)) {
                found.add(object.asObjectable());
            }
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(found, checked(run));
    }

    @Override
    public <O extends ObjectType> Answer<O> get(Class<O> type, String nameOrOid) {
        O existing = mp.existing(type, nameOrOid);
        String oid = existing.getOid();
        List<O> found = new ArrayList<>();
        Run run = as("get " + existing.getName().getOrig(), (task, result) -> {
            found.add(mp.model().getObject(type, oid, null, task, result).asObjectable());
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(found.isEmpty() ? null : found.get(0), checked(run));
    }

    @Override
    public <O extends ObjectType> Answer<Boolean> isAuthorized(String actionUrl, Class<O> type, String nameOrOid) {
        @SuppressWarnings("unchecked")
        PrismObject<O> object = (PrismObject<O>) mp.existing(type, nameOrOid).asPrismObject();
        boolean[] allowed = new boolean[1];
        Run run = as("authorize " + actionUrl + " " + object.getName().getOrig(), (task, result) -> {
            allowed[0] = mp.drivers().isAuthorized(actionUrl, object, task, result);
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(allowed[0], checked(run));
    }

    @Override
    public <F extends ObjectType, T extends ObjectType> Answer<Boolean> isAuthorizedToAssign(Class<F> type,
            String focus, Class<T> targetType, String target, javax.xml.namespace.QName relation) {
        return assignment(type, focus, targetType, target, relation, true);
    }

    @Override
    public <F extends ObjectType, T extends ObjectType> Answer<Boolean> isAuthorizedToUnassign(Class<F> type,
            String focus, Class<T> targetType, String target, javax.xml.namespace.QName relation) {
        return assignment(type, focus, targetType, target, relation, false);
    }

    private <F extends ObjectType, T extends ObjectType> Answer<Boolean> assignment(Class<F> type, String focus,
            Class<T> targetType, String target, javax.xml.namespace.QName relation, boolean assign) {
        PrismObject<? extends ObjectType> focusObject = mp.existing(type, focus).asPrismObject();
        PrismObject<? extends ObjectType> targetObject = mp.existing(targetType, target).asPrismObject();
        boolean[] allowed = new boolean[1];
        Run run = as("authorize " + (assign ? "assign " : "unassign ") + targetObject.getName().getOrig()
                + (assign ? " to " : " from ") + focusObject.getName().getOrig(), (task, result) -> {
                    allowed[0] = mp.drivers().isAuthorizedToAssign(focusObject, targetObject, relation, assign, task,
                            result);
                    return MidPointImpl.Outcome.NONE;
                });
        return new Answer<>(allowed[0], checked(run));
    }

    @Override
    public <O extends ObjectType> Answer<java.util.Set<String>> selectedBy(
            com.evolveum.midpoint.xml.ns._public.common.common_3.OwnedObjectSelectorType selector, Class<O> type,
            java.util.Collection<String> oids) {
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        Run run = as("select " + type.getSimpleName() + " by a selector", (task, result) -> {
            ObjectFilter filter = mp.drivers().selectorFilter(selector, type, task, result);
            if (oids.isEmpty() || filter instanceof NoneFilter) {
                return MidPointImpl.Outcome.NONE;
            }
            QueryFactory queries = mp.prismContext().queryFactory();
            ObjectFilter samples = queries.createInOid(oids);
            ObjectQuery query = queries.createQuery(filter == null ? samples : queries.createAnd(filter, samples));
            // the repository: the filter is the user's already
            for (PrismObject<O> object : mp.harness.bean(RepositoryService.class).searchObjects(type, query, null,
                    result)) {
                selected.add(object.getOid());
            }
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(selected, checked(run));
    }

    @Override
    public Answer<List<CompiledAuthorization>> authorizations() {
        List<CompiledAuthorization> out = new ArrayList<>();
        Run run = as("authorizations", (task, result) -> {
            for (Object granted : com.evolveum.midpoint.security.api.SecurityUtil.getPrincipal().getAuthorities()) {
                com.evolveum.midpoint.security.api.Authorization authorization =
                        (com.evolveum.midpoint.security.api.Authorization) granted;
                out.add(new CompiledAuthorization(bean(authorization), authorization.getSourceDescription()));
            }
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(out, checked(run));
    }

    @Override
    public Answer<com.github.wadahiro.midpoint.tester.GuiProfile> guiProfile() {
        List<com.github.wadahiro.midpoint.tester.GuiProfile> out = new ArrayList<>();
        Run run = as("GUI profile", (task, result) -> {
            out.add(mp.drivers().guiProfile(task, result));
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(out.isEmpty() ? null : out.get(0), checked(run));
    }

    /**
     * The authorization as written: the compiled authorization keeps it in a private field (authorizationType
     * before 4.8, authorizationBean on 4.8) and has no getter of it, so it is read by its type.
     */
    private static AuthorizationType bean(com.evolveum.midpoint.security.api.Authorization authorization)
            throws IllegalAccessException {
        for (java.lang.reflect.Field field : authorization.getClass().getDeclaredFields()) {
            if (field.getType() == AuthorizationType.class) {
                field.setAccessible(true);
                return (AuthorizationType) field.get(authorization);
            }
        }
        throw new IllegalStateException("No authorization bean in " + authorization.getClass().getName());
    }

    @Override
    public Answer<Boolean> isAuthorizedForAny(java.util.Collection<String> actionUrls) {
        boolean[] allowed = new boolean[1];
        Run run = as("authorize any of " + actionUrls, (task, result) -> {
            allowed[0] = mp.drivers().decideAccess(actionUrls, task, result);
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(allowed[0], checked(run));
    }

    @Override
    public <O extends ObjectType> Answer<PrismObjectDefinition<O>> editDefinition(Class<O> type, String nameOrOid) {
        O existing = mp.existing(type, nameOrOid);
        String oid = existing.getOid();
        List<PrismObjectDefinition<O>> found = new ArrayList<>();
        Run run = as("edit definition of " + existing.getName().getOrig(), (task, result) -> {
            // the object as the user reads it, as the GUI's form gets it
            PrismObject<O> object = mp.model().getObject(type, oid, null, task, result);
            found.add(mp.harness.bean(ModelInteractionService.class)
                    .getEditObjectDefinition(object, AuthorizationPhaseType.REQUEST, task, result));
            return MidPointImpl.Outcome.NONE;
        });
        return new Answer<>(found.isEmpty() ? null : found.get(0), checked(run));
    }

    @Override
    public Run execute(ObjectDelta<? extends ObjectType> delta) {
        runtime.login(username);
        try {
            return mp.execute(List.of(delta), null, false);
        } finally {
            runtime.login(TesterRuntime.ADMINISTRATOR);
        }
    }

    private Run as(String name, MidPointImpl.Operation operation) {
        runtime.login(username);
        try {
            return mp.run(name + " as " + username, operation, false);
        } finally {
            runtime.login(TesterRuntime.ADMINISTRATOR);
        }
    }

    /** A denial is an answer; anything else that failed is thrown. */
    private static Run checked(Run run) {
        if (run.failure() != null && !(run.failure() instanceof SecurityViolationException)) {
            throw new OperationFailed(run.operation() + " failed: " + run.failure().getMessage(), run, run.failure());
        }
        return run;
    }
}
