package com.evolveum.midpoint.model.common.mapping;

import java.util.List;

import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.repo.common.expression.Source;
import com.evolveum.midpoint.schema.config.ConfigurationItemOrigin;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SecurityViolationException;

/**
 * Tester class (midpoint-config-tester), not part of midPoint: a {@link MappingImpl} that tells the tester's
 * recorder when its evaluation starts and ends. It lives in midPoint's package only because {@link MappingImpl}'s
 * constructor and the mapping's parts ({@code mappingConfigItem}, {@code variables}, {@code sources}) are
 * package-private; it changes nothing else.
 *
 * Created by the tester's {@code RecordingMappingFactory} (which replaces the {@code mappingFactory} bean), so it
 * covers every data mapping midPoint builds through its factory. A {@link #clone()} is a plain {@link MappingImpl}:
 * midPoint clones mappings only after their evaluation.
 */
public class RecordingMappingImpl<V extends PrismValue, D extends ItemDefinition<?>> extends MappingImpl<V, D> {

    /** The recorder; must not throw. */
    public interface Listener {
        Object started(RecordingMappingImpl<?, ?> mapping);

        /** {@code nanos}: how long midPoint's evaluation took (the recorder's own work excluded). */
        void finished(Object token, RecordingMappingImpl<?, ?> mapping, Throwable error, long nanos);
    }

    private static volatile Listener listener;

    public RecordingMappingImpl(MappingBuilder<V, D> builder) {
        super(builder);
    }

    public static void listener(Listener newListener) {
        listener = newListener;
    }

    @Override
    public void evaluate(Task task, OperationResult parentResult)
            throws ExpressionEvaluationException, ObjectNotFoundException, SchemaException,
            SecurityViolationException, ConfigurationException, CommunicationException {
        Listener current = listener;
        Object token = current != null ? current.started(this) : null;
        Throwable error = null;
        long start = System.nanoTime();
        try {
            super.evaluate(task, parentResult);
        } catch (Throwable e) {
            error = e;
            throw e;
        } finally {
            if (current != null) {
                current.finished(token, this, error, System.nanoTime() - start);
            }
        }
    }

    /** Where the mapping comes from (its config item's type differs between lines, the origin does not). */
    public ConfigurationItemOrigin recordedOrigin() {
        return mappingConfigItem.origin();
    }

    public VariablesMap recordedVariables() {
        return variables;
    }

    public List<Source<?, ?>> recordedSources() {
        return sources;
    }
}
