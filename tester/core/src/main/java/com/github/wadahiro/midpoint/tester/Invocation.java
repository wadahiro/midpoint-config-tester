package com.github.wadahiro.midpoint.tester;

import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;

/** Code a test runs as a driver call ({@link MidPoint#invoke}), with the call's task and result. */
@FunctionalInterface
public interface Invocation<T> {

    T run(Task task, OperationResult result) throws Exception;
}
