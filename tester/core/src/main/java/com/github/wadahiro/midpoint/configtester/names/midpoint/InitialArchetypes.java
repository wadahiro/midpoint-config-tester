// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The archetypes midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialArchetypes {

    /** "Application", 029-archetype-application.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef APPLICATION = new ArchetypeRef("Application", "00000000-0000-0000-0000-000000000329", Lines.only("4.8", "4.9", "4.10"));

    /** "Application role", 028-archetype-application-role.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef APPLICATION_ROLE = new ArchetypeRef("Application role", "00000000-0000-0000-0000-000000000328", Lines.only("4.8", "4.9", "4.10"));

    /** "Approval case", 025-archetype-approval-case.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef APPROVAL_CASE = new ArchetypeRef("Approval case", "00000000-0000-0000-0000-000000000342", Lines.ALL);

    /** "Approval-related task", 521-archetype-task-approval.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef APPROVAL_RELATED_TASK = new ArchetypeRef("Approval-related task", "00000000-0000-0000-0000-000000000521", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Asynchronous update task", 505-archetype-task-async-update.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef ASYNCHRONOUS_UPDATE_TASK = new ArchetypeRef("Asynchronous update task", "00000000-0000-0000-0000-000000000505", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Business role", 022-archetype-business-role.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef BUSINESS_ROLE = new ArchetypeRef("Business role", "00000000-0000-0000-0000-000000000321", Lines.ALL);

    /** "Certification-campaign-close-current-stage task", 537-archetype-task-certification-close-current-stage.xml (4.9, 4.10). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_CLOSE_CURRENT_STAGE_TASK = new ArchetypeRef("Certification-campaign-close-current-stage task", "00000000-0000-0000-0000-000000000537", Lines.only("4.9", "4.10"));

    /** "Certification-campaign-open-next-stage task", 534-archetype-task-certification-open-next-stage.xml (4.9, 4.10). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_OPEN_NEXT_STAGE_TASK = new ArchetypeRef("Certification-campaign-open-next-stage task", "00000000-0000-0000-0000-000000000534", Lines.only("4.9", "4.10"));

    /** "Certification-campaign-reiterate task", 538-archetype-task-certification-reiterate-campaign.xml (4.9, 4.10). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_REITERATE_TASK = new ArchetypeRef("Certification-campaign-reiterate task", "00000000-0000-0000-0000-000000000538", Lines.only("4.9", "4.10"));

    /** "Certification-campaign-start-related task", 536-archetype-task-certification-start-campaign.xml (4.9, 4.10). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_START_RELATED_TASK = new ArchetypeRef("Certification-campaign-start-related task", "00000000-0000-0000-0000-000000000536", Lines.only("4.9", "4.10"));

    /** "Certification-related task", 520-archetype-task-certification.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef CERTIFICATION_RELATED_TASK = new ArchetypeRef("Certification-related task", "00000000-0000-0000-0000-000000000520", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Certification-remediation-related task", 535-archetype-task-certification-remediation.xml (4.9, 4.10). */
    public static final ArchetypeRef CERTIFICATION_REMEDIATION_RELATED_TASK = new ArchetypeRef("Certification-remediation-related task", "00000000-0000-0000-0000-000000000535", Lines.only("4.9", "4.10"));

    /** "Classification", 062-archetype-classification.xml (4.9, 4.10). */
    public static final ArchetypeRef CLASSIFICATION = new ArchetypeRef("Classification", "00000000-0000-0000-0000-000000000330", Lines.only("4.9", "4.10"));

    /** "Cleanup task", 506-archetype-task-cleanup.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef CLEANUP_TASK = new ArchetypeRef("Cleanup task", "00000000-0000-0000-0000-000000000506", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Clearance", 063-archetype-clearance.xml (4.9, 4.10). */
    public static final ArchetypeRef CLEARANCE = new ArchetypeRef("Clearance", "00000000-0000-0000-0000-000000000331", Lines.only("4.9", "4.10"));

    /** "Collection report", 061-archetype-report-collection.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef COLLECTION_REPORT = new ArchetypeRef("Collection report", "00000000-0000-0000-0000-000000000171", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Correlation case", 027-archetype-correlation-case.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef CORRELATION_CASE = new ArchetypeRef("Correlation case", "00000000-0000-0000-0000-000000000345", Lines.only("4.8", "4.9", "4.10"));

    /** "Dashboard report", 060-archetype-report-dashboard.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef DASHBOARD_REPORT = new ArchetypeRef("Dashboard report", "00000000-0000-0000-0000-000000000170", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Delete long time not updated shadows task", 516-archetype-task-shadows-delete-long-time-not-updated.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef DELETE_LONG_TIME_NOT_UPDATED_SHADOWS_TASK = new ArchetypeRef("Delete long time not updated shadows task", "00000000-0000-0000-0000-000000000516", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Delete objects task", 515-archetype-task-objects-delete.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef DELETE_OBJECTS_TASK = new ArchetypeRef("Delete objects task", "00000000-0000-0000-0000-000000000515", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Distributed report export task", 512-archetype-task-report-export-distributed.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef DISTRIBUTED_REPORT_EXPORT_TASK = new ArchetypeRef("Distributed report export task", "00000000-0000-0000-0000-000000000512", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Event mark", 700-archetype-event-mark.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef EVENT_MARK = new ArchetypeRef("Event mark", "00000000-0000-0000-0000-000000000700", Lines.only("4.8", "4.9", "4.10"));

    /** "Execute change task (one delta applicable for many objects)", 517-archetype-task-execute-change.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef EXECUTE_CHANGE_TASK = new ArchetypeRef("Execute change task (one delta applicable for many objects)", "00000000-0000-0000-0000-000000000517", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Execute deltas task (background processing for change execution)", 518-archetype-task-execute-deltas.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef EXECUTE_DELTAS_TASK = new ArchetypeRef("Execute deltas task (background processing for change execution)", "00000000-0000-0000-0000-000000000518", Lines.only("4.8", "4.9", "4.10"));

    /** "Import task", 503-archetype-task-import.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef IMPORT_TASK = new ArchetypeRef("Import task", "00000000-0000-0000-0000-000000000503", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Iterative action task", 509-archetype-task-iterative-bulk-action.xml (4.9, 4.10). */
    public static final ArchetypeRef ITERATIVE_ACTION_TASK = new ArchetypeRef("Iterative action task", "00000000-0000-0000-0000-000000000509", Lines.only("4.9", "4.10"));

    /** "Iterative bulk action task", 509-archetype-task-iterative-bulk-action.xml (4.4, 4.8). */
    public static final ArchetypeRef ITERATIVE_BULK_ACTION_TASK = new ArchetypeRef("Iterative bulk action task", "00000000-0000-0000-0000-000000000509", Lines.only("4.4", "4.8"));

    /** "Live synchronization task", 504-archetype-task-live-sync.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef LIVE_SYNCHRONIZATION_TASK = new ArchetypeRef("Live synchronization task", "00000000-0000-0000-0000-000000000504", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Location", 150-archetype-location.xml (4.10). */
    public static final ArchetypeRef LOCATION = new ArchetypeRef("Location", "54b098b4-f6dc-44dd-8a05-ffd6c2f2c5b5", Lines.only("4.10"));

    /** "Manual provisioning case", 023-archetype-manual-provisioning-case.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef MANUAL_PROVISIONING_CASE = new ArchetypeRef("Manual provisioning case", "00000000-0000-0000-0000-000000000340", Lines.ALL);

    /** "Object integrity check task", 522-archetype-task-object-integrity-check.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef OBJECT_INTEGRITY_CHECK_TASK = new ArchetypeRef("Object integrity check task", "00000000-0000-0000-0000-000000000522", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Object mark", 701-archetype-object-mark.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef OBJECT_MARK = new ArchetypeRef("Object mark", "00000000-0000-0000-0000-000000000701", Lines.only("4.8", "4.9", "4.10"));

    /** "Operation request", 024-archetype-operation-request.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef OPERATION_REQUEST = new ArchetypeRef("Operation request", "00000000-0000-0000-0000-000000000341", Lines.ALL);

    /** "Organization", 150-archetype-organization.xml (4.10). */
    public static final ArchetypeRef ORGANIZATION = new ArchetypeRef("Organization", "886bfa6e-8702-11ef-9c40-6fce51f54b0d", Lines.only("4.10"));

    /** "Organizational unit", 150-archetype-organizational-unit.xml (4.10). */
    public static final ArchetypeRef ORGANIZATIONAL_UNIT = new ArchetypeRef("Organizational unit", "6a12b965-a1c2-47c9-87bd-19e84c71d814", Lines.only("4.10"));

    /** "Person", 702-archetype-person.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef PERSON = new ArchetypeRef("Person", "00000000-0000-0000-0000-000000000702", Lines.only("4.8", "4.9", "4.10"));

    /** "Project", 150-archetype-project.xml (4.10). */
    public static final ArchetypeRef PROJECT = new ArchetypeRef("Project", "89a34123-1db7-4fc1-a053-6c42b82ef2ab", Lines.only("4.10"));

    /** "Provisioning propagation task (many resources)", 533-archetype-task-multi-propagation.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef PROVISIONING_PROPAGATION_TASK_MANY_RESOURCES = new ArchetypeRef("Provisioning propagation task (many resources)", "00000000-0000-0000-0000-000000000533", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Provisioning propagation task (one resource)", 532-archetype-task-propagation.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef PROVISIONING_PROPAGATION_TASK_ONE_RESOURCE = new ArchetypeRef("Provisioning propagation task (one resource)", "00000000-0000-0000-0000-000000000532", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Recomputation task", 502-archetype-task-recomputation.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef RECOMPUTATION_TASK = new ArchetypeRef("Recomputation task", "00000000-0000-0000-0000-000000000502", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Reconciliation task", 501-archetype-task-reconciliation.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef RECONCILIATION_TASK = new ArchetypeRef("Reconciliation task", "00000000-0000-0000-0000-000000000501", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Refresh shadows task", 514-archetype-task-shadows-refresh.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef REFRESH_SHADOWS_TASK = new ArchetypeRef("Refresh shadows task", "00000000-0000-0000-0000-000000000514", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Reindex repository task", 519-archetype-task-reindex-repository.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef REINDEX_REPOSITORY_TASK = new ArchetypeRef("Reindex repository task", "00000000-0000-0000-0000-000000000519", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Report", 059-archetype-report.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef REPORT = new ArchetypeRef("Report", "00000000-0000-0000-0000-000000000172", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Report export task", 511-archetype-task-report-export-classic.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef REPORT_EXPORT_TASK = new ArchetypeRef("Report export task", "00000000-0000-0000-0000-000000000511", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Report import task", 510-archetype-task-report-import-classic.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef REPORT_IMPORT_TASK = new ArchetypeRef("Report import task", "00000000-0000-0000-0000-000000000510", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Report task", 507-archetype-task-report.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef REPORT_TASK = new ArchetypeRef("Report task", "00000000-0000-0000-0000-000000000507", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Resource template", 703-archetype-resource-template.xml (4.8, 4.9, 4.10). */
    public static final ArchetypeRef RESOURCE_TEMPLATE = new ArchetypeRef("Resource template", "00000000-0000-0000-0000-000000000703", Lines.only("4.8", "4.9", "4.10"));

    /** "Shadow integrity check task", 513-archetype-task-shadow-integrity-check.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef SHADOW_INTEGRITY_CHECK_TASK = new ArchetypeRef("Shadow integrity check task", "00000000-0000-0000-0000-000000000513", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Shadow policy mark", 705-archetype-shadow-policy-mark.xml (4.9, 4.10). */
    public static final ArchetypeRef SHADOW_POLICY_MARK = new ArchetypeRef("Shadow policy mark", "00000000-0000-0000-0000-000000000705", Lines.only("4.9", "4.10"));

    /** "Shadow reclassification task", 523-archetype-task-shadow-reclassification.xml (4.9, 4.10). */
    public static final ArchetypeRef SHADOW_RECLASSIFICATION_TASK = new ArchetypeRef("Shadow reclassification task", "00000000-0000-0000-0000-000000000523", Lines.only("4.9", "4.10"));

    /** "Single action task", 508-archetype-task-single-bulk-action.xml (4.9, 4.10). */
    public static final ArchetypeRef SINGLE_ACTION_TASK = new ArchetypeRef("Single action task", "00000000-0000-0000-0000-000000000508", Lines.only("4.9", "4.10"));

    /** "Single bulk action task", 508-archetype-task-single-bulk-action.xml (4.4, 4.8). */
    public static final ArchetypeRef SINGLE_BULK_ACTION_TASK = new ArchetypeRef("Single bulk action task", "00000000-0000-0000-0000-000000000508", Lines.only("4.4", "4.8"));

    /** "System role", 021-archetype-system-role.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef SYSTEM_ROLE = new ArchetypeRef("System role", "00000000-0000-0000-0000-000000000320", Lines.ALL);

    /** "System task", 529-archetype-task-system.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef SYSTEM_TASK = new ArchetypeRef("System task", "00000000-0000-0000-0000-000000000529", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "System user", 020-archetype-system-user.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef SYSTEM_USER = new ArchetypeRef("System user", "00000000-0000-0000-0000-000000000300", Lines.ALL);

    /** "Team", 150-archetype-team.xml (4.10). */
    public static final ArchetypeRef TEAM = new ArchetypeRef("Team", "3cc72762-030e-4161-bd9a-5fa9cb4fd935", Lines.only("4.10"));

    /** "Trace", 026-archetype-trace.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef TRACE = new ArchetypeRef("Trace", "00000000-0000-0000-0000-000000000343", Lines.ALL);

    /** "Trigger scanner task", 531-archetype-task-trigger.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef TRIGGER_SCANNER_TASK = new ArchetypeRef("Trigger scanner task", "00000000-0000-0000-0000-000000000531", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Utility task", 528-archetype-task-util.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef UTILITY_TASK = new ArchetypeRef("Utility task", "00000000-0000-0000-0000-000000000528", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Validity scanner task", 530-archetype-task-validity.xml (4.4, 4.8, 4.9, 4.10). */
    public static final ArchetypeRef VALIDITY_SCANNER_TASK = new ArchetypeRef("Validity scanner task", "00000000-0000-0000-0000-000000000530", Lines.only("4.4", "4.8", "4.9", "4.10"));

    private InitialArchetypes() {
    }
}
