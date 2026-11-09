package com.github.wadahiro.midpoint.tester.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Single-run resource tasks as the GUI creates them from a resource ("Reconcile", "Import", "Live sync"): an activity
 * on 4.4+, a task handler with the resource as its object and kind/intent in the extension on 4.0. One OID per
 * operation, resource, kind and intent, so a later call overwrites (and re-runs) the task of an earlier one; a live
 * synchronization task is kept instead and run again, since it holds the token.
 */
final class ResourceTasks {

    static final String RECONCILE = "reconcile";
    static final String IMPORT = "import";
    static final String LIVE_SYNC = "live sync";

    private ResourceTasks() {
    }

    static String oid(String operation, String resourceOid, String kind, String intent) {
        return UUID.nameUUIDFromBytes(("tester " + operation + " " + resourceOid + " " + kind + " " + intent)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String xml(String operation, String resourceOid, String resourceName, String kind, String intent) {
        String oid = oid(operation, resourceOid, kind, intent);
        String name = escape(operation + " " + resourceName + " (tester)");
        String c = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
        if ("4.0".equals(LineSupport.LINE)) {
            String handler = "http://midpoint.evolveum.com/xml/ns/public/model/synchronization/task/"
                    + (RECONCILE.equals(operation) ? "reconciliation" : LIVE_SYNC.equals(operation) ? "live-sync"
                            : "import") + "/handler-3";
            return "<task oid='" + oid + "' xmlns='" + c + "'"
                    + " xmlns:mext='http://midpoint.evolveum.com/xml/ns/public/model/extension-3'>"
                    + "<name>" + name + "</name>"
                    + "<extension><mext:kind>" + kind + "</mext:kind><mext:intent>" + escape(intent)
                    + "</mext:intent></extension>"
                    + "<ownerRef oid='00000000-0000-0000-0000-000000000002' type='UserType'/>"
                    + "<executionStatus>runnable</executionStatus>"
                    + "<handlerUri>" + handler + "</handlerUri>"
                    + "<objectRef oid='" + resourceOid + "' type='ResourceType'/>"
                    + "<recurrence>single</recurrence><binding>loose</binding>"
                    + "</task>";
        }
        String work = RECONCILE.equals(operation) ? "reconciliation"
                : LIVE_SYNC.equals(operation) ? "liveSynchronization" : "import";
        return "<task oid='" + oid + "' xmlns='" + c + "'>"
                + "<name>" + name + "</name>"
                + "<ownerRef oid='00000000-0000-0000-0000-000000000002' type='UserType'/>"
                + "<executionState>runnable</executionState>"
                + "<activity><work><" + work + "><resourceObjects>"
                + "<resourceRef oid='" + resourceOid + "' type='ResourceType'/>"
                + "<kind>" + kind + "</kind><intent>" + escape(intent) + "</intent>"
                + "</resourceObjects></" + work + "></work></activity>"
                + "</task>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
