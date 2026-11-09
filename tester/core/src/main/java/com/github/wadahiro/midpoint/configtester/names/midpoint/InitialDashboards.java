// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The dashboards midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialDashboards {

    /** "admin-dashboard", 310-dashboard-admin.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef ADMIN_DASHBOARD = new MidPointObjectRef("DashboardType", "dashboard", "admin-dashboard", "00000000-0000-0000-0001-000000000001", Lines.ALL);

    /** "compliance-dashboard", 899-dashboard-compliance.xml (4.10). */
    public static final MidPointObjectRef COMPLIANCE_DASHBOARD = new MidPointObjectRef("DashboardType", "dashboard", "compliance-dashboard", "f941f3fc-dcef-4415-9e79-ae56b185a501", Lines.only("4.10"));

    private InitialDashboards() {
    }
}
