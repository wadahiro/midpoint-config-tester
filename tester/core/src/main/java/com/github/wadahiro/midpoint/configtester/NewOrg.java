package com.github.wadahiro.midpoint.configtester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.names.OrgRef;

/** An org to add through the model ({@link Org#add(java.util.function.Consumer)}). */
public final class NewOrg extends NewFocus<NewOrg, OrgType> {

    NewOrg(MidPointConfigTester tester, String name) {
        super(tester, OrgType.class, name);
    }

    @Override
    NewOrg self() {
        return this;
    }

    /** The parent org: an assignment of it (midPoint computes the {@code parentOrgRef}, {@link Org#parents()}). */
    public NewOrg parent(String org) {
        return assign(org);
    }

    public NewOrg parent(OrgRef org) {
        return assign(org);
    }
}
