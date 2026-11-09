package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.evolveum.midpoint.model.common.mapping.AbstractMappingImpl;
import com.evolveum.midpoint.model.common.mapping.RecordingMappingImpl;
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
 * The recorder hooks of 4.4: fills a {@link Record} when a mapping ({@link RecordingMappingImpl}) or an expression
 * evaluator ({@link EvaluatorHook}) runs.
 *
 * 4.4 has no {@code ConfigurationItemOrigin}: a mapping's owner is its origin object (template, role, resource) and
 * its position is found by {@link MappingPlacement}.
 */
final class Recorder44 implements EvaluatorHook.Listener, RecordingMappingImpl.Listener {

    private final Field conditionResultOld;
    private final Field conditionResultNew;

    Recorder44() {
        try {
            conditionResultOld = AbstractMappingImpl.class.getDeclaredField("conditionResultOld");
            conditionResultNew = AbstractMappingImpl.class.getDeclaredField("conditionResultNew");
            conditionResultOld.setAccessible(true);
            conditionResultNew.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Unexpected mapping implementation", e);
        }
    }

    // ---- mappings -------------------------------------------------------------------------------------------

    @Override
    public Object started(RecordingMappingImpl<?, ?> mapping) {
        Record record = Recorder.begin(Evaluation.Kind.MAPPING);
        if (record == null) {
            return null;
        }
        try {
            MappingType bean = mapping.getMappingBean();
            record.mappingName = bean.getName();
            record.description = mapping.getContextDescription();
            identify(record, mapping, bean);
        } catch (RuntimeException e) {
            failed(e);
        }
        return record;
    }

    @Override
    public void finished(Object token, RecordingMappingImpl<?, ?> mapping, Throwable error, long nanos) {
        Record record = (Record) token;
        if (record == null) {
            return;
        }
        record.nanos = nanos;
        try {
            record.target = mapping.getOutputPath() != null ? mapping.getOutputPath().toString()
                    : mapping.getMappingQName() != null ? mapping.getMappingQName().getLocalPart() : null;
            variables(record, mapping.recordedVariables());
            sources(record, mapping.recordedSources());
            record.conditionOld = (Boolean) conditionResultOld.get(mapping);
            record.conditionNew = (Boolean) conditionResultNew.get(mapping);
            output(record, mapping.getOutputTriple());
            record.error = error;
        } catch (RuntimeException | IllegalAccessException e) {
            failed(e);
        } finally {
            Recorder.end(record);
        }
    }

    /** Owner (the mapping's origin object) and position ({@link MappingPlacement}). */
    private static void identify(Record record, RecordingMappingImpl<?, ?> mapping, MappingType bean) {
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
            record.part = context.getLocalContextDescription();
            record.description = context.getContextDescription();
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
