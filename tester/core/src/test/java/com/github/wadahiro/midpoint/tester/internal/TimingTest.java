package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The time report's JSON, as the next run reads it back to compare its phases with. */
class TimingTest {

    @Test
    void aReportsPhasesAreReadBack(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("timing.json");
        Files.writeString(json, "{\n  \"line\": \"4.8\",\n  \"phases\": {\n"
                + "    \"reset.total\": {\"count\": 12, \"totalMs\": 345.678, \"meanMs\": 28.807, \"maxMs\": 61.000},\n"
                + "    \"startup.harness (Spring, initSystem)\": {\"count\": 1, \"totalMs\": 4100.000, \"meanMs\": 4100.000,"
                + " \"maxMs\": 4100.000}\n  }\n}\n", StandardCharsets.UTF_8);

        Map<String, double[]> phases = Timing.read(json);

        assertThat(phases).containsOnlyKeys("reset.total", "startup.harness (Spring, initSystem)");
        assertThat(phases.get("reset.total")).containsExactly(12, 345.678, 28.807, 61.0);
    }

    @Test
    void noReportMeansNothingToCompareWith(@TempDir Path dir) {
        assertThat(Timing.read(dir.resolve("timing.json"))).isEmpty();
    }
}
