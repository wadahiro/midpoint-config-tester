package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One call midPoint made to the connector of a resource in a block ({@link ConnectorCalls}): the operation, the object
 * class and the account and, for {@code add} and {@code modify}, the attributes written (local names; activation and
 * credentials as {@code activation/administrativeStatus}, {@code credentials/password/value}). A provisioning script
 * ({@code executeScript}, a resource's {@code <scripts>}) has its language, code, host and arguments as midPoint
 * evaluated them; its attributes are the argument names:
 *
 * <pre>{@code
 * c.connectorCalls().to("AD").scripts().single().hasArgument("user", "jdoe").codeContains("mkdir").only("user");
 * }</pre>
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
    private final String language;
    private final String code;
    private final String host;
    private final Map<String, List<String>> arguments;

    ConnectorCall(String resource, String operation, String objectClass, String account, List<String> attributes,
            boolean failed, String language, String code, String host, Map<String, List<String>> arguments) {
        this.resource = resource;
        this.operation = operation;
        this.objectClass = objectClass;
        this.account = account;
        this.attributes = Collections.unmodifiableList(new ArrayList<>(attributes));
        this.failed = failed;
        this.language = language;
        this.code = code;
        this.host = host;
        this.arguments = arguments;
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

    /** A script's language ({@code bash}, {@code powershell}...); null for another call or a script without one. */
    public String language() {
        return language;
    }

    /** A script's code, as the resource has it (the connector puts the arguments in); null for another call. */
    public String code() {
        return code;
    }

    /** Where a script runs: {@code resource} or {@code connector}; null for another call. */
    public String host() {
        return host;
    }

    /** A script's arguments, by name, their values as midPoint evaluated them, as text; empty for another call. */
    public Map<String, List<String>> arguments() {
        return arguments;
    }

    /** Checks that the script has the argument with exactly these values. */
    public ConnectorCall hasArgument(String name, Object... values) {
        List<String> expected = new ArrayList<>();
        for (Object v : values) {
            expected.add(String.valueOf(v));
        }
        List<String> actual = arguments.get(name);
        if (actual == null || !actual.equals(expected)) {
            throw new AssertionError(this + ": expected the argument " + name + " = " + expected + ", but "
                    + (actual == null ? "it has none" : "it is " + actual) + "; arguments " + arguments);
        }
        return this;
    }

    /** Checks that the script's code contains the text. */
    public ConnectorCall codeContains(String text) {
        if (code == null || !code.contains(text)) {
            throw new AssertionError(this + ": expected a script whose code contains '" + text + "', but its code is "
                    + (code == null ? "none" : "'" + code + "'"));
        }
        return this;
    }

    /** Checks the script's language. */
    public ConnectorCall hasLanguage(String expected) {
        if (!expected.equals(language)) {
            throw new AssertionError(this + ": expected a script in " + expected + ", but it is in " + language);
        }
        return this;
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
        if (code != null) {
            return resource + " executeScript" + (language != null ? " " + language : "") + " '" + code + "' "
                    + arguments + (failed ? " (failed)" : "");
        }
        return resource + " " + operation + (objectClass != null ? " " + objectClass : "")
                + (account != null ? " " + account : "") + (attributes.isEmpty() ? "" : " " + attributes)
                + (failed ? " (failed)" : "");
    }
}
