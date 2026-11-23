package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.NewObjectForm;
import com.github.wadahiro.midpoint.configtester.ObjectForm;

/**
 * A save that runs longer than a few of the GUI's progress refreshes (fixtures/forms/slow-save.xml: the archetype's
 * template sleeps one and a half seconds each time it is evaluated): the form waits for its execution in the background to end, so the organization it
 * creates is saved, not reported as not saved while the execution still runs, and one that fails is rejected with
 * the operation's error.
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class SlowSaveFormScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "slow-save.xml");

    @Test
    void aSlowSaveIsWaitedFor(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        NewObjectForm.Submit submit = t.newObjectForm(OrgType.class).archetype("form-slow-category")
                .parent("form-slow-site").set("name", "slow-category-1").submit();

        submit.saves();
        assertThat(submit.saved()).isTrue();
        assertThat(submit.outcome()).isEqualTo(ObjectForm.Outcome.SAVED);
        t.org("slow-category-1").exists().hasItem("description", "computed slowly");
    }

    @Test
    void aSlowSaveThatFailsIsRejectedWithItsError(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        NewObjectForm.Submit submit = t.newObjectForm(OrgType.class).archetype("form-slow-failing-category")
                .parent("form-slow-site").set("name", "slow-category-3").submit();

        assertThat(submit.saved()).isFalse();
        assertThat(submit.outcome()).isEqualTo(ObjectForm.Outcome.REJECTED);
        submit.rejectsWithMessageContaining("The slow computation failed");
        t.org("slow-category-3").doesNotExist();
    }

    @Test
    void theOldDesignOf44WaitsForASlowSaveToo(MidPointConfigTester t) {
        assumeTrue(t.line().equals("4.4"), "only 4.4 has both designs (useNewDesign)");
        t.midpoint().importObject(FIXTURE);
        t.midpoint().importObject(new File(Fixtures.dir("forms"), "slow-save-old-design.xml"));

        t.actor("form-slow-old-design-admin").newObjectForm(OrgType.class).archetype("form-slow-category")
                .parent("form-slow-site").set("name", "slow-category-2").submit().saves();
        t.org("slow-category-2").exists().hasItem("description", "computed slowly");
    }
}
