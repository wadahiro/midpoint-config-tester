package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One call midPoint made to the connector of a resource in a block ({@link ConnectorCalls}): the operation, the object
 * class and the account and, for {@code add} and {@code modify}, the attributes written (local names; activation and
 * credentials as {@code activation/administrativeStatus}, {@code credentials/password/value}).
 */
public final class ConnectorCall {

    /** The operations that change the target system; the others read it. */
    static final Set<String> WRITES = Set.of("add", "modify", "delete", "executeScript");

    private final String resource;
    private final String operation;
    private final String objectClass;
    private final String account;
    private final List<String> attributes;
    private final boolean failed;

    ConnectorCall(String resource, String operation, String objectClass, String account, List<String> attributes,
            boolean failed) {
        this.resource = resource;
        this.operation = operation;
        this.objectClass = objectClass;
        this.account = account;
        this.attributes = Collections.unmodifiableList(new ArrayList<>(attributes));
        this.failed = failed;
    }

    /** The resource's name. */
    public String resource() {
        return resource;
    }

    /**
     * {@code add}, {@code modify}, {@code delete}, {@code executeScript} (writes), {@code fetch}, {@code search},
     * {@code count}, {@code fetchChanges}, {@code fetchCurrentToken}, {@code test} (reads).
     */
    public String operation() {
        return operation;
    }

    public boolean isWrite() {
        return WRITES.contains(operation);
    }

    /** The object class's local name, e.g. {@code AccountObjectClass}, {@code inetOrgPerson}; null if not known. */
    public String objectClass() {
        return objectClass;
    }

    /** The account's name ({@code icfs:name}), else its identifiers; null for a search or a count. */
    public String account() {
        return account;
    }

    public List<String> attributes() {
        return attributes;
    }

    /** Whether the connector threw (midPoint handles the error: a retry, a pending operation, a failed task). */
    public boolean failed() {
        return failed;
    }

    /** Checks that the call wrote exactly these attributes: each of them, and nothing else. */
    public ConnectorCall only(String... attributes) {
        List<String> expected = Arrays.asList(attributes);
        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(this.attributes);
        List<String> extra = new ArrayList<>(this.attributes);
        extra.removeAll(expected);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new AssertionError(this + ": expected only " + expected + " to be written"
                    + (missing.isEmpty() ? "" : ", but not " + missing)
                    + (extra.isEmpty() ? "" : ", but also " + extra));
        }
        return this;
    }

    @Override
    public String toString() {
        return resource + " " + operation + (objectClass != null ? " " + objectClass : "")
                + (account != null ? " " + account : "") + (attributes.isEmpty() ? "" : " " + attributes)
                + (failed ? " (failed)" : "");
    }
}
