package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.notifications.api.EventProcessingContext;
import com.evolveum.midpoint.notifications.impl.helpers.ExpressionFilterHelper;
import com.evolveum.midpoint.schema.config.ConfigurationItem;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.xml.ns._public.common.common_3.BaseEventHandlerType;

/**
 * The {@code expressionFilterHelper} bean of 4.8+ ({@link RecorderInstaller}): evaluates the expression filters of a
 * notifier or event handler in its scope ({@link Recorder#inNotifierScope}), since midPoint gives their evaluation
 * only the approximate origin of the whole notification configuration. Otherwise midPoint's own helper.
 */
public class ScopedExpressionFilterHelper extends ExpressionFilterHelper {

    @Override
    public boolean processEvent(ConfigurationItem<? extends BaseEventHandlerType> handlerConfig,
            EventProcessingContext<?> ctx, OperationResult result) {
        try {
            return Recorder.inNotifierScope(handlerConfig.value(), () -> super.processEvent(handlerConfig, ctx, result));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
