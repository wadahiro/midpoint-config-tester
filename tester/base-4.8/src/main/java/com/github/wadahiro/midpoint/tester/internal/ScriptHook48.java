package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import jakarta.xml.bind.JAXBElement;

import org.springframework.beans.factory.BeanFactory;

import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.schema.SchemaConstantsGenerated;
import com.evolveum.midpoint.schema.constants.ExpressionConstants;
import com.evolveum.midpoint.schema.expression.TypedValue;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ScriptExpressionEvaluatorType;
import com.github.wadahiro.midpoint.scan.EvaluatorFingerprint;
import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.HookRun;

/**
 * Records the scripts midPoint runs without an {@code ExpressionFactory} (so {@link EvaluatorHook} does not see them):
 * scripting model hooks, {@code execute-script} bulk actions, report scripts. They are created by the
 * script factory directly; its language executors (MEL's too, from 4.11) are wrapped here, and a run that is not
 * already the script of a recorded {@code <script>} expression is recorded as an expression of its own (report
 * scripts are located by their form as configured, see {@code fingerprint}).
 *
 * A hook's script run records the hook's name and, at the start, the Clockwork state and focus ({@link HookRun}).
 */
final class ScriptHook48 {

    /**
     * Where a line keeps its script executors, by name (4.11 renamed the classes): the factory bean, its map of
     * executors by language, their interface and its method that runs a script, and the context it takes, which has
     * {@code getScriptBean()}, {@code getContextDescription()} and {@code getVariables()}.
     */
    record Api(String factory, String executors, String executor, String run, String context) {
    }

    private ScriptHook48() {
    }

