package com.github.wadahiro.midpoint.tester;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationType;

/**
 * An authorization of a logged-in user as midPoint compiled it at login ({@code Access.authorizations()}): the
 * authorization as written, and where it comes from (the role, org or archetype, as midPoint describes it).
 */
public final class CompiledAuthorization {

    private final AuthorizationType authorization;
    private final String source;

    public CompiledAuthorization(AuthorizationType authorization, String source) {
        this.authorization = authorization;
        this.source = source;
    }

    /** The authorization as written in its role (its object and target selectors, items, phase). */
    public AuthorizationType authorization() {
        return authorization;
    }

    /** Where it comes from, e.g. {@code role:00000000-0000-0000-0000-000000000008(End user)}; may be null. */
    public String source() {
        return source;
    }
}
