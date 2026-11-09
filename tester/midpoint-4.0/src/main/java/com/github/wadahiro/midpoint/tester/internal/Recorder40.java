package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.evolveum.midpoint.model.common.mapping.MappingImpl;
import com.evolveum.midpoint.prism.PrismPropertyValue;
import com.evolveum.midpoint.repo.common.expression.ExpressionUtil;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.PrismValueDeltaSetTriple;
import com.evolveum.midpoint.repo.common.expression.ExpressionEvaluationContext;
import com.evolveum.midpoint.repo.common.expression.Source;
import com.evolveum.midpoint.schema.expression.TypedValue;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.xml.ns._public.common.common_3.MappingType;
import com.github.wadahiro.midpoint.tester.Evaluation;

/**
 * The recorder hooks of 4.0: fills a {@link Record} when a mapping ({@link RecordingMappingEvaluator}) or an expression
 * evaluator ({@link EvaluatorHook}) runs.
 *
 * The mapping hook is the model's {@code mappingEvaluator} ({@link RecordingMappingEvaluator}); 4.0 has no
 * {@code ConfigurationItemOrigin}: a mapping's owner is its origin object (template, role, resource) and its position
 * is found by {@link MappingPlacement}.
 */
final class Recorder40 implements EvaluatorHook.Listener, RecordingMappingEvaluator.Listener {

    private static final String CONDITION = "condition in ";
    private static final String EXPRESSION = "expression in ";

    private final Field variables;
    private final Field sources;
    private final Field outputPath;

    Recorder40() {
        try {
            variables = MappingImpl.class.getDeclaredField("variables");
            sources = MappingImpl.class.getDeclaredField("sources");
            outputPath = MappingImpl.class.getDeclaredField("outputPath");
            variables.setAccessible(true);
            sources.setAccessible(true);
            outputPath.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Unexpected mapping implementation", e);
        }
    }

    // ---- mappings -------------------------------------------------------------------------------------------

