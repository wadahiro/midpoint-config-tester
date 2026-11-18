package com.github.wadahiro.midpoint.tester.internal;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * The administrator's login at the end of a reset, reused. A login in the tester is no session: midPoint builds the
 * user's principal (the user, its authorizations from its roles and the security policy) and puts it into Spring
 * Security's context, with no expiry. The principal follows the rows it was built from: when the reset restores the
 * state it was built on, and none of those rows was written since (the user itself, roles, orgs, archetypes, services,
 * the security policy and the system configuration: {@link #written}), the reset puts the same authentication back
 * instead of building it again. As a safeguard the user's version in the database is checked against the principal's.
 */
final class LoginCache {

    /** The types whose rows a principal is built from (besides the user itself). */
    private static final Set<String> SECURITY_TYPES = Set.of("RoleType", "OrgType", "ArchetypeType", "ServiceType",
            "AbstractRoleType", "SecurityPolicyType", "SystemConfigurationType");

    private static volatile boolean changed = true;
    private static volatile Object inLineWith;
    private static volatile String username;
    private static volatile Authentication authentication;
    private static volatile String userOid;

    private LoginCache() {
    }

    /** A row was written ({@code type}: the simple name of its type, {@code oid} if known). */
    static void written(String type, String oid) {
        if (type == null || SECURITY_TYPES.contains(type)
                || "UserType".equals(type) && (oid == null || oid.equals(userOid))) {
            changed = true;
        }
    }

    /** Forgets the login (another database): the next reset logs in afresh. */
    static void forget() {
        inLineWith = null;
        authentication = null;
        changed = true;
    }

    /**
     * Puts back the login of {@code user} made at a reset to {@code state}, when nothing it was built from changed
     * since and the user's version is the principal's; false: the caller logs in and calls {@link #remember}.
     */
    static boolean reuse(Object state, String user, RepositoryService repository) {
        Authentication cached = authentication;
        if (cached == null || state == null || state != inLineWith || changed || !user.equals(username)) {
            return false;
        }
        String version = principalVersion(cached);
        if (version == null) {
            return false;
        }
        try {
            if (!version.equals(repository.getVersion(UserType.class, userOid, new OperationResult("login cache")))) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        SecurityContextHolder.getContext().setAuthentication(cached);
        return true;
    }

    /** The login just made for {@code user} at a reset to {@code state}, to reuse at the next reset to it. */
    static void remember(Object state, String user) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        String oid = current != null ? principalOid(current) : null;
        if (oid == null || principalVersion(current) == null) {
            forget();
            return;
        }
        authentication = current;
        username = user;
        userOid = oid;
        inLineWith = state;
        changed = false;
    }

    /** The OID of midPoint's principal ({@code MidPointPrincipal.getOid}, every line), or null. */
    private static String principalOid(Authentication authentication) {
        Object value = call(authentication.getPrincipal(), "getOid");
        return value instanceof String ? (String) value : null;
    }

    /** The version of the principal's user ({@code getFocus} 4.4+, {@code getUser} 4.0), or null. */
    private static String principalVersion(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        Object focus = call(principal, "getFocus");
        if (focus == null) {
            focus = call(principal, "getUser");
        }
        Object version = focus != null ? call(focus, "getVersion") : null;
        return version instanceof String ? (String) version : null;
    }

    private static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
