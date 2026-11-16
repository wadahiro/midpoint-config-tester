package com.github.wadahiro.midpoint.configtester;

import java.time.Instant;
import java.time.LocalDate;

import javax.xml.namespace.QName;

/**
 * An assignment to make ({@link Focus#assign(String, java.util.function.Consumer)}, {@link Request#assign(String,
 * java.util.function.Consumer)}, {@link Modification#assign(String, java.util.function.Consumer)}): its relation and
 * its validity, e.g. {@code t.actor("jdoe").request().assign("VPN Access", a -> a.validTo(end))}. A date is the start
 * of that day in the server's time zone ({@code tester.timeZone}): {@code validTo(end)} is valid until {@code end}
 * begins.
 */
public final class NewAssignment {

    private final MidPointConfigTester tester;
    QName relation;
    Instant validFrom;
    Instant validTo;

    NewAssignment(MidPointConfigTester tester) {
        this.tester = tester;
    }

    /** The relation ({@code SchemaConstants.ORG_MANAGER}, {@code ORG_OWNER}); the default relation without it. */
    public NewAssignment relation(QName relation) {
        this.relation = relation;
        return this;
    }

    /** The assignment is valid from this instant on (its {@code activation/validFrom}). */
    public NewAssignment validFrom(Instant validFrom) {
        this.validFrom = validFrom;
        return this;
    }

    public NewAssignment validFrom(LocalDate validFrom) {
        return validFrom(startOf(validFrom));
    }

    /** The assignment is valid until this instant (its {@code activation/validTo}). */
    public NewAssignment validTo(Instant validTo) {
        this.validTo = validTo;
        return this;
    }

    public NewAssignment validTo(LocalDate validTo) {
        return validTo(startOf(validTo));
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(tester.clock().zone()).toInstant();
    }

    static NewAssignment of(MidPointConfigTester tester, java.util.function.Consumer<NewAssignment> spec) {
        NewAssignment assignment = new NewAssignment(tester);
        spec.accept(assignment);
        return assignment;
    }
}
