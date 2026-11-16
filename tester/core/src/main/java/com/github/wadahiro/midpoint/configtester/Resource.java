package com.github.wadahiro.midpoint.configtester;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.github.wadahiro.midpoint.configtester.names.AttributeRef;
import com.github.wadahiro.midpoint.tester.TargetSystem;

/**
 * The system behind a resource of the deployment ({@link MidPointConfigTester#source} / {@link MidPointConfigTester#target}), as the manifest
 * set it up (a mirror, a CSV file, an in-memory LDAP server). Accounts are named by the resource's naming attribute
 * (the CSV's unique column, the LDAP DN); attribute names are the resource's ({@code cn}, not {@code ri:cn}).
 *
 * <p>Changes here are made on the system itself, as its own administrator (or the HR department) would; midPoint
 * sees them when it reads the system next ({@link MidPointConfigTester#task}, {@link MidPointConfigTester#reconcile}).</p>
 */
public final class Resource {

    private final MidPointConfigTester tester;
    private final String name;

    Resource(MidPointConfigTester tester, String name) {
        this.tester = tester;
        this.name = name;
    }

    public String name() {
        return name;
    }

    MidPointConfigTester tester() {
        return tester;
    }

    /** Adds an account (a row, an entry) with these attributes, e.g. {@code add("1001", Map.of("firstName", "John"))}. */
    public Resource add(String account, Map<String, ?> attributes) {
        system().addAccount(account, attributes);
        return this;
    }

    /** Replaces the values of an attribute of the account (no values: removes them). */
    public Resource modify(String account, String attribute, Object... values) {
        system().modifyAccount(account, attribute, values);
        return this;
    }

    public Resource delete(String account) {
        system().deleteAccount(account);
        return this;
    }

    /**
     * Adds an account with the attributes of the row, typed by the resource's generated attributes:
     * {@code add("1001", row -> row.set(HrAttrs.FIRST_NAME, "John").set(HrAttrs.LAST_NAME, "Doe"))}.
     */
    public Resource add(String account, Consumer<Row> row) {
        Row r = new Row(this);
        row.accept(r);
        return add(account, r.attributes());
    }

    /**
     * Adds an account named by the value the row gives the resource's naming attribute (the CSV's name column, the
     * LDAP {@code dn}): {@code add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John"))}.
     */
    public Resource add(Consumer<Row> row) {
        Row r = new Row(this);
        row.accept(r);
        String naming = namingAttribute();
        Object id = r.attributes().get(naming);
        if (id == null) {
            throw new IllegalArgumentException("The row added to " + name + " has no value of its naming attribute "
                    + naming + ": " + r.attributes().keySet());
        }
        Map<String, Object> attributes = new LinkedHashMap<>(r.attributes());
        attributes.remove(naming);
        return add(id instanceof List ? String.valueOf(((List<?>) id).get(0)) : String.valueOf(id), attributes);
    }

    /** {@link #modify(String, String, Object...)} by the generated attribute ({@code HrAttrs.DEPARTMENT}). */
    public Resource modify(String account, AttributeRef attribute, Object... values) {
        return modify(account, attribute(attribute), values);
    }

    /** The attribute's name, checked to be one of this resource on this line. */
    String attribute(AttributeRef attribute) {
        return attribute.of(name, tester.line());
    }

    /** The local name of the attribute that names the accounts ({@code dn}, the CSV's name column). */
    String namingAttribute() {
        String naming = tester.deployment().namingAttribute(name);
        if (naming == null) {
            throw new IllegalArgumentException("No resource " + name + " in the deployment (resources: "
                    + tester.deployment().resources() + ")");
        }
        return naming;
    }

    /** The attributes of an account to add ({@link #add(Consumer)}), typed by the resource's generated attributes. */
    public static final class Row {
        private final Resource resource;
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        Row(Resource resource) {
            this.resource = resource;
        }

        /** The attribute's values (several: a multi-valued attribute). */
        public Row set(AttributeRef attribute, Object... values) {
            return set(resource.attribute(attribute), values);
        }

        /** By name, the escape hatch for an attribute the generated names do not have. */
        public Row set(String attribute, Object... values) {
            attributes.put(attribute, values.length == 1 ? values[0] : java.util.Arrays.asList(values));
            return this;
        }

        Map<String, Object> attributes() {
            return attributes;
        }
    }

    /** An account of the system, to check (it need not exist: {@link Account#doesNotExist()}). */
    public Account account(String account) {
        return new Account(tester, this, account);
    }

    /** The names of the accounts on the system now. */
    public List<String> accounts() {
        return system().accountNames();
    }

    /** A group of the system (the first object class of the resource that is not its accounts'); it need not exist. */
    public Group group(String group) {
        return new Group(tester, this, null, group);
    }

    /** The names of the groups on the system now. */
    public List<String> groups() {
        return system().groupNames();
    }

    /**
     * An object of any class of the system, by the name the resource uses for the class ({@code "groupOfNames"}, as in
     * its {@code schemaHandling}); it need not exist.
     */
    public TargetObject object(String objectClass, String name) {
        return new TargetObject(tester, this, objectClass, name);
    }

    public List<String> objects(String objectClass) {
        return system().objectNames(objectClass);
    }

    /**
     * Makes the provisioning scripts midPoint runs on the resource (its {@code <scripts>}) fail, as a communication
     * error does (an SSH connection that breaks: the SSH connector fails only so, a command's exit status is not
     * checked), so the test sees what the scripts' {@code criticality} makes of it; a reset or checkpoint undoes it.
     * The scripts are never run: the mirror accepts them, and {@code connectorCalls().scripts()} has what midPoint
     * asked for.
     */
    public Resource scriptsFail() {
        system().scriptsFail(true);
        return this;
    }

    /** The PoC's view of the system (groups, other object classes, passwords). */
    public TargetSystem system() {
        try {
            return tester.deployment().api().target(name);
        } catch (IllegalArgumentException | AssertionError e) {
            throw new IllegalArgumentException("No resource " + name + " in the deployment: " + e.getMessage(), e);
        }
    }
}
