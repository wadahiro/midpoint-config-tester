package com.github.wadahiro.midpoint.tester.internal;

import java.time.Instant;

import com.evolveum.midpoint.common.Clock;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivityDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusValidityScanWorkDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TriggerScanWorkDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.WorkDefinitionsType;
import com.github.wadahiro.midpoint.tester.Run;
import com.github.wadahiro.midpoint.tester.Time;

/** {@link Time} on 4.4: midPoint's {@link Clock} override, scanners as single-run activity tasks. */
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
        return submit("trigger scanner", new WorkDefinitionsType().triggerScan(new TriggerScanWorkDefinitionType()));
    }

    @Override
    public Run runValidityScanner() {
        return submit("validity scanner",
                new WorkDefinitionsType().focusValidityScan(new FocusValidityScanWorkDefinitionType()));
    }

    private Run submit(String name, WorkDefinitionsType work) {
        return mp.runAs(null, "run " + name, (task, result) -> {
            // 4.4 has no ModelInteractionService.submit: a new task with the activity, switched to the background
            Task scanner = mp.harness.createTask(name + " (tester)");
            scanner.setName(name + " (tester)");
            scanner.getUpdatedTaskObject().asObjectable().activity(new ActivityDefinitionType().work(work));
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
