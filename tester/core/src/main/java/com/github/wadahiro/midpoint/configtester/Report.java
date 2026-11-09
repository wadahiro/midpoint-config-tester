package com.github.wadahiro.midpoint.configtester;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A report of the deployment, by name ({@link Scenario#report}). midPoint 4.4+ (object collection and dashboard
 * reports); 4.0's reports are Jasper reports, which the tester does not run ({@link UnsupportedOperationException}).
 */
public final class Report {

    private final Scenario scenario;
    private final String name;

    Report(Scenario scenario, String name) {
        this.scenario = scenario;
        this.name = name;
    }

    /** Runs the report as the GUI's "Run report" (an export task, waited for) and reads its output file. */
    public ReportOutput run() {
        return run(Map.of());
    }

    /**
     * Runs it with parameters by name (a {@code java.util.Collection} value gives a parameter several values, as a
     * report task may hold them).
     */
    public ReportOutput run(Map<String, ?> parameters) {
        return new ReportOutput(name,
                scenario.deployment().api().reports().export(name, new LinkedHashMap<>(parameters)).lines());
    }

    public String name() {
        return name;
    }
}
