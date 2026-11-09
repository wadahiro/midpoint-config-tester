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

/**
 * A task run that fails on one of its objects fails the driver call that ran it, as the task fails in midPoint, and
 * says which object failed and why: midPoint's result of the run only counts the failures.
 */
@MidPointTest
class TaskErrorsTest {

    @TempDir
    static Path tmp;

    @Test
    void aReconciliationThatFailsOnAnAccountFailsWithTheAccountAndWhy(MidPoint mp) throws IOException {
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

        assertThatThrownBy(() -> mp.reconcile("HR"))
                .isInstanceOf(OperationFailed.class)
                .hasMessageContaining("last failure: bad")
                .hasMessageContaining("cannot take Broken");
        assertThat(mp.user("good")).isNotNull();
        assertThat(mp.user("bad")).isNull();
    }
}
