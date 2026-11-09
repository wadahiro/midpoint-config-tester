package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.notifications.api.events.Event;
import com.evolveum.midpoint.notifications.impl.helpers.ExpressionFilterHelper;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.EventHandlerType;

/**
 * The {@code expressionFilterHelper} bean of 4.0 and 4.4 ({@code RecorderInstaller}): evaluates the expression
 * filters of a notifier or event handler in its scope ({@link Recorder#inNotifierScope}), since midPoint says nothing
 * about which notifier their evaluation is for. Otherwise midPoint's own helper.
 */
public class ScopedExpressionFilterHelper extends ExpressionFilterHelper {

    @Override
    public boolean processEvent(Event event, EventHandlerType eventHandlerType, Task task, OperationResult result) {
        try {
            return Recorder.inNotifierScope(eventHandlerType,
                    () -> super.processEvent(event, eventHandlerType, task, result));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
