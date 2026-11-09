package com.github.wadahiro.midpoint.configtester.names;

/**
 * A name of the deployment, as generated from its configuration ({@code NamesGenerator}): an object, an attribute of a
 * resource, an extension item. Tests use the generated constants instead of strings, so a name that the configuration
 * no longer has fails the compilation; the load checks that every generated name exists in the loaded deployment.
 */
public abstract class DeploymentName {

    private final String name;
    private final Lines lines;

    DeploymentName(String name, Lines lines) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("A deployment name cannot be empty");
        }
        this.name = name;
        this.lines = lines == null ? Lines.ALL : lines;
    }

    /** The name as midPoint knows it (the object's name, the attribute's local name, the item path). */
    public String name() {
        return name;
    }

    /** The lines the configuration has it on. */
    public Lines lines() {
        return lines;
    }

    public boolean existsOn(String line) {
        return lines.includes(line);
    }

    /** What it is, for messages: {@code role}, {@code attribute of LDAP}, {@code item of UserType}. */
    public abstract String kind();

    /**
     * This name, checked to exist on the line: a name that only the overlay of another line has (or that this line's
     * overlay removes) fails the test with a clear message rather than with "not found".
     */
    public String on(String line) {
        if (!existsOn(line)) {
            throw new AssertionError(this + " is not in the deployment on midPoint " + line + " (" + lines
                    + " of the configuration)");
        }
        return name;
    }

    @Override
    public String toString() {
        return kind() + " " + name;
    }
}
