package com.github.wadahiro.midpoint.configtester.sample.stale;

import java.util.List;

import com.github.wadahiro.midpoint.configtester.names.DeploymentName;
import com.github.wadahiro.midpoint.configtester.names.Lines;
import com.github.wadahiro.midpoint.configtester.names.RoleRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;

/** As generated from an older configuration of {@code scenarios/access} (DeploymentLoadTest). */
public final class Names {

    private Names() {
    }

    public static List<DeploymentName> all() {
        return List.of(
                new RoleRef("Auditor", "30000000-0000-0000-0000-00000000c002", Lines.ALL),
                // renamed since
                new RoleRef("Internal Auditor", null, Lines.ALL),
                // another OID now
                new UserRef("jack", "30000000-0000-0000-0000-00000000e999", Lines.ALL),
                // on a line whose overlay the test does not run: not checked
                new RoleRef("Ghost", null, Lines.only("3.9")));
    }
}
