package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;

import com.github.wadahiro.midpoint.tester.LogEntry;
import com.github.wadahiro.midpoint.tester.Mail;

/**
 * An exhaustive verification of a block's {@link Effects} ({@link Effects#claims()}): the same selections, whose
 * {@code exactly}, {@code none}, {@code single} and {@code ignored} claim what they select; {@link #verifyAll()} then
 * fails listing every changed object, connector write, mail and error logged that no check claimed, e.g.
 *
 * <pre>{@code
 * Claims claims = effects.claims();
 * claims.changes().of("jdoe").single().only("activation/validTo");
 * claims.connectorCalls().to("LDAP").modifies().of("jdoe").single();
 * claims.mails().to("jdoe@example.com").withSubject("renewed").single();
 * claims.verifyAll();
 * }</pre>
 *
 * In Kotlin: {@code effects.verifyAll { changes().of("jdoe").single(); ... }}. Connector reads, logs below ERROR and
 * audit records need no claim.
 */
public final class Claims {

    private final Effects effects;
    private final Ledger ledger;

    Claims(Effects effects, Ledger ledger) {
        this.effects = effects;
        this.ledger = ledger;
    }

    public Changes changes() {
        return Changes.of(effects.changeList(), ledger);
    }

    public ConnectorCalls connectorCalls() {
        return ConnectorCalls.of(effects.connectorCallList(), ledger);
    }

    public Mails mails() {
        return Mails.ofBlock(effects.mailList(), ledger);
    }

    /** The logs of the block; an error logged has to be claimed ({@code logs().errors().single()}). */
    public Logs logs() {
        return Logs.ofBlock(effects.logList(), ledger);
    }

    /** Fails if a changed object, a connector write, a mail or an error logged was not claimed. */
    public Effects verifyAll() {
        List<String> unclaimed = new ArrayList<>();
        for (ObjectChange change : effects.changeList()) {
            if (!ledger.isClaimed(change)) {
                unclaimed.add(change.toString());
            }
        }
        for (ConnectorCall call : effects.connectorCallList()) {
            if (call.isWrite() && !ledger.isClaimed(call)) {
                unclaimed.add("connector: " + call);
            }
        }
        for (Mail mail : effects.mailList()) {
            if (!ledger.isClaimed(mail)) {
                unclaimed.add("mail: " + mail.recipients() + " '" + mail.subject() + "'");
            }
        }
        for (LogEntry entry : effects.logList()) {
            if ("ERROR".equals(entry.level()) && !ledger.isClaimed(entry)) {
                unclaimed.add("log: " + entry);
            }
        }
        if (!unclaimed.isEmpty()) {
            throw new AssertionError("Effects of the block not verified:\n  " + String.join("\n  ", unclaimed) + "\n"
                    + Effects.ignoredNote());
        }
        return effects;
    }
}