    @Override
    public Object started(MappingImpl<?, ?> mapping) {
        Record record = Recorder.begin(Evaluation.Kind.MAPPING);
        if (record == null) {
            return null;
        }
        try {
            MappingType bean = mapping.getMappingType();
            record.mappingName = bean.getName();
            record.description = mapping.getContextDescription();
            identify(record, mapping, bean);
        } catch (RuntimeException e) {
            failed(e);
        }
        return record;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void finished(Object token, MappingImpl<?, ?> mapping, Throwable error, long nanos) {
        Record record = (Record) token;
        if (record == null) {
            return;
        }
        record.nanos = nanos;
        try {
            Object path = outputPath.get(mapping);
            record.target = path != null ? path.toString()
                    : mapping.getMappingQName() != null ? mapping.getMappingQName().getLocalPart() : null;
            variables(record, (VariablesMap) variables.get(mapping));
            sources(record, (Collection<? extends Source<?, ?>>) sources.get(mapping));
            condition(record, mapping);
            output(record, mapping.getOutputTriple());
            record.error = error;
        } catch (RuntimeException | IllegalAccessException e) {
            failed(e);
        } finally {
            Recorder.end(record);
        }
    }

    /**
     * 4.0 keeps the condition's result only in local variables: computed as {@code MappingImpl} does, from the
     * condition's output triple and the masks (no condition is true); unknown when the condition was not evaluated.
     */
    private static void condition(Record record, MappingImpl<?, ?> mapping) {
        PrismValueDeltaSetTriple<PrismPropertyValue<Boolean>> triple = mapping.getConditionOutputTriple();
        if (mapping.getMappingType().getCondition() == null) {
            record.conditionOld = mapping.isConditionMaskOld();
            record.conditionNew = mapping.isConditionMaskNew();
        } else if (triple != null) {
            record.conditionOld = ExpressionUtil.computeConditionResult(triple.getNonPositiveValues())
                    && mapping.isConditionMaskOld();
            record.conditionNew = ExpressionUtil.computeConditionResult(triple.getNonNegativeValues())
                    && mapping.isConditionMaskNew();
        }
    }

    /** Owner (the mapping's origin object) and position ({@link MappingPlacement}). */
    private static void identify(Record record, MappingImpl<?, ?> mapping, MappingType bean) {
        PrismObject<?> owner = mapping.getOriginObject() != null ? mapping.getOriginObject().asPrismObject() : null;
        PrismContainerValue<?> value = bean.asPrismContainerValue();
        if (owner == null) {
            owner = MappingPlacement.rootObject(value);
        }
        record.elementPath = MappingPlacement.place(owner, value);
        if (owner != null) {
            record.ownerType = owner.getCompileTimeClass() != null ? owner.getCompileTimeClass().getSimpleName() : null;
            record.ownerOid = owner.getOid();
            record.ownerName = owner.getName() != null ? owner.getName().getOrig() : null;
        }
    }

    // ---- expression evaluators ------------------------------------------------------------------------------

    @Override
    public Object before(String fingerprint, Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof ExpressionEvaluationContext)) {
            return null;
        }
        Record record = Recorder.begin(Evaluation.Kind.EXPRESSION);
        if (record == null) {
            return null;
        }
        try {
            ExpressionEvaluationContext context = (ExpressionEvaluationContext) args[0];
            record.fingerprint = fingerprint;
            record.description = context.getContextDescription();
            record.part = part(record);
            variables(record, context.getVariables());
            sources(record, context.getSources());
            if (record.parent != null && record.parent.kind == Evaluation.Kind.MAPPING
                    && "expression".equals(record.part)) {
                // the mapping evaluates its expression for the states its condition holds in
                record.conditionOld = !context.isSkipEvaluationMinus();
                record.conditionNew = !context.isSkipEvaluationPlus();
            }
        } catch (RuntimeException e) {
            failed(e);
        }
        return record;
    }

    @Override
    public void after(Object token, Object output, Throwable error) {
        Record record = (Record) token;
        if (record == null) {
            return;
        }
        try {
            output(record, output instanceof PrismValueDeltaSetTriple ? (PrismValueDeltaSetTriple<?>) output : null);
            record.error = error;
        } catch (RuntimeException e) {
            failed(e);
        } finally {
            Recorder.end(record);
        }
    }

    /**
     * 4.0 has no local context description: a mapping's condition and expression are told by the description the
     * mapping gives them ({@code condition in ...}, {@code expression in ...}), for the direct children of a mapping.
     */
    private static String part(Record record) {
        if (record.parent == null || record.parent.kind != Evaluation.Kind.MAPPING || record.description == null) {
            return null;
        }
        return record.description.startsWith(CONDITION) ? "condition"
                : record.description.startsWith(EXPRESSION) ? "expression" : null;
    }

    // ---- values ---------------------------------------------------------------------------------------------

    private static void variables(Record record, VariablesMap variables) {
        if (variables == null) {
            return;
        }
        for (Map.Entry<String, TypedValue> e : variables.entrySet()) {
            // the unnamed variable is the default object of relative paths, not something a script can use
            if (e.getKey() != null) {
                record.variables.put(e.getKey(), e.getValue() != null ? e.getValue().getValue() : null);
            }
        }
    }

    private static void sources(Record record, Collection<? extends Source<?, ?>> sources) {
        if (sources == null) {
            return;
        }
        for (Source<?, ?> source : sources) {
            String name = source.getName() != null ? source.getName().getLocalPart() : "?";
            record.sources.put(name, new Evaluation.SourceValues(realValues(source.getItemOld()),
                    realValues(source.getItemNew())));
        }
    }

    private static List<Object> realValues(Item<?, ?> item) {
        return item == null ? new ArrayList<>() : realValues(item.getValues());
    }

    /** The triple's sets, copied now: midPoint keeps changing the triple after the evaluation. */
    private static void output(Record record, PrismValueDeltaSetTriple<?> triple) {
        if (triple == null) {
            return;
        }
        record.hasOutput = true;
        record.zero = realValues(triple.getZeroSet());
        record.plus = realValues(triple.getPlusSet());
        record.minus = realValues(triple.getMinusSet());
    }

    private static List<Object> realValues(Collection<? extends PrismValue> values) {
        List<Object> out = new ArrayList<>();
        if (values != null) {
            for (PrismValue value : values) {
                out.add(value != null ? value.getRealValue() : null);
            }
        }
        return out;
    }

    private static void failed(Exception e) {
        // the recorder must never change what midPoint does
        System.err.println("Evaluation recorder failed: " + e);
    }
}
