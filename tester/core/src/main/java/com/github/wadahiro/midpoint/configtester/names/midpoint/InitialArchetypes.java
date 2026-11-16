// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10, 4.11.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The archetypes midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialArchetypes {

    /** "Application", 029-archetype-application.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef APPLICATION = new ArchetypeRef("Application", "00000000-0000-0000-0000-000000000329", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Application role", 028-archetype-application-role.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef APPLICATION_ROLE = new ArchetypeRef("Application role", "00000000-0000-0000-0000-000000000328", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Approval case", 025-archetype-approval-case.xml (4.0, 4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef APPROVAL_CASE = new ArchetypeRef("Approval case", "00000000-0000-0000-0000-000000000342", Lines.ALL);

    /** "Approval-related task", 521-archetype-task-approval.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef APPROVAL_RELATED_TASK = new ArchetypeRef("Approval-related task", "00000000-0000-0000-0000-000000000521", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Asynchronous update task", 505-archetype-task-async-update.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef ASYNCHRONOUS_UPDATE_TASK = new ArchetypeRef("Asynchronous update task", "00000000-0000-0000-0000-000000000505", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Business role", 022-archetype-business-role.xml (4.0, 4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef BUSINESS_ROLE = new ArchetypeRef("Business role", "00000000-0000-0000-0000-000000000321", Lines.ALL);

    /** "Certification-campaign-close-current-stage task", 537-archetype-task-certification-close-current-stage.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_CLOSE_CURRENT_STAGE_TASK = new ArchetypeRef("Certification-campaign-close-current-stage task", "00000000-0000-0000-0000-000000000537", Lines.only("4.9", "4.10", "4.11"));

    /** "Certification-campaign-open-next-stage task", 534-archetype-task-certification-open-next-stage.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_OPEN_NEXT_STAGE_TASK = new ArchetypeRef("Certification-campaign-open-next-stage task", "00000000-0000-0000-0000-000000000534", Lines.only("4.9", "4.10", "4.11"));

    /** "Certification-campaign-reiterate task", 538-archetype-task-certification-reiterate-campaign.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_REITERATE_TASK = new ArchetypeRef("Certification-campaign-reiterate task", "00000000-0000-0000-0000-000000000538", Lines.only("4.9", "4.10", "4.11"));

    /** "Certification-campaign-start-related task", 536-archetype-task-certification-start-campaign.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef CERTIFICATION_CAMPAIGN_START_RELATED_TASK = new ArchetypeRef("Certification-campaign-start-related task", "00000000-0000-0000-0000-000000000536", Lines.only("4.9", "4.10", "4.11"));

    /** "Certification-related task", 520-archetype-task-certification.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef CERTIFICATION_RELATED_TASK = new ArchetypeRef("Certification-related task", "00000000-0000-0000-0000-000000000520", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Certification-remediation-related task", 535-archetype-task-certification-remediation.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef CERTIFICATION_REMEDIATION_RELATED_TASK = new ArchetypeRef("Certification-remediation-related task", "00000000-0000-0000-0000-000000000535", Lines.only("4.9", "4.10", "4.11"));

    /** "Classification", 018-archetype-classification.xml, 062-archetype-classification.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef CLASSIFICATION = new ArchetypeRef("Classification", "00000000-0000-0000-0000-000000000330", Lines.only("4.9", "4.10", "4.11"));

    /** "Cleanup task", 506-archetype-task-cleanup.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef CLEANUP_TASK = new ArchetypeRef("Cleanup task", "00000000-0000-0000-0000-000000000506", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Clearance", 063-archetype-clearance.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef CLEARANCE = new ArchetypeRef("Clearance", "00000000-0000-0000-0000-000000000331", Lines.only("4.9", "4.10", "4.11"));

    /** "Collection report", 061-archetype-report-collection.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef COLLECTION_REPORT = new ArchetypeRef("Collection report", "00000000-0000-0000-0000-000000000171", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Correlation case", 027-archetype-correlation-case.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef CORRELATION_CASE = new ArchetypeRef("Correlation case", "00000000-0000-0000-0000-000000000345", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Dashboard report", 060-archetype-report-dashboard.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef DASHBOARD_REPORT = new ArchetypeRef("Dashboard report", "00000000-0000-0000-0000-000000000170", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Delete long time not updated shadows task", 516-archetype-task-shadows-delete-long-time-not-updated.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef DELETE_LONG_TIME_NOT_UPDATED_SHADOWS_TASK = new ArchetypeRef("Delete long time not updated shadows task", "00000000-0000-0000-0000-000000000516", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Delete objects task", 515-archetype-task-objects-delete.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef DELETE_OBJECTS_TASK = new ArchetypeRef("Delete objects task", "00000000-0000-0000-0000-000000000515", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Distributed report export task", 512-archetype-task-report-export-distributed.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef DISTRIBUTED_REPORT_EXPORT_TASK = new ArchetypeRef("Distributed report export task", "00000000-0000-0000-0000-000000000512", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Event mark", 700-archetype-event-mark.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef EVENT_MARK = new ArchetypeRef("Event mark", "00000000-0000-0000-0000-000000000700", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Execute change task (one delta applicable for many objects)", 517-archetype-task-execute-change.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef EXECUTE_CHANGE_TASK = new ArchetypeRef("Execute change task (one delta applicable for many objects)", "00000000-0000-0000-0000-000000000517", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Execute deltas task (background processing for change execution)", 518-archetype-task-execute-deltas.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef EXECUTE_DELTAS_TASK = new ArchetypeRef("Execute deltas task (background processing for change execution)", "00000000-0000-0000-0000-000000000518", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Governance policy", 064-archetype-governance-policy.xml (4.11). */
    public static final ArchetypeRef GOVERNANCE_POLICY = new ArchetypeRef("Governance policy", "4f3084ae-4308-11f1-a19e-a357d787ee05", Lines.only("4.11"));

    /** "Import task", 503-archetype-task-import.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef IMPORT_TASK = new ArchetypeRef("Import task", "00000000-0000-0000-0000-000000000503", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Iterative action task", 509-archetype-task-iterative-bulk-action.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef ITERATIVE_ACTION_TASK = new ArchetypeRef("Iterative action task", "00000000-0000-0000-0000-000000000509", Lines.only("4.9", "4.10", "4.11"));

    /** "Iterative bulk action task", 509-archetype-task-iterative-bulk-action.xml (4.4, 4.8). */
    public static final ArchetypeRef ITERATIVE_BULK_ACTION_TASK = new ArchetypeRef("Iterative bulk action task", "00000000-0000-0000-0000-000000000509", Lines.only("4.4", "4.8"));

    /** "Live synchronization task", 504-archetype-task-live-sync.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef LIVE_SYNCHRONIZATION_TASK = new ArchetypeRef("Live synchronization task", "00000000-0000-0000-0000-000000000504", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Location", 150-archetype-location.xml (4.10, 4.11). */
    public static final ArchetypeRef LOCATION = new ArchetypeRef("Location", "54b098b4-f6dc-44dd-8a05-ffd6c2f2c5b5", Lines.only("4.10", "4.11"));

    /** "Manual provisioning case", 023-archetype-manual-provisioning-case.xml (4.0, 4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef MANUAL_PROVISIONING_CASE = new ArchetypeRef("Manual provisioning case", "00000000-0000-0000-0000-000000000340", Lines.ALL);

    /** "Object integrity check task", 522-archetype-task-object-integrity-check.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef OBJECT_INTEGRITY_CHECK_TASK = new ArchetypeRef("Object integrity check task", "00000000-0000-0000-0000-000000000522", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Object mark", 016-archetype-object-mark.xml, 701-archetype-object-mark.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef OBJECT_MARK = new ArchetypeRef("Object mark", "00000000-0000-0000-0000-000000000701", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Operation request", 024-archetype-operation-request.xml (4.0, 4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef OPERATION_REQUEST = new ArchetypeRef("Operation request", "00000000-0000-0000-0000-000000000341", Lines.ALL);

    /** "Organization", 150-archetype-organization.xml (4.10, 4.11). */
    public static final ArchetypeRef ORGANIZATION = new ArchetypeRef("Organization", "886bfa6e-8702-11ef-9c40-6fce51f54b0d", Lines.only("4.10", "4.11"));

    /** "Organizational unit", 150-archetype-organizational-unit.xml (4.10, 4.11). */
    public static final ArchetypeRef ORGANIZATIONAL_UNIT = new ArchetypeRef("Organizational unit", "6a12b965-a1c2-47c9-87bd-19e84c71d814", Lines.only("4.10", "4.11"));

    /** "Person", 702-archetype-person.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef PERSON = new ArchetypeRef("Person", "00000000-0000-0000-0000-000000000702", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Project", 150-archetype-project.xml (4.10, 4.11). */
    public static final ArchetypeRef PROJECT = new ArchetypeRef("Project", "89a34123-1db7-4fc1-a053-6c42b82ef2ab", Lines.only("4.10", "4.11"));

    /** "Provisioning propagation task (many resources)", 533-archetype-task-multi-propagation.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef PROVISIONING_PROPAGATION_TASK_MANY_RESOURCES = new ArchetypeRef("Provisioning propagation task (many resources)", "00000000-0000-0000-0000-000000000533", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Provisioning propagation task (one resource)", 532-archetype-task-propagation.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef PROVISIONING_PROPAGATION_TASK_ONE_RESOURCE = new ArchetypeRef("Provisioning propagation task (one resource)", "00000000-0000-0000-0000-000000000532", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Recomputation task", 502-archetype-task-recomputation.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef RECOMPUTATION_TASK = new ArchetypeRef("Recomputation task", "00000000-0000-0000-0000-000000000502", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Reconciliation task", 501-archetype-task-reconciliation.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef RECONCILIATION_TASK = new ArchetypeRef("Reconciliation task", "00000000-0000-0000-0000-000000000501", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Refresh shadows task", 514-archetype-task-shadows-refresh.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef REFRESH_SHADOWS_TASK = new ArchetypeRef("Refresh shadows task", "00000000-0000-0000-0000-000000000514", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Reindex repository task", 519-archetype-task-reindex-repository.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef REINDEX_REPOSITORY_TASK = new ArchetypeRef("Reindex repository task", "00000000-0000-0000-0000-000000000519", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Report", 059-archetype-report.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef REPORT = new ArchetypeRef("Report", "00000000-0000-0000-0000-000000000172", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Report export task", 511-archetype-task-report-export-classic.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef REPORT_EXPORT_TASK = new ArchetypeRef("Report export task", "00000000-0000-0000-0000-000000000511", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Report import task", 510-archetype-task-report-import-classic.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef REPORT_IMPORT_TASK = new ArchetypeRef("Report import task", "00000000-0000-0000-0000-000000000510", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Report task", 507-archetype-task-report.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef REPORT_TASK = new ArchetypeRef("Report task", "00000000-0000-0000-0000-000000000507", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Resource template", 703-archetype-resource-template.xml (4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef RESOURCE_TEMPLATE = new ArchetypeRef("Resource template", "00000000-0000-0000-0000-000000000703", Lines.only("4.8", "4.9", "4.10", "4.11"));

    /** "Shadow integrity check task", 513-archetype-task-shadow-integrity-check.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef SHADOW_INTEGRITY_CHECK_TASK = new ArchetypeRef("Shadow integrity check task", "00000000-0000-0000-0000-000000000513", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Shadow policy mark", 705-archetype-shadow-policy-mark.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef SHADOW_POLICY_MARK = new ArchetypeRef("Shadow policy mark", "00000000-0000-0000-0000-000000000705", Lines.only("4.9", "4.10", "4.11"));

    /** "Shadow reclassification task", 523-archetype-task-shadow-reclassification.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef SHADOW_RECLASSIFICATION_TASK = new ArchetypeRef("Shadow reclassification task", "00000000-0000-0000-0000-000000000523", Lines.only("4.9", "4.10", "4.11"));

    /** "Single action task", 508-archetype-task-single-bulk-action.xml (4.9, 4.10, 4.11). */
    public static final ArchetypeRef SINGLE_ACTION_TASK = new ArchetypeRef("Single action task", "00000000-0000-0000-0000-000000000508", Lines.only("4.9", "4.10", "4.11"));

    /** "Single bulk action task", 508-archetype-task-single-bulk-action.xml (4.4, 4.8). */
    public static final ArchetypeRef SINGLE_BULK_ACTION_TASK = new ArchetypeRef("Single bulk action task", "00000000-0000-0000-0000-000000000508", Lines.only("4.4", "4.8"));

    /** "Smart integration focus object type statistics", 100-archetype-smart-integration-focus-object-type-statistics.xml (4.11). */
    public static final ArchetypeRef SMART_INTEGRATION_FOCUS_OBJECT_TYPE_STATISTICS = new ArchetypeRef("Smart integration focus object type statistics", "504e000b-2432-4fcb-b25b-caece57e502a", Lines.only("4.11"));

    /** "Smart integration resource object class statistics", 100-archetype-smart-integration-resource-object-class-statistics.xml (4.11). */
    public static final ArchetypeRef SMART_INTEGRATION_RESOURCE_OBJECT_CLASS_STATISTICS = new ArchetypeRef("Smart integration resource object class statistics", "ce989425-5dbf-444e-812f-5077620727d5", Lines.only("4.11"));

    /** "Smart integration resource object type statistics", 100-archetype-smart-integration-resource-object-type-statistics.xml (4.11). */
    public static final ArchetypeRef SMART_INTEGRATION_RESOURCE_OBJECT_TYPE_STATISTICS = new ArchetypeRef("Smart integration resource object type statistics", "0e4b0dc2-b0de-4aeb-a30f-d43ab069b5d5", Lines.only("4.11"));

    /** "Smart integration schema match", 100-archetype-smart-integration-schema-match.xml (4.11). */
    public static final ArchetypeRef SMART_INTEGRATION_SCHEMA_MATCH = new ArchetypeRef("Smart integration schema match", "d74efe5c-8aa0-4b2c-bca4-697512e63eb4", Lines.only("4.11"));

    /** "System role", 021-archetype-system-role.xml (4.0, 4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef SYSTEM_ROLE = new ArchetypeRef("System role", "00000000-0000-0000-0000-000000000320", Lines.ALL);

    /** "System-submitted task", 540-archetype-task-system-submitted.xml (4.11). */
    public static final ArchetypeRef SYSTEM_SUBMITTED_TASK = new ArchetypeRef("System-submitted task", "83225dc6-bf8a-4d37-97e5-c94a3f77da54", Lines.only("4.11"));

    /** "System task", 529-archetype-task-system.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef SYSTEM_TASK = new ArchetypeRef("System task", "00000000-0000-0000-0000-000000000529", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "System user", 020-archetype-system-user.xml (4.0, 4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef SYSTEM_USER = new ArchetypeRef("System user", "00000000-0000-0000-0000-000000000300", Lines.ALL);

    /** "Team", 150-archetype-team.xml (4.10, 4.11). */
    public static final ArchetypeRef TEAM = new ArchetypeRef("Team", "3cc72762-030e-4161-bd9a-5fa9cb4fd935", Lines.only("4.10", "4.11"));

    /** "Trace", 026-archetype-trace.xml (4.0, 4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef TRACE = new ArchetypeRef("Trace", "00000000-0000-0000-0000-000000000343", Lines.ALL);

    /** "Trigger scanner task", 531-archetype-task-trigger.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef TRIGGER_SCANNER_TASK = new ArchetypeRef("Trigger scanner task", "00000000-0000-0000-0000-000000000531", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "User-submitted task", 539-archetype-task-user-submitted.xml (4.11). */
    public static final ArchetypeRef USER_SUBMITTED_TASK = new ArchetypeRef("User-submitted task", "d515f699-b7dd-4649-9703-a7c02afa1537", Lines.only("4.11"));

    /** "Utility task", 528-archetype-task-util.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef UTILITY_TASK = new ArchetypeRef("Utility task", "00000000-0000-0000-0000-000000000528", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    /** "Validity scanner task", 530-archetype-task-validity.xml (4.4, 4.8, 4.9, 4.10, 4.11). */
    public static final ArchetypeRef VALIDITY_SCANNER_TASK = new ArchetypeRef("Validity scanner task", "00000000-0000-0000-0000-000000000530", Lines.only("4.4", "4.8", "4.9", "4.10", "4.11"));

    private InitialArchetypes() {
    }
}
