package com.github.wadahiro.midpoint.tester.internal;

import java.time.Instant;

import com.evolveum.midpoint.common.Clock;
import com.evolveum.midpoint.model.api.ModelPublicConstants;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskManager;
import com.github.wadahiro.midpoint.tester.Run;

/** {@link Time} on 4.0: midPoint's {@link Clock} override, scanners as single-run tasks with the scanner handlers. */
final class TimeImpl implements Time {

    private final MidPointImpl mp;

    TimeImpl(MidPointImpl mp) {
        this.mp = mp;
    }

    @Override
    public Instant now() {
        return Instant.ofEpochMilli(clock().currentTimeMillis());
    }

    @Override
    public void advance(String duration) {
        // Quartz keeps the real time: recurring tasks run only through runDueTasks / runTask until the reset
        TaskSchedules.holdRegularRuns();
        clock().overrideDuration(duration);
        TimeAgent.sync(clock());
    }

    @Override
    public void reset() {
        clock().resetOverride();
        TimeAgent.sync(clock());
    }

    @Override
    public boolean shiftsTheJvmClock() {
        return TimeAgent.failure() == null;
    }

    @Override
    public Run runTriggerScanner() {
        return submit("trigger scanner", ModelPublicConstants.TRIGGER_SCANNER_TASK_HANDLER_URI);
    }

    @Override
    public Run runValidityScanner() {
        return submit("validity scanner", ModelPublicConstants.FOCUS_VALIDITY_SCANNER_TASK_HANDLER_URI);
    }

    private Run submit(String name, String handlerUri) {
        return mp.runAs(null, "run " + name, (task, result) -> {
            // 4.0 has no activities: a new single-run task with the scanner's handler, switched to the background
            Task scanner = mp.harness.createTask(name + " (tester)");
            scanner.setName(name + " (tester)");
            scanner.setHandlerUri(handlerUri);
            scanner.makeSingle();
            mp.bean(TaskManager.class).switchToBackground(scanner, result);
            return new MidPointImpl.Outcome(scanner.getOid(), null);
        });
    }

    @Override
    public Run runDueTasks() {
        return mp.runDueTasks();
    }

    private Clock clock() {
        return mp.bean(Clock.class);
    }
}
