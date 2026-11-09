package com.github.wadahiro.midpoint.configtester.names;

/**
 * An item of an object type by its path without namespaces, e.g. an extension item of users
 * ({@code extension/hrId}), as the deployment's extension schema defines it.
 */
public final class ItemRef extends DeploymentName {

    private final String ownerType;
    private final String namespace;

    public ItemRef(String ownerType, String namespace, String path, Lines lines) {
        super(path, lines);
        this.ownerType = ownerType;
        this.namespace = namespace;
    }

    /** The local name of the type the item belongs to ({@code UserType}). */
    public String ownerType() {
        return ownerType;
    }

    /** The namespace of the extension schema; null for items of midPoint's own schema. */
    public String namespace() {
        return namespace;
    }

    /** The path, e.g. {@code extension/hrId}. */
    public String path() {
        return name();
    }

    @Override
    public String kind() {
        return "item of " + ownerType;
    }
}
