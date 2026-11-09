// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The reports midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialReports {

    /** "All audit records report", 090-report-audit.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ReportRef ALL_AUDIT_RECORDS_REPORT = new ReportRef("All audit records report", "00000000-0000-0000-0000-000000000080", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Certification campaigns report", 140-report-certification-campaigns.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ReportRef CERTIFICATION_CAMPAIGNS_REPORT = new ReportRef("Certification campaigns report", "00000000-0000-0000-0000-000000000140", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Certification cases report", 150-report-certification-cases.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ReportRef CERTIFICATION_CASES_REPORT = new ReportRef("Certification cases report", "00000000-0000-0000-0000-000000000150", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Certification decisions report", 160-report-certification-decisions.xml (4.4). */
    public static final ReportRef CERTIFICATION_DECISIONS_REPORT = new ReportRef("Certification decisions report", "00000000-0000-0000-0000-000000000160", Lines.only("4.4"));

    /** "Certification definitions report", 130-report-certification-definitions.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ReportRef CERTIFICATION_DEFINITIONS_REPORT = new ReportRef("Certification definitions report", "00000000-0000-0000-0000-000000000130", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Certification work items report", 160-report-certification-work-items.xml (4.8, 4.9, 4.10). */
    public static final ReportRef CERTIFICATION_WORK_ITEMS_REPORT = new ReportRef("Certification work items report", "00000000-0000-0000-0000-000000000160", Lines.only("4.8", "4.9", "4.10"));

    /** "IGA: Who has access to what and why", 200-report-indirect-assignments.xml (4.8, 4.9, 4.10). */
    public static final ReportRef IGA_WHO_HAS_ACCESS_TO_WHAT_AND_WHY = new ReportRef("IGA: Who has access to what and why", "00000000-0000-0000-0000-b8249b79d2b5", Lines.only("4.8", "4.9", "4.10"));

    /** "Reconciliation report", 100-report-reconciliation.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ReportRef RECONCILIATION_REPORT = new ReportRef("Reconciliation report", "00000000-0000-0000-0000-000000000100", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Simulation report: Items changed", 172-report-simulation-items-changed.xml (4.8, 4.9, 4.10). */
    public static final ReportRef SIMULATION_REPORT_ITEMS_CHANGED = new ReportRef("Simulation report: Items changed", "00000000-0000-0000-0000-ea32deff43df", Lines.only("4.8", "4.9", "4.10"));

    /** "Simulation report: Objects", 170-report-simulation-objects.xml (4.8, 4.9, 4.10). */
    public static final ReportRef SIMULATION_REPORT_OBJECTS = new ReportRef("Simulation report: Objects", "00000000-0000-0000-0000-286d76cea7c5", Lines.only("4.8", "4.9", "4.10"));

    /** "Simulation report: Objects with metrics", 171-report-simulation-objects-with-metrics.xml (4.8, 4.9, 4.10). */
    public static final ReportRef SIMULATION_REPORT_OBJECTS_WITH_METRICS = new ReportRef("Simulation report: Objects with metrics", "00000000-0000-0000-0000-616a5c5dbca8", Lines.only("4.8", "4.9", "4.10"));

    /** "Simulation report: Results", 180-report-simulation-results.xml (4.8, 4.9, 4.10). */
    public static final ReportRef SIMULATION_REPORT_RESULTS = new ReportRef("Simulation report: Results", "00000000-0000-0000-0000-97631b84fde7", Lines.only("4.8", "4.9", "4.10"));

    /** "Simulation report: Values changed", 173-report-simulation-values-changed.xml (4.8, 4.9, 4.10). */
    public static final ReportRef SIMULATION_REPORT_VALUES_CHANGED = new ReportRef("Simulation report: Values changed", "00000000-0000-0000-0000-61bc8211947c", Lines.only("4.8", "4.9", "4.10"));

    /** "Users in MidPoint", 110-report-user-list.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ReportRef USERS_IN_MIDPOINT = new ReportRef("Users in MidPoint", "00000000-0000-0000-0000-000000000110", Lines.only("4.4", "4.8", "4.9", "4.10"));

    private InitialReports() {
    }
}
