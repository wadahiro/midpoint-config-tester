package com.github.wadahiro.midpoint.tester.internal;

import java.time.Instant;

import com.evolveum.midpoint.common.Clock;
import com.evolveum.midpoint.model.api.ActivitySubmissionOptions;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivityDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusValidityScanWorkDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TriggerScanWorkDefinitionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.WorkDefinitionsType;
import com.github.wadahiro.midpoint.tester.Run;
import com.github.wadahiro.midpoint.tester.Time;

/** {@link Time} on 4.8: midPoint's {@link Clock} override, scanners as single-run activity tasks. */
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
            String oid = mp.bean(ModelInteractionService.class).submit(new ActivityDefinitionType().work(work),
                    ActivitySubmissionOptions.create().withTaskTemplate(new TaskType().name(name + " (tester)")),
                    task, result);
            return new MidPointImpl.Outcome(oid, null);
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