    @SuppressWarnings("unchecked")
    static void install(BeanFactory beanFactory, Api api, Function<Object, String> serializer) {
        try {
            Class<?> factoryClass = Class.forName(api.factory());
            Class<?> executorClass = Class.forName(api.executor());
            Class<?> contextClass = Class.forName(api.context());
            Context context = new Context(contextClass.getDeclaredMethod("getScriptBean"),
                    contextClass.getMethod("getContextDescription"), contextClass.getMethod("getVariables"));
            context.bean.setAccessible(true);
            Field field = factoryClass.getDeclaredField(api.executors());
            field.setAccessible(true);
            Map<String, Object> executors = (Map<String, Object>) field.get(beanFactory.getBean(factoryClass));
            for (Map.Entry<String, Object> entry : executors.entrySet()) {
                Object executor = entry.getValue();
                if (!Proxy.isProxyClass(executor.getClass())) {
                    entry.setValue(Proxy.newProxyInstance(executorClass.getClassLoader(), new Class<?>[] { executorClass },
                            new Handler(executor, api.run(), contextClass, context, serializer)));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot hook the script executors", e);
        }
    }

    /** The accessors of a script run's context. */
    private record Context(Method bean, Method description, Method variables) {
    }

    private static final class Handler implements InvocationHandler {

        private static final String NAMED_HOOK = "hook '";
        private static final String UNNAMED_HOOK = "scripting hook in system configuration";

        private final Object executor;
        private final String run;
        private final Class<?> contextClass;
        private final Context context;
        private final Function<Object, String> serializer;

        Handler(Object executor, String run, Class<?> contextClass, Context context,
                Function<Object, String> serializer) {
            this.executor = executor;
            this.run = run;
            this.contextClass = contextClass;
            this.context = context;
            this.serializer = serializer;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!run.equals(method.getName()) || args == null || args.length != 1 || !contextClass.isInstance(args[0])) {
                return call(method, args);
            }
            String description = (String) context.description.invoke(args[0]);
            Record record = isRecordedExpression(description) ? null : begin(args[0], description);
            Object output = null;
            Throwable error = null;
            try {
                output = call(method, args);
                return output;
            } catch (Throwable e) {
                error = e;
                throw e;
            } finally {
                end(record, output, error);
            }
        }

        private Object call(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(executor, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /**
         * Whether this is the script of the {@code <script>} expression being recorded on this thread (its
         * description is the expression's, prefixed with {@code (old)}/{@code (new)} for a single-shot evaluation).
         */
        private static boolean isRecordedExpression(String description) {
            Record current = Recorder.current();
            return current != null && current.kind == Evaluation.Kind.EXPRESSION
                    && current.fingerprint != null && current.fingerprint.startsWith("script")
                    && current.description != null && description != null
                    && description.endsWith(current.description);
        }

        private Record begin(Object run, String description) {
            Record record = Recorder.begin(Evaluation.Kind.EXPRESSION);
            if (record == null) {
                return null;
            }
            try {
                ScriptExpressionEvaluatorType bean = (ScriptExpressionEvaluatorType) context.bean.invoke(run);
                record.description = description;
                if (bean != null) {
                    record.fingerprint = fingerprint(bean);
                }
                VariablesMap variables = (VariablesMap) context.variables.invoke(run);
                if (variables != null) {
                    for (Map.Entry<String, TypedValue<?>> e : variables.entrySet()) {
                        if (e.getKey() != null) {
                            record.variables.put(e.getKey(), e.getValue() != null ? e.getValue().getValue() : null);
                        }
                    }
                }
                String hook = hookName(record.description);
                if (hook != null) {
                    record.hook = hookRun(record, hook.isEmpty() ? null : hook);
                }
            } catch (RuntimeException | ReflectiveOperationException e) {
                System.err.println("Evaluation recorder failed: " + e);
            }
            return record;
        }

        /**
         * The canonical form of the script as configured: the report service sets {@code objectVariableMode} on the
         * bean before it runs it, so without that element unless a configuration file has it.
         */
        private String fingerprint(ScriptExpressionEvaluatorType bean) {
            String asRun = EvaluatorFingerprint.of(serializer.apply(new JAXBElement<>(
                    SchemaConstantsGenerated.C_SCRIPT, ScriptExpressionEvaluatorType.class, bean)));
            if (bean.getObjectVariableMode() == null || ConfigIndex.get().isKnownFingerprint(asRun)) {
                return asRun;
            }
            ScriptExpressionEvaluatorType copy = bean.clone();
            copy.setObjectVariableMode(null);
            return EvaluatorFingerprint.of(serializer.apply(new JAXBElement<>(
                    SchemaConstantsGenerated.C_SCRIPT, ScriptExpressionEvaluatorType.class, copy)));
        }

        private static void end(Record record, Object output, Throwable error) {
            if (record == null) {
                return;
            }
            try {
                record.hasOutput = output != null;
                if (output instanceof List) {
                    List<Object> values = new ArrayList<>();
                    for (Object value : (List<?>) output) {
                        values.add(value instanceof PrismValue ? ((PrismValue) value).getRealValue() : value);
                    }
                    record.zero = values;
                }
                record.error = error;
            } catch (RuntimeException e) {
                System.err.println("Evaluation recorder failed: " + e);
            } finally {
                Recorder.end(record);
            }
        }

        /**
         * The hook's name if the description is one {@code ClockworkHookHelper} gives a hook's script
         * ({@code hook '<name>'}), empty for a hook without a name, else null. The script bean is not a container
         * value, so its place in the system configuration cannot be followed; its location comes from its
         * canonical form, as for other expressions.
         */
        private static String hookName(String description) {
            if (description == null) {
                return null;
            }
            if (description.startsWith(NAMED_HOOK) && description.endsWith("'")) {
                return description.substring(NAMED_HOOK.length(), description.length() - 1);
            }
            return UNNAMED_HOOK.equals(description) ? "" : null;
        }

        /** The Clockwork state and focus now: the model context is live and moves on after the hook. */
        private static HookRun hookRun(Record record, String hookName) {
            Object modelContext = record.variables.get(ExpressionConstants.VAR_MODEL_CONTEXT);
            Object focus = record.variables.get(ExpressionConstants.VAR_FOCUS);
            String state = modelContext instanceof ModelContext
                    ? ((ModelContext<?>) modelContext).getState().toModelStateType().value() : null;
            String focusType = null;
            String focusName = null;
            if (focus instanceof PrismObject) {
                PrismObject<?> object = (PrismObject<?>) focus;
                focusType = object.getCompileTimeClass() != null ? object.getCompileTimeClass().getSimpleName() : null;
                focusName = object.getName() != null ? object.getName().getOrig() : null;
            }
            return new HookRun(hookName, state, focusType, focusName, record.view);
        }
    }
}
