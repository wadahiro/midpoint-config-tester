// Written by .claude/skills/add-midpoint-line/scripts/initial-names.py from the initial objects of midPoint 4.0, 4.4, 4.8, 4.9, 4.10.
// Do not edit: run the script again (InitialNamesTest checks it on every line).
package com.github.wadahiro.midpoint.configtester.names.midpoint;

import com.github.wadahiro.midpoint.configtester.names.*;

/**
 * The security, value and (4.9+) policy objects midPoint imports on its first start (its initial objects), on the lines that ship them
 * under this name. Like the generated names of a deployment, usable in every verb that takes a name.
 */
public final class InitialPolicies {

    /** "Default Password Policy", 010-value-policy.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef DEFAULT_PASSWORD_POLICY = new MidPointObjectRef("ValuePolicyType", "value policy", "Default Password Policy", "00000000-0000-0000-0000-000000000003", Lines.ALL);

    /** "Default Security Policy", 015-security-policy.xml (4.0, 4.4, 4.8, 4.9, 4.10). */
    public static final MidPointObjectRef DEFAULT_SECURITY_POLICY = new MidPointObjectRef("SecurityPolicyType", "security policy", "Default Security Policy", "00000000-0000-0000-0000-000000000120", Lines.ALL);

    /** "Privileged access", 300-classification-privileged-access.xml (4.9, 4.10). */
    public static final MidPointObjectRef PRIVILEGED_ACCESS = new MidPointObjectRef("PolicyType", "policy", "Privileged access", "00000000-0000-0000-0000-000000000332", Lines.only("4.9", "4.10"));

    /** "Require classification", 300-policy-require-classification.xml (4.10). */
    public static final MidPointObjectRef REQUIRE_CLASSIFICATION = new MidPointObjectRef("PolicyType", "policy", "Require classification", "4758b0a3-4b26-47fb-b69e-d54703c86ee2", Lines.only("4.10"));

    /** "Require owner", 300-policy-require-owner.xml (4.10). */
    public static final MidPointObjectRef REQUIRE_OWNER = new MidPointObjectRef("PolicyType", "policy", "Require owner", "6451bca8-4035-4fb3-8ab1-5de14da59e18", Lines.only("4.10"));

    private InitialPolicies() {
    }
}
