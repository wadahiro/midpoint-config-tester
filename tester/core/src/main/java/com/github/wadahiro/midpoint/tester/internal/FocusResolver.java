package com.github.wadahiro.midpoint.tester.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.util.ObjectDeltaObject;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.Evaluation;

/**
 * Which focus object an evaluation is about ({@link Evaluation#focus()}), decided for the outermost evaluation (the
 * root) and inherited by what is nested in it:
 *
 * <ol>
 *   <li>the object of the root's {@code focus} variable (of an object delta: the object after the change, else the
 *       one before, so a focus being deleted is still itself), when it has a name or an OID; a missing name or OID is
 *       looked up in the repository when the run ends;</li>
 *   <li>else (no {@code focus} variable, or an object without name and OID: the focus an inbound or a correlation
 *       works on before the user exists) the owner of the root's shadow (variable {@code shadow}, {@code projection} or
 *       {@code account}) when the run ends: the user the shadow got linked to;</li>
 *   <li>else none.</li>
 * </ol>
 */
public final class FocusResolver {

    private static final List<String> SHADOW_VARIABLES = List.of("shadow", "projection", "account");

    private FocusResolver() {
    }

    /** The object a variable value holds (an object delta: its new object, else its old one), or null. */
    public static PrismObject<?> object(Object value) {
        if (value instanceof ObjectDeltaObject) {
            value = ((ObjectDeltaObject<?>) value).getAnyObject();
        }
        if (value instanceof Objectable) {
            value = ((Objectable) value).asPrismObject();
        }
        return value instanceof PrismObject ? (PrismObject<?>) value : null;
    }

    /** The object of the {@code focus} variable, as midPoint gave it; null if there is none or it has no name or OID. */
    public static Evaluation.Owner ofVariables(Map<String, Object> variables) {
        PrismObject<?> focus = object(variables.get("focus"));
        if (focus == null) {
            return null;
        }
        String name = focus.getName() != null ? focus.getName().getOrig() : null;
        if (name == null && focus.getOid() == null) {
            return null;
        }
        return new Evaluation.Owner(typeName(focus), focus.getOid(), name);
    }

    /** Decides the focus of every root record of a finished run, with the repository's state now. */
    public static void resolve(List<Record> records, RepositoryService repository, PrismContext prismContext) {
        Map<String, Evaluation.Owner> cache = new HashMap<>();
        for (Record record : records) {
            if (record.parent != null) {
                continue;
            }
            Evaluation.Owner focus;
            try {
                focus = resolve(record.variables, repository, prismContext, cache);
            } catch (RuntimeException e) {
                focus = ofVariables(record.variables);
            }
            record.focus = focus;
            record.focusResolved = true;
        }
    }

    private static Evaluation.Owner resolve(Map<String, Object> variables, RepositoryService repository,
            PrismContext prismContext, Map<String, Evaluation.Owner> cache) {
        PrismObject<?> focus = object(variables.get("focus"));
        Evaluation.Owner known = ofVariables(variables);
        if (known != null) {
            if (known.oid() != null && known.name() != null) {
                return known;
            }
            @SuppressWarnings("unchecked")
            Class<? extends ObjectType> type = focus.getCompileTimeClass() != null
                    && ObjectType.class.isAssignableFrom(focus.getCompileTimeClass())
                    ? (Class<? extends ObjectType>) focus.getCompileTimeClass() : ObjectType.class;
            String key = type.getName() + "/" + known.oid() + "/" + known.name();
            return cache.computeIfAbsent(key, k -> complete(known, type, repository, prismContext));
        }
        for (String variable : SHADOW_VARIABLES) {
            PrismObject<?> shadow = object(variables.get(variable));
            if (shadow != null && shadow.getOid() != null) {
                String key = "shadow/" + shadow.getOid();
                if (!cache.containsKey(key)) {
                    cache.put(key, shadowOwner(shadow.getOid(), repository));
                }
                return cache.get(key);
            }
        }
        return null;
    }

    /** The name of a focus known by OID, or the OID of one known by name (null if it does not exist). */
    private static Evaluation.Owner complete(Evaluation.Owner known, Class<? extends ObjectType> type,
            RepositoryService repository, PrismContext prismContext) {
        try {
            if (known.oid() != null) {
                PrismObject<? extends ObjectType> object =
                        repository.getObject(type, known.oid(), null, new OperationResult("focus"));
                return new Evaluation.Owner(known.type(), known.oid(), object.getName().getOrig());
            }
            ObjectQuery query = prismContext.queryFor(type)
                    .item(ObjectType.F_NAME).eqPoly(known.name()).matchingOrig()
                    .build();
            List<? extends PrismObject<? extends ObjectType>> found =
                    repository.searchObjects(type, query, null, new OperationResult("focus"));
            return found.size() == 1
                    ? new Evaluation.Owner(known.type(), found.get(0).getOid(), known.name())
                    : known;
        } catch (Exception e) {
            return known;
        }
    }

    private static Evaluation.Owner shadowOwner(String shadowOid, RepositoryService repository) {
        PrismObject<?> owner = repository.searchShadowOwner(shadowOid, null, new OperationResult("focus"));
        if (owner == null) {
            return null;
        }
        return new Evaluation.Owner(typeName(owner), owner.getOid(),
                owner.getName() != null ? owner.getName().getOrig() : null);
    }

    private static String typeName(PrismObject<?> object) {
        return object.getCompileTimeClass() != null ? object.getCompileTimeClass().getSimpleName() : null;
    }
}
