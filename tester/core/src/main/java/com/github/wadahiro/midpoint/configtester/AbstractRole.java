package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;

/**
 * What a {@link Role}, {@link Org} and {@link Service} have besides being a {@link Focus}: their members, the focuses
 * midPoint made members of them (their {@code roleMembershipRef}, with the default relation: not managers or owners).
 *
 * @param <SELF> the class itself, which the assertions return
 */
public abstract class AbstractRole<SELF extends AbstractRole<SELF>> extends Focus<SELF> {

    AbstractRole(MidPointConfigTester tester, Class<? extends AbstractRoleType> type, String kind, String name) {
        super(tester, type, kind, name);
    }

    /** The names of the members (users, and roles, orgs or services that have this one), as the repository has them. */
    public List<String> members() {
        List<String> out = new ArrayList<>();
        for (FocusType member : tester.deployment().api().members(existing().getOid())) {
            out.add(member.getName().getOrig());
        }
        return out;
    }

    /** {@link #hasMember(String)} by the generated name ({@code Users.JACK}, a role, an org...). */
    public SELF hasMember(com.github.wadahiro.midpoint.configtester.names.ObjectRef focus) {
        return hasMember(focus.on(tester.line()));
    }

    public SELF hasNoMember(com.github.wadahiro.midpoint.configtester.names.ObjectRef focus) {
        return hasNoMember(focus.on(tester.line()));
    }

    public SELF hasMember(String focus) {
        List<String> members = members();
        if (!members.contains(focus)) {
            throw new AssertionError(kind + " " + name + ": expected a member " + focus + ", members: " + members);
        }
        return self();
    }

    public SELF hasNoMember(String focus) {
        List<String> members = members();
        if (members.contains(focus)) {
            throw new AssertionError(kind + " " + name + ": expected no member " + focus + ", members: " + members);
        }
        return self();
    }
}
