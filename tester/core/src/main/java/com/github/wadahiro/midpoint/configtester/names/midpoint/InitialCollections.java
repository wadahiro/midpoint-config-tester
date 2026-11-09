// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The object collections midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialCollections {

    /** "Active applications", 253-object-collection-application-active.xml (4.10). */
    public static final MidPointObjectRef ACTIVE_APPLICATIONS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Active applications", "c4a07805-67c5-4b25-961e-e47b75a0b5a5", Lines.only("4.10"));

    /** "Active roles", 350-object-collection-role-active.xml (4.10). */
    public static final MidPointObjectRef ACTIVE_ROLES = new MidPointObjectRef("ObjectCollectionType", "object collection", "Active roles", "8494d5a9-5ce9-4795-813b-0e424b9e4d42", Lines.only("4.10"));

    /** "Active tasks", 261-object-collection-task-active.xml, 270-object-collection-task-active.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ACTIVE_TASKS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Active tasks", "00000000-0000-0000-0001-000000000005", Lines.ALL);

    /** "Active users", 350-object-collection-user-active.xml (4.10). */
    public static final MidPointObjectRef ACTIVE_USERS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Active users", "0de46df0-f2d9-437b-84ba-8e8d291ef0e1", Lines.only("4.10"));

    /** "All audit records", 270-object-collection-audit.xml (4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ALL_AUDIT_RECORDS = new MidPointObjectRef("ObjectCollectionType", "object collection", "All audit records", "00000000-0000-0000-0001-000000000284", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "All certification campaigns", 280-object-collection-certification-campaign-all.xml (4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ALL_CERTIFICATION_CAMPAIGNS = new MidPointObjectRef("ObjectCollectionType", "object collection", "All certification campaigns", "00000000-0000-0000-0001-000000000280", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "All resources", 250-object-collection-resource-all.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ALL_RESOURCES = new MidPointObjectRef("ObjectCollectionType", "object collection", "All resources", "00000000-0000-0000-0001-000000000006", Lines.ALL);

    /** "All resource templates", 340-object-collection-resource-templates.xml (4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ALL_RESOURCE_TEMPLATES = new MidPointObjectRef("ObjectCollectionType", "object collection", "All resource templates", "00000000-0000-0000-0001-000000000340", Lines.only("4.8", "4.9", "4.10"));

    /** "All roles", 350-object-collection-role-all.xml (4.10). */
    public static final MidPointObjectRef ALL_ROLES = new MidPointObjectRef("ObjectCollectionType", "object collection", "All roles", "02c103d7-e178-4d34-b494-e367164f0fa7", Lines.only("4.10"));

    /** "All shadows", 290-object-collection-shadow-all.xml (4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ALL_SHADOWS = new MidPointObjectRef("ObjectCollectionType", "object collection", "All shadows", "00000000-0000-0000-0001-000000000008", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "All tasks", 260-object-collection-task-all.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ALL_TASKS = new MidPointObjectRef("ObjectCollectionType", "object collection", "All tasks", "00000000-0000-0000-0001-000000000007", Lines.ALL);

    /** "Applications", 253-object-collection-application.xml (4.8, 4.9, 4.10). */
    public static final MidPointObjectRef APPLICATIONS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Applications", "00000000-0000-0000-0001-000000000017", Lines.only("4.8", "4.9", "4.10"));

    /** "Applications without classification", 350-object-collection-application-without-classification.xml (4.10). */
    public static final MidPointObjectRef APPLICATIONS_WITHOUT_CLASSIFICATION = new MidPointObjectRef("ObjectCollectionType", "object collection", "Applications without classification", "d7887599-3f3a-4d51-80ae-ee8567cd51aa", Lines.only("4.10"));

    /** "Application roles", 252-object-collection-application-role.xml (4.8, 4.9, 4.10). */
    public static final MidPointObjectRef APPLICATION_ROLES = new MidPointObjectRef("ObjectCollectionType", "object collection", "Application roles", "00000000-0000-0000-0001-000000000016", Lines.only("4.8", "4.9", "4.10"));

    /** "Audit records in 24h", 271-object-collection-audit-24h.xml (4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef AUDIT_RECORDS_IN_24H = new MidPointObjectRef("ObjectCollectionType", "object collection", "Audit records in 24h", "00000000-0000-0000-0001-000000000285", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Certification tasks", 263-object-collection-task-certification.xml (4.9, 4.10). */
    public static final MidPointObjectRef CERTIFICATION_TASKS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Certification tasks", "00000000-0000-0000-0002-000000000008", Lines.only("4.9", "4.10"));

    /** "Error audit records", 290-object-collection-audit-errors.xml (4.0). */
    public static final MidPointObjectRef ERROR_AUDIT_RECORDS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Error audit records", "00000000-0000-0000-0001-000000000004", Lines.only("4.0"));

    /** "Error audit records in 24h", 272-object-collection-audit-errors-24h.xml (4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ERROR_AUDIT_RECORDS_IN_24H = new MidPointObjectRef("ObjectCollectionType", "object collection", "Error audit records in 24h", "00000000-0000-0000-0001-000000000004", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Modification audit records", 300-object-collection-audit-modifications.xml (4.0). */
    public static final MidPointObjectRef MODIFICATION_AUDIT_RECORDS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Modification audit records", "00000000-0000-0000-0001-000000000002", Lines.only("4.0"));

    /** "Modification audit records in 24h", 273-object-collection-audit-modifications-24h.xml (4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef MODIFICATION_AUDIT_RECORDS_IN_24H = new MidPointObjectRef("ObjectCollectionType", "object collection", "Modification audit records in 24h", "00000000-0000-0000-0001-000000000002", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "My cases", 330-object-collection-my-cases.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef MY_CASES = new MidPointObjectRef("ObjectCollectionType", "object collection", "My cases", "00000000-0000-0000-0000-000000000344", Lines.ALL);

    /** "Privileged roles", 354-roles-privileged.xml (4.9, 4.10). */
    public static final MidPointObjectRef PRIVILEGED_ROLES = new MidPointObjectRef("ObjectCollectionType", "object collection", "Privileged roles", "00000000-0000-0000-0001-000000000354", Lines.only("4.9", "4.10"));

    /** "Privileged users", 350-object-collection-privileged-users.xml (4.10). */
    public static final MidPointObjectRef PRIVILEGED_USERS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Privileged users", "f968f2ca-1c61-421c-b3d9-6925a8f31983", Lines.only("4.10"));

    /** "Report tasks", 262-object-collection-task-report.xml (4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef REPORT_TASKS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Report tasks", "00000000-0000-0000-0002-000000000007", Lines.only("4.4", "4.8", "4.9", "4.10"));

    /** "Resources up", 251-object-collection-resource-up.xml, 280-object-collection-resource-up.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef RESOURCES_UP = new MidPointObjectRef("ObjectCollectionType", "object collection", "Resources up", "00000000-0000-0000-0001-000000000003", Lines.ALL);

    /** "Suspicious applications", 350-object-collection-application-suspicious.xml (4.10). */
    public static final MidPointObjectRef SUSPICIOUS_APPLICATIONS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Suspicious applications", "89c49f7f-2b3e-4319-8b19-a8ed651394f4", Lines.only("4.10"));

    /** "Suspicious objects", 352-suspicious-objects.xml (4.9, 4.10). */
    public static final MidPointObjectRef SUSPICIOUS_OBJECTS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Suspicious objects", "00000000-0000-0000-0001-000000000352", Lines.only("4.9", "4.10"));

    /** "Suspicious roles", 357-suspicious-roles.xml (4.9, 4.10). */
    public static final MidPointObjectRef SUSPICIOUS_ROLES = new MidPointObjectRef("ObjectCollectionType", "object collection", "Suspicious roles", "00000000-0000-0000-0001-000000000357", Lines.only("4.9", "4.10"));

    /** "Suspicious users", 358-suspicious-users.xml (4.9, 4.10). */
    public static final MidPointObjectRef SUSPICIOUS_USERS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Suspicious users", "00000000-0000-0000-0001-000000000358", Lines.only("4.9", "4.10"));

    /** "Unowned applications", 350-object-collection-application-unowned.xml (4.10). */
    public static final MidPointObjectRef UNOWNED_APPLICATIONS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Unowned applications", "cc8c1397-e5c4-456c-bd98-f07b3dca97ec", Lines.only("4.10"));

    /** "Unowned roles", 350-object-collection-role-unowned.xml (4.10). */
    public static final MidPointObjectRef UNOWNED_ROLES = new MidPointObjectRef("ObjectCollectionType", "object collection", "Unowned roles", "6d9d98f5-9182-4d2d-89e8-32ab23704199", Lines.only("4.10"));

    /** "Users with SoD violation", 353-users-with-sod-violations.xml (4.10). */
    public static final MidPointObjectRef USERS_WITH_SO_D_VIOLATION = new MidPointObjectRef("ObjectCollectionType", "object collection", "Users with SoD violation", "00000000-0000-0000-0001-000000000353", Lines.only("4.10"));

    /** "Users with SoD violations", 353-users-with-sod-violations.xml (4.9). */
    public static final MidPointObjectRef USERS_WITH_SO_D_VIOLATIONS = new MidPointObjectRef("ObjectCollectionType", "object collection", "Users with SoD violations", "00000000-0000-0000-0001-000000000353", Lines.only("4.9"));

    private InitialCollections() {
    }
}
