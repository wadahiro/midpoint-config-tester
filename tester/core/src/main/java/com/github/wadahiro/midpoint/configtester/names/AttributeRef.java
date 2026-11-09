package com.github.wadahiro.midpoint.configtester.names;

/**
 * An attribute of a resource's accounts (a CSV column, an LDAP attribute), by its local name ({@code cn}, not
 * {@code ri:cn}), as the resource's {@code schemaHandling}, its stored schema, its connector configuration or its
 * initial data names it.
 */
public final class AttributeRef extends DeploymentName {

    private final String resource;

    public AttributeRef(String resource, String name, Lines lines) {
        super(name, lines);
        this.resource = resource;
    }

    /** The name of the resource it belongs to. */
    public String resource() {
        return resource;
    }

    @Override
    public String kind() {
        return "attribute of " + resource;
    }

    /** The name, checked to belong to the resource and to exist on the line. */
    public String of(String resourceName, String line) {
        if (!resource.equals(resourceName)) {
            throw new AssertionError(this + " is used on resource " + resourceName);
        }
        return on(line);
    }
}
