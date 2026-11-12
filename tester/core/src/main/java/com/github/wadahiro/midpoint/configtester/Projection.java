package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;

/**
 * An account a focus links on a resource ({@link User#account}), in its two places: its shadow in the repository
 * ({@link #shadow()}) and the object on the target system ({@link #onTarget()}). Both are read as they are, without
 * midPoint's provisioning (which would act on what it finds), so that a precondition may check a state midPoint would
 * repair:
 *
 * <pre>{@code
 * t.user("jdoe").account("Directory").shadow().hasTag("legacy");
 * t.user("jdoe").account("Directory").onTarget().exists().hasAttribute("mail", "jdoe@example.com");
 * }</pre>
 */
public final class Projection {

    private final MidPointConfigTester tester;
    private final String owner;
    private final String resource;
    private final String name;

    Projection(MidPointConfigTester tester, String owner, String resource, String name) {
        this.tester = tester;
        this.owner = owner;
        this.resource = resource;
        this.name = name;
    }

    /** The account's name on the target system (its shadow's name). */
    public String name() {
        return name;
    }

    /** The account's shadow in the repository, as the owner links it. */
    public RepositoryShadow shadow() {
        return new RepositoryShadow(tester, owner, resource, name);
    }

    /** The account on the target system ({@link Resource#account}): it need not exist there. */
    public Account onTarget() {
        return tester.target(resource).account(name);
    }

    @Override
    public String toString() {
        return name + " on " + resource + " of " + owner;
    }

    /**
     * The live accounts the focus links on the resource (dead shadows left out), by name ({@code name} null: any);
     * shadows as the repository has them.
     */
    static List<ShadowType> linked(MidPointConfigTester tester, FocusType focus, String resource, String name) {
        String resourceOid = tester.target(resource).system().resourceOid();
        List<ShadowType> out = new ArrayList<>();
        for (ObjectReferenceType link : focus.getLinkRef()) {
            ShadowType shadow = tester.deployment().api().find(ShadowType.class, link.getOid());
            if (shadow != null && shadow.getResourceRef() != null
                    && resourceOid.equals(shadow.getResourceRef().getOid())
                    && !Boolean.TRUE.equals(shadow.isDead())
                    && (name == null || name.equals(shadow.getName().getOrig()))) {
                out.add(shadow);
            }
        }
        return out;
    }
}
