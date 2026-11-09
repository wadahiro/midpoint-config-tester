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
import javax.xml.bind.JAXBElement;

import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.common.expression.script.ScriptEvaluator;
import com.evolveum.midpoint.model.common.expression.script.ScriptExpressionEvaluationContext;
import com.evolveum.midpoint.model.common.expression.script.ScriptExpressionFactory;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.schema.SchemaConstantsGenerated;
import com.evolveum.midpoint.schema.constants.ExpressionConstants;
import com.evolveum.midpoint.schema.expression.TypedValue;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ScriptExpressionEvaluatorType;
import com.github.wadahiro.midpoint.scan.EvaluatorFingerprint;
import com.github.wadahiro.midpoint.tester.Evaluation;
import com.github.wadahiro.midpoint.tester.HookRun;

/**
 * Records the scripts midPoint runs without an {@code ExpressionFactory} (so {@link EvaluatorHook} does not see them):
 * scripting model hooks, {@code execute-script} bulk actions, report scripts. They are created by the
 * {@code ScriptExpressionFactory} directly; its language evaluators are wrapped here, and a run that is not already
 * the script of a recorded {@code <script>} expression is recorded as an expression of its own (report scripts are
 * located by their form as configured, see {@code fingerprint}).
 *
 * A hook's script run records the hook's name and, at the start, the Clockwork state and focus ({@link HookRun}).
 */
final class ScriptHook40 {

    private ScriptHook40() {
    }

    @SuppressWarnings("unchecked")
    static void install(ScriptExpressionFactory factory, Function<Object, String> serializer) {
        try {
            Field field = ScriptExpressionFactory.class.getDeclaredField("evaluatorMap");
            field.setAccessible(true);
            Map<String, ScriptEvaluator> evaluators = (Map<String, ScriptEvaluator>) field.get(factory);
            for (Map.Entry<String, ScriptEvaluator> entry : evaluators.entrySet()) {
                ScriptEvaluator evaluator = entry.getValue();
                if (!Proxy.isProxyClass(evaluator.getClass())) {
                    entry.setValue((ScriptEvaluator) Proxy.newProxyInstance(ScriptEvaluator.class.getClassLoader(),
                            new Class<?>[] { ScriptEvaluator.class }, new Handler(evaluator, serializer)));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot hook the script evaluators", e);
        }
    }

    private static final class Handler implements InvocationHandler {

        private static final String NAMED_HOOK = "hook '";
        private static final String UNNAMED_HOOK = "scripting hook in system configuration";

        private final ScriptEvaluator evaluator;
        private final Function<Object, String> serializer;

        Handler(ScriptEvaluator evaluator, Function<Object, String> serializer) {
            this.evaluator = evaluator;
            this.serializer = serializer;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"evaluate".equals(method.getName()) || args == null || args.length != 1
                    || !(args[0] instanceof ScriptExpressionEvaluationContext)) {
                return call(method, args);
            }
            ScriptExpressionEvaluationContext context = (ScriptExpressionEvaluationContext) args[0];
            Record record = isRecordedExpression(context) ? null : begin(context);
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
                return method.invoke(evaluator, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /**
         * Whether this is the script of the {@code <script>} expression being recorded on this thread (its
         * description is the expression's, prefixed with {@code (old)}/{@code (new)} for a single-shot evaluation).
         */
        private static boolean isRecordedExpression(ScriptExpressionEvaluationContext context) {
            Record current = Recorder.current();
            return current != null && current.kind == Evaluation.Kind.EXPRESSION
                    && current.fingerprint != null && current.fingerprint.startsWith("script")
                    && current.description != null && context.getContextDescription() != null
                    && context.getContextDescription().endsWith(current.description);
        }

        private Record begin(ScriptExpressionEvaluationContext context) {
            Record record = Recorder.begin(Evaluation.Kind.EXPRESSION);
            if (record == null) {
                return null;
            }
            try {
                ScriptExpressionEvaluatorType bean = context.getExpressionType();
                record.description = context.getContextDescription();
                if (bean != null) {
                    record.fingerprint = fingerprint(bean);
                }
                if (context.getVariables() != null) {
                    for (Map.Entry<String, TypedValue> e : context.getVariables().entrySet()) {
                        if (e.getKey() != null) {
                            record.variables.put(e.getKey(), e.getValue() != null ? e.getValue().getValue() : null);
                        }
                    }
                }
                String hook = hookName(record.description);
                if (hook != null) {
                    record.hook = hookRun(record, hook.isEmpty() ? null : hook);
                }
            } catch (RuntimeException e) {
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
