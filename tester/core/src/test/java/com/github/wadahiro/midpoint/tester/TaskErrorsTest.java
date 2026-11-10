package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.tester.internal.Recorder;

/**
 * A task run that fails on one of its objects fails the driver call that ran it, as the task fails in midPoint, and
 * says which object failed and why: midPoint's result of the run only counts the failures.
 */
@MidPointConfigTest(Fixtures.BARE)
class TaskErrorsTest {

    @TempDir
    static Path tmp;

    @Test
    void aReconciliationThatFailsOnAnAccountFailsWithTheAccountAndWhy(MidPointConfigTester t) throws IOException {
        MidPoint mp = failingHr(t);

        assertThatThrownBy(() -> mp.reconcile("HR"))
                .isInstanceOf(OperationFailed.class)
                .hasMessageContaining("last failure: bad")
                .hasMessageContaining("cannot take Broken");
        assertThat(mp.user("good")).isNotNull();
        assertThat(mp.user("bad")).isNull();
    }

    @Test
    void aTaskRunThatFailsOnSomeObjectsPassesWhenPartialErrorsAreAllowed(MidPointConfigTester t) throws IOException {
        MidPoint mp = failingHr(t);
        assertThatThrownBy(() -> mp.reconcile("HR")).isInstanceOf(OperationFailed.class);   // creates the task

        assertThatThrownBy(() -> t.task(RECONCILE_HR).run())
                .isInstanceOf(OperationFailed.class)
                .hasMessageContaining("PARTIAL_ERROR")
                .hasMessageContaining("last failure: bad");
        t.task(RECONCILE_HR).allowPartialErrors().run();
        assertThat(mp.user("good")).isNotNull();
        assertThat(mp.user("bad")).isNull();
    }

    @Test
    void dueTasksThatFailOnSomeObjectsFailUnlessPartialErrorsAreAllowed(MidPointConfigTester t) throws IOException {
        MidPoint mp = failingHr(t);
        mp.importObject(Fixtures.of(mp, new File(Fixtures.dir("async"), "task-reconcile-hr.xml")));   // held

        assertThatThrownBy(() -> t.clock().runDue())   // never ran: due
                .isInstanceOf(OperationFailed.class)
                .hasMessageContaining("reconcile HR hourly")
                .hasMessageContaining("last failure: bad");
        assertThat(mp.user("good")).isNotNull();
        int runs = Recorder.taskRuns(HOURLY);
        t.clock().advance("PT2H").allowPartialErrors().runDue();
        assertThat(Recorder.taskRuns(HOURLY) - runs).as("runs of the due task").isEqualTo(1);
    }

    static final String HOURLY = "10000000-0000-0000-0000-00000000f030";

    static final String RECONCILE_HR = "reconcile HR (tester)";

    /** The HR resource of the fixtures, whose mapping cannot take the last name Broken, with a good and a bad row. */
    private static MidPoint failingHr(MidPointConfigTester t) throws IOException {
        MidPoint mp = t.midpoint();
        Path resource = tmp.resolve("resource-hr-failing.xml");
        String hr = Files.readString(Fixtures.of(mp, new File(Fixtures.ROOT, "resource-hr.xml")).toPath(),
                StandardCharsets.UTF_8);
        String failing = hr.replace("last ? last.toLowerCase().capitalize() : null",
                "if (last == 'Broken') { throw new IllegalStateException('cannot take Broken') }\n"
                        + "last ? last.toLowerCase().capitalize() : null");
        assertThat(failing).isNotEqualTo(hr);
        Files.writeString(resource, failing, StandardCharsets.UTF_8);
        TargetSystem target = mp.importResource(resource.toFile());
        target.addAccount("good", Map.of("fullname", "Good Person"));
        target.addAccount("bad", Map.of("fullname", "Bad Broken"));
        return mp;
    }
}
