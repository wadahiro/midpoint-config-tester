package com.github.wadahiro.midpoint.configtester;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A page of the side menu by a name the same on every line ({@link SideMenu#hasItem(MenuPage)}): the lines mount
 * some pages at different URLs and label them differently ("Request a role" at {@code /self/assignmentShoppingCart}
 * on 4.0 and 4.4, "Request access" at {@code /self/requestAccess} on 4.8 and later). Each name lists the URLs the lines
 * use; an item matches when its page is mounted at one of them.
 */
public enum MenuPage {

    /**
     * The self-service dashboard ("Home"), {@code /self} and {@code /self/dashboard}: {@code #selfAll} or
     * {@code #selfDashboard}.
     */
    SELF_HOME("/self", "/self/dashboard"),
    /**
     * The user's own profile, {@code /self/profile/user} (4.4's new design: {@code /self/profile/userNew}; 4.0:
     * {@code /self/profile}): {@code #selfAll} or {@code #selfProfile}.
     */
    SELF_PROFILE("/self/profile/user", "/self/profile/userNew", "/self/profile"),
    /** "Credentials", {@code /self/credentials}: {@code #selfCredentials}. */
    SELF_CREDENTIALS("/self/credentials"),
    /**
     * The request-access page: "Request access" at {@code /self/requestAccess} (4.8+), "Request a role" at
     * {@code /self/assignmentShoppingCart} (4.0, 4.4); {@code #selfAll} or {@code #selfRequestAssignments}.
     */
    REQUEST_ACCESS("/self/requestAccess", "/self/assignmentShoppingCart"),
    /** "Consents", {@code /self/consents}: an experimental page (4.4+), not in 4.0's menu. */
    SELF_CONSENTS("/self/consents"),
    /** The administration's info dashboard, {@code /admin/dashboard/info}: {@code #home} or {@code #dashboard}. */
    DASHBOARD("/admin/dashboard/info"),
    /** "All users", {@code /admin/users}: {@code #usersAll} or {@code #users}. */
    USERS("/admin/users"),
    /** "Organization tree", {@code /admin/org/tree}: {@code #orgAll} or {@code #orgTree}. */
    ORG_TREE("/admin/org/tree"),
    /** "All roles", {@code /admin/roles}: {@code #rolesAll} or {@code #roles}. */
    ROLES("/admin/roles"),
    /** "All services", {@code /admin/services}: {@code #servicesAll} or {@code #services}. */
    SERVICES("/admin/services"),
    /** "All resources", {@code /admin/resources}: {@code #resourcesAll} or {@code #resources}. */
    RESOURCES("/admin/resources"),
    /** "My work items", {@code /admin/myWorkItems}. */
    MY_WORK_ITEMS("/admin/myWorkItems"),
    /** "All work items", {@code /admin/allWorkItems}. */
    ALL_WORK_ITEMS("/admin/allWorkItems"),
    /** Certification "Campaigns", {@code /admin/certification/campaigns}. */
    CERTIFICATION_CAMPAIGNS("/admin/certification/campaigns"),
    /** Certification "Definitions", {@code /admin/certification/definitions}. */
    CERTIFICATION_DEFINITIONS("/admin/certification/definitions"),
    /** "All tasks", {@code /admin/tasks}: {@code #tasksAll} or {@code #tasks}. */
    TASKS("/admin/tasks"),
    /** "All reports", {@code /admin/reports}: {@code #reportsAll} or {@code #reports}. */
    REPORTS("/admin/reports"),
    /** "Audit log viewer", {@code /admin/auditLogViewer} (4.0, 4.4: under Reports): {@code #auditLogViewer}. */
    AUDIT_LOG("/admin/auditLogViewer"),
    /** "Repository objects", {@code /admin/config/debugs}. */
    REPOSITORY_OBJECTS("/admin/config/debugs"),
    /** "Import object", {@code /admin/config/import}. */
    IMPORT_OBJECT("/admin/config/import"),
    /** "System" (the system configuration), {@code /admin/config/system}. */
    SYSTEM_CONFIGURATION("/admin/config/system"),
    /** "About", {@code /admin/config/about}. */
    ABOUT("/admin/config/about");

    private final List<String> urls;

    MenuPage(String... urls) {
        this.urls = Collections.unmodifiableList(Arrays.asList(urls));
    }

    /** The URLs the lines mount the page at (after the GUI's context), the newest line's first. */
    public List<String> urls() {
        return urls;
    }

    /** Whether the item's page is mounted at one of the page's URLs. */
    boolean matchesItem(SideMenu.Item item) {
        for (String url : item.urls()) {
            if (urls.contains(url)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return name() + " (" + String.join(" | ", urls) + ")";
    }
}
