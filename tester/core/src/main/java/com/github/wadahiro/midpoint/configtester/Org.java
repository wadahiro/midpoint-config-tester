package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.names.OrgRef;

/**
 * An org of the scenario by name ({@link MidPointConfigTester#org}), read anew by every call: its members, and its
 * place in the tree, the parents midPoint computed from its assignments ({@code parentOrgRef}).
 */
public final class Org extends AbstractRole<Org> {

    Org(MidPointConfigTester tester, String name) {
        super(tester, OrgType.class, "Org", name);
    }

    @Override
    Org self() {
        return this;
    }

    @Override
    public OrgType asObject() {
        return (OrgType) super.asObject();
    }

    /** The names of the orgs this one is in ({@code parentOrgRef} with the default relation). */
    public List<String> parents() {
        List<String> out = new ArrayList<>();
        for (ObjectReferenceType parent : existing().getParentOrgRef()) {
            if (parent.getRelation() == null || "default".equals(parent.getRelation().getLocalPart())) {
                out.add(tester.nameOf(parent));
            }
        }
        return out;
    }

    public Org hasParent(String org) {
        List<String> parents = parents();
        if (!parents.contains(org)) {
            throw new AssertionError("Org " + name + ": expected the parent " + org + ", parents: " + parents);
        }
        return this;
    }

    public Org hasParent(OrgRef org) {
        return hasParent(org.on(tester.line()));
    }

    public Org hasNoParent(String org) {
        List<String> parents = parents();
        if (parents.contains(org)) {
            throw new AssertionError("Org " + name + ": expected no parent " + org + ", parents: " + parents);
        }
        return this;
    }
}
