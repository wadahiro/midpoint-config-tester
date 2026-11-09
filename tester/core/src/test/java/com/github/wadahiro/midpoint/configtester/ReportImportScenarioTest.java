package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultStatusType;

/**
 * Report imports (4.4+) by the import script of {@code scenarios/report-import}: by the administrator, or as a user,
 * as when the user imports in the GUI (the script's {@code actor} is the user).
 */
@MidPointConfigTest("classpath:scenarios/report-import/midpoint-config-tester.yaml")
class ReportImportScenarioTest {

    static final List<String> LINES = List.of("\"name\"", "\"jdoe\"");

    @Test
    void anImportByTheAdministrator(MidPointConfigTester t) {
        assumeFalse("4.0".equals(t.midpoint().line()), "midPoint 4.0 has no report import");

        var in = t.midpoint().reports().importFrom("import-descriptions", LINES);

        assertThat(in.status()).isEqualTo(OperationResultStatusType.SUCCESS);
        t.user("jdoe").hasItem("description", "imported by administrator");
    }

    @Test
    void anImportAsAUser(MidPointConfigTester t) {
        assumeFalse("4.0".equals(t.midpoint().line()), "midPoint 4.0 has no report import");

        var in = t.midpoint().reports().importFrom("import-descriptions", LINES, "ann");

        assertThat(in.status()).isEqualTo(OperationResultStatusType.SUCCESS);
        t.user("jdoe").hasItem("description", "imported by ann");
    }
}
