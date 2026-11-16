package com.github.wadahiro.midpoint.tester.internal;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.common.mapping.AbstractMappingImpl;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.JaxbVisitable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.repo.api.ClusterwideCacheInvalidationDispatcher;
import com.evolveum.midpoint.repo.common.expression.Expression;
import com.evolveum.midpoint.repo.common.expression.ExpressionFactory;
import com.evolveum.midpoint.repo.common.expression.ExpressionUtil;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.schema.expression.MidPointTrustDescriptor;
import com.evolveum.midpoint.schema.expression.TrustDescriptorSetter;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.CommonException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ExpressionType;

/** midPoint 4.11 line of the tester runtime; the native-repository parts are shared ({@link Line48}). */
public final class LineSupport {

    public static final String LINE = "4.11";

    static final String CONFIG_FILE = Line48.CONFIG_FILE;
    static final String[] SCHEMA_SCRIPTS = Line48.SCHEMA_SCRIPTS;
    static final String[] BASELINE = Line48.BASELINE;
    /** The script executors ({@code ScriptFactory}: 4.11 renamed the classes of 4.8-4.10). */
    static final ScriptHook48.Api SCRIPT_API = new ScriptHook48.Api(
            "com.evolveum.midpoint.model.common.expression.script.ScriptFactory", "executorMap",
            "com.evolveum.midpoint.model.common.expression.script.ScriptExecutor", "execute",
            "com.evolveum.midpoint.model.common.expression.script.ScriptExecutionContext");

    /**
     * {@code expressions/safeScriptingLanguagesOnly} of midPoint's configuration file, as the server's, required:
     * true, only MEL and safe Velocity run, as the {@code config.xml} of a fresh 4.11 installation has it; false,
     * every scripting language (Groovy...), as a deployment upgraded to 4.11 keeps it without the setting.
     */
    static final String SAFE_SCRIPTING_LANGUAGES_ONLY = "tester.safeScriptingLanguagesOnly";

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
        String safeOnly = System.getProperty(SAFE_SCRIPTING_LANGUAGES_ONLY, "").trim();
        if (safeOnly.isEmpty() || safeOnly.startsWith("${")) {
            throw new IllegalStateException(SAFE_SCRIPTING_LANGUAGES_ONLY + " is not set: whether the midPoint server"
                    + " runs only MEL and safe Velocity (expressions/safeScriptingLanguagesOnly of its config.xml, true"
                    + " in a fresh 4.11 installation) or Groovy too; set <" + SAFE_SCRIPTING_LANGUAGES_ONLY
                    + ">true</" + SAFE_SCRIPTING_LANGUAGES_ONLY + "> or false in the POM (the parent passes it to the"
                    + " test JVM)");
        }
        if (!safeOnly.equals("true") && !safeOnly.equals("false")) {
            throw new IllegalStateException(SAFE_SCRIPTING_LANGUAGES_ONLY + " \"" + safeOnly + "\" is not true or false");
        }
        return Line48.config(jdbcUrl, user, extensionDir, connectorDir,
                "    <expressions><safeScriptingLanguagesOnly>" + safeOnly + "</safeScriptingLanguagesOnly></expressions>\n");
    }

    static Harness startHarness() {
        return Line48.startHarness();
    }

    static LineDrivers drivers(Harness harness) {
        return new Drivers48((Harness48) harness);
    }

    // ---- midPoint API that differs between the 4.8+ lines -------------------------------------------------------

    /**
     * The repository's own caches (ext items, URIs), reloaded from the restored database. 4.11's
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

    /** The expression of an ad-hoc script ({@code makeExpression}: 4.11 takes the profile from its origin). */
    static <V extends PrismValue, D extends ItemDefinition<?>> Expression<V, D> makeExpression(ExpressionFactory factory,
            ExpressionType expression, D output, String description, Task task, OperationResult result)
            throws CommonException {
        return factory.makeExpression(expression, output, description, task, result);
    }

    /** A filter's expressions evaluated as the GUI does ({@code evaluateFilterExpressions}, without a profile in 4.11). */
    static ObjectFilter evaluateFilterExpressions(ObjectFilter filter, VariablesMap variables, ExpressionFactory factory,
            String description, Task task, OperationResult result) throws CommonException {
        return ExpressionUtil.evaluateFilterExpressions(filter, variables, factory, description, task, result);
    }

    /** Every midPoint cache invalidated, on this node only ({@code ClusterwideCacheInvalidationDispatcher}, 4.11). */
    static void invalidateCaches(Harness48 harness) {
        harness.bean(ClusterwideCacheInvalidationDispatcher.class).dispatchInvalidation(null, null, false, null);
    }

    /**
     * The expressions in a bean (an expression, a bulk action) trusted with the full expression profile, as
     * {@code PostInitialDataImport} trusts its scripts: 4.11 runs no expression without a trust descriptor.
     */
    static void trustFully(Object bean) {
        TrustDescriptorSetter.setDescriptors((JaxbVisitable) bean, MidPointTrustDescriptor.trusted());
    }

    /** The expressions in a bean trusted as the logged-in user, as the GUI's bulk action page does (4.11). */
    static void trustAsCurrentPrincipal(Object bean) {
        TrustDescriptorSetter.setDescriptors((JaxbVisitable) bean, MidPointTrustDescriptor.forCurrentPrincipal());
    }

    /** See {@code Line48.currentChannel}. */
    static String currentChannel() {
        return Line48.currentChannel();
    }
}
