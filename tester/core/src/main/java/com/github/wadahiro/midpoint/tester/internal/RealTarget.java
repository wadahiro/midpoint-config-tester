package com.github.wadahiro.midpoint.tester.internal;

import java.util.List;

import com.github.wadahiro.midpoint.tester.mirror.MirrorStore;

/**
 * A target on its real connector (CSV, LDAP) whose additional connectors stand in as mirrors
 * ({@link MirrorTargetImpl#scriptsOnly}): the provisioning scripts run there, and fail there when a test says so.
 */
interface RealTarget {

    /** The mirrors of the resource's additional connectors. */
    void scriptMirrors(List<String> instanceIds);

    static void scriptsFail(String resourceOid, List<String> scriptMirrors, boolean fail) {
        if (scriptMirrors.isEmpty()) {
            throw new UnsupportedOperationException("Resource " + resourceOid + " is on its real connector and has no"
                    + " additional connector: its scripts, if any, run on the real connector");
        }
        scriptMirrors.forEach(id -> MirrorStore.get(id).scriptsFail(fail));
    }
}
