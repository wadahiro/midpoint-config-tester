package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.internal.Checkpoint;

/**
 * A constant of a deployed function library's script changed for one test ({@code t.config()}, the sample deployment
 * {@code scenarios/library}): the next evaluation runs the changed code, a restore to an earlier checkpoint and the
 * next test run the library as deployed.
 */
@MidPointConfigTest("classpath:scenarios/library/midpoint-config-tester.yaml")
class ConfigScenarioTest {

    private static final String DEPLOYED = "standard (limit 3)";

    @Test
    void theLibraryRunsAsDeployed(MidPointConfigTester t) {
        // whatever test ran before in this JVM: its change is gone
        t.user("alice").add();
        t.user("alice").assign("Labelled");
        t.user("alice").hasItem("description", DEPLOYED);
        assertThat(t.config().functionLibrary("exampleLibrary").function("label").code())
                .contains("int LIMIT = 3").doesNotContain("int LIMIT = 2");
    }

    @Test
    void theNextEvaluationRunsTheChangedConstant(MidPointConfigTester t) {
        t.user("alice").add();
        t.user("alice").assign("Labelled");
        t.user("alice").hasItem("description", DEPLOYED);

        Config.LibraryFunction label = t.config().functionLibrary("exampleLibrary").function("label")
                .replace("int LIMIT = 3", "int LIMIT = 2");
        assertThat(label.code()).contains("int LIMIT = 2");

        // no reset in between: the function library and its compiled script are not served from midPoint's caches
        t.user("alice").recompute();
        t.user("alice").hasItem("description", "standard (limit 2)");
        // and the change goes on with the next one
        label.replace("'standard'", "'lowered'");
        t.user("bob").add();
        t.user("bob").assign("Labelled");
        t.user("bob").hasItem("description", "lowered (limit 2)");
    }

    @Test
    void aRestoreToAnEarlierCheckpointUndoesTheChange(MidPointConfigTester t) {
        t.user("alice").add();
        t.user("alice").assign("Labelled");
        Checkpoint checkpoint = t.deployment().checkpoint();
        try {
            t.config().functionLibrary("exampleLibrary").function("label").replace("int LIMIT = 3", "int LIMIT = 2");
            t.user("alice").recompute();
            t.user("alice").hasItem("description", "standard (limit 2)");

            t.deployment().restore(checkpoint);

            t.user("alice").hasItem("description", DEPLOYED);
            t.user("alice").recompute();
            t.user("alice").hasItem("description", DEPLOYED);
            assertThat(t.config().functionLibrary("exampleLibrary").function("label").code())
                    .contains("int LIMIT = 3");
        } finally {
            t.deployment().release(checkpoint);
        }
    }

    @Test
    void aTextThatIsNotThereExactlyOnceFails(MidPointConfigTester t) {
        Config.LibraryFunction label = t.config().functionLibrary("exampleLibrary").function("label");
        assertThatThrownBy(() -> label.replace("int LIMIT = 300", "int LIMIT = 2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occurs 0 times");
        assertThatThrownBy(() -> label.replace("LIMIT", "MAX"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("occurs 2 times");
        assertThat(label.code()).contains("int LIMIT = 3");
    }

    @Test
    void onlyAScriptFunctionOfAnExistingLibraryCanBeChanged(MidPointConfigTester t) {
        assertThatThrownBy(() -> t.config().functionLibrary("missingLibrary"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no function library missingLibrary");
        assertThatThrownBy(() -> t.config().functionLibrary("exampleLibrary").function("missing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[label, fixed]");
        assertThatThrownBy(() -> t.config().functionLibrary("exampleLibrary").function("fixed"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0 script evaluators");
    }
}
