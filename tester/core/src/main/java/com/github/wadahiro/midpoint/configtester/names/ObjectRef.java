package com.github.wadahiro.midpoint.configtester.names;

/**
 * An object of the deployment by name (and the OID its file gives it, if any). Two refs are equal when they name an
 * object of the same type by the same name, whatever their OID and lines: the refs the views read back
 * ({@code user.roles()}) equal the generated constants ({@code Roles.DEPT_IT}, {@code InitialRoles.SUPERUSER}).
 */
public abstract class ObjectRef extends DeploymentName {

    private final String oid;

    ObjectRef(String name, String oid, Lines lines) {
        super(name, lines);
        this.oid = oid == null || oid.isEmpty() ? null : oid;
    }

    /** The OID of the object's file; null when the file gives none. */
    public String oid() {
        return oid;
    }

    /** The local name of the object's type in midPoint's schema ({@code RoleType}). */
    public abstract String type();

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ObjectRef)) {
            return false;
        }
        ObjectRef ref = (ObjectRef) other;
        return type().equals(ref.type()) && name().equals(ref.name());
    }

    @Override
    public int hashCode() {
        return 31 * type().hashCode() + name().hashCode();
    }

    /**
     * A ref of the object of this type ({@code RoleType}) and name, as the views read it back: a role, an org, a
     * service or an archetype as its own ref, any other type as a {@link MidPointObjectRef}.
     */
    public static ObjectRef of(String type, String name, String oid) {
        switch (type) {
            case "RoleType":
                return new RoleRef(name, oid, Lines.ALL);
            case "OrgType":
                return new OrgRef(name, oid, Lines.ALL);
            case "ServiceType":
                return new ServiceRef(name, oid, Lines.ALL);
            case "ArchetypeType":
                return new ArchetypeRef(name, oid, Lines.ALL);
            case "UserType":
                return new UserRef(name, oid, Lines.ALL);
            case "ResourceType":
                return new ResourceRef(name, oid, Lines.ALL);
            default:
                return new MidPointObjectRef(type, type, name, oid, Lines.ALL);
        }
    }
}
