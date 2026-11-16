package com.github.wadahiro.midpoint.tester.internal;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.common.mapping.AbstractMappingImpl;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.repo.api.CacheDispatcher;
import com.evolveum.midpoint.repo.common.expression.Expression;
import com.evolveum.midpoint.repo.common.expression.ExpressionFactory;
import com.evolveum.midpoint.repo.common.expression.ExpressionUtil;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.CommonException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ExpressionType;

/** midPoint 4.9 line of the tester runtime; the native-repository parts are shared ({@link Line48}). */
public final class LineSupport {

    public static final String LINE = "4.9";

    static final String CONFIG_FILE = Line48.CONFIG_FILE;
    static final String[] SCHEMA_SCRIPTS = Line48.SCHEMA_SCRIPTS;
    static final String[] BASELINE = Line48.BASELINE;
    static final ScriptHook48.Api SCRIPT_API = Line48.SCRIPT_API;

    private LineSupport() {
    }

    /**
     * A container or bean value (e.g. {@code InformationType}) as one line of JSON, so that the normalized text
     * shows its content; null when prism cannot serialize it.
     */
    public static String serializeRealValue(Object value) {
        return Line48.serializeRealValue(value);
    }

    static String config(String jdbcUrl, String user, String extensionDir, String connectorDir) {
        return Line48.config(jdbcUrl, user, extensionDir, connectorDir);
    }

    static Harness startHarness() {
        return Line48.startHarness();
    }

    static LineDrivers drivers(Harness harness) {
        return new Drivers48((Harness48) harness);
    }

    // ---- midPoint API that differs between the 4.8+ lines -------------------------------------------------------

    /**
     * The repository's own caches (ext items, URIs), reloaded from the restored database. 4.9's
     * {@code ExtItemCache.initialize} does not clear its {@code nameToExtItem} map (new in 4.9, used to find
     * conflicting ext item definitions): after a reset its stale entries make the repository drop shadow attributes
     * (e.g. {@code icfs:name}), so it is cleared here first.
     */
    static void clearRepositoryCaches(SqaleRepoContext context) {
        try {
            java.lang.reflect.Field cacheField = SqaleRepoContext.class.getDeclaredField("extItemCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(context);
            java.lang.reflect.Field namesField = cache.getClass().getDeclaredField("nameToExtItem");
            namesField.setAccessible(true);
            Object names = namesField.get(cache);
            synchronized (cache) {
                ((com.google.common.collect.Multimap<?, ?>) names).clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot clear the ext item names of the repository", e);
        }
        // the shadow partitions: the partition manager remembers the resource tables it created, which the reset of
        // the database drops (a resource imported after the snapshot then gets no table for its shadows)
        try {
            Object manager = com.evolveum.midpoint.repo.sqale.qmodel.shadow.QShadowMapping.getShadowMapping()
                    .getPartitionManager();
            java.lang.reflect.Field tables = manager.getClass().getDeclaredField("resourceTable");
            tables.setAccessible(true);
            synchronized (manager) {
                tables.set(manager, null);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot clear the shadow partitions the repository knows", e);
        }
        context.clearCaches();
    }

    /** A multi-valued, frozen definition of an expression's output ({@code mutator()}). */
    static <T> PrismPropertyDefinition<T> outputDefinition(QName name, QName type) {
        PrismPropertyDefinition<T> definition = PrismContext.get().definitionFactory().newPropertyDefinition(name, type);
        definition.mutator().setMaxOccurs(-1);
        definition.freeze();
        return definition;
    }

    /** The mapping's target item name as midPoint gives it to expressions ({@code getTargetItemName()}). */
    static QName mappingTargetName(AbstractMappingImpl<?, ?, ?> mapping) {
        return mapping.getTargetItemName();
    }

    /** The expression of an ad-hoc script ({@code makeExpression} with an expression profile, null: the default). */
    static <V extends PrismValue, D extends ItemDefinition<?>> Expression<V, D> makeExpression(ExpressionFactory factory,
            ExpressionType expression, D output, String description, Task task, OperationResult result)
            throws CommonException {
        return factory.makeExpression(expression, output, null, description, task, result);
    }

    /** A filter's expressions evaluated as the GUI does ({@code evaluateFilterExpressions} with the default profile). */
    static ObjectFilter evaluateFilterExpressions(ObjectFilter filter, VariablesMap variables, ExpressionFactory factory,
            String description, Task task, OperationResult result) throws CommonException {
        return ExpressionUtil.evaluateFilterExpressions(filter, variables, MiscSchemaUtil.getExpressionProfile(), factory,
                description, task, result);
    }

    /** Every midPoint cache invalidated, on this node only ({@code CacheDispatcher}). */
    static void invalidateCaches(Harness48 harness) {
        harness.bean(CacheDispatcher.class).dispatchInvalidation(null, null, false, null);
    }

    /** Trust for the expressions in a bean, which 4.11 requires: before it, nothing to set. */
    static void trustFully(Object bean) {
    }

    /** See {@link #trustFully}. */
    static void trustAsCurrentPrincipal(Object bean) {
    }

    /** See {@code Line48.currentChannel}. */
    static String currentChannel() {
        return Line48.currentChannel();
    }
}
