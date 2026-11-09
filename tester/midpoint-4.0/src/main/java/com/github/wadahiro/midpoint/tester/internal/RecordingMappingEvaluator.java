package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.model.common.mapping.MappingImpl;
import com.evolveum.midpoint.model.impl.lens.LensContext;
import com.evolveum.midpoint.model.impl.lens.LensProjectionContext;
import com.evolveum.midpoint.model.impl.lens.projector.mappings.MappingEvaluator;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SecurityViolationException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * midPoint 4.0's {@code mappingEvaluator} bean (model-impl), whose mapping evaluations report their start and end
 * ({@link RecorderInstaller} sets this class on the bean definition). On 4.0 a mapping cannot be subclassed
 * ({@code MappingImpl}'s constructor is private, its builder final), but the model evaluates every data mapping
 * through this bean ({@code MappingImpl.evaluate} has no other caller in the model but the diagnostic
 * {@code MappingDiagEvaluator}); the overloads delegate to this method.
 */
@SuppressWarnings("rawtypes")
public class RecordingMappingEvaluator extends MappingEvaluator {

    /** The recorder; must not throw. */
    public interface Listener {
        Object started(MappingImpl<?, ?> mapping);

        /** {@code nanos}: how long midPoint's evaluation took (the recorder's own work excluded). */
        void finished(Object token, MappingImpl<?, ?> mapping, Throwable error, long nanos);
    }

    private static volatile Listener listener;

    public static void listener(Listener newListener) {
        listener = newListener;
    }

    @Override
    public <V extends PrismValue, D extends ItemDefinition, F extends ObjectType> void evaluateMapping(
            MappingImpl<V, D> mapping, LensContext<F> lensContext, LensProjectionContext projContext, Task task,
            OperationResult parentResult) throws ExpressionEvaluationException, ObjectNotFoundException,
            SchemaException, SecurityViolationException, ConfigurationException, CommunicationException {
        Listener current = listener;
        Object token = current != null ? current.started(mapping) : null;
        Throwable error = null;
        long start = System.nanoTime();
        try {
            super.evaluateMapping(mapping, lensContext, projContext, task, parentResult);
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            if (current != null) {
                current.finished(token, mapping, error, System.nanoTime() - start);
            }
        }
    }
}
