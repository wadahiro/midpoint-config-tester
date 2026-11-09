package com.github.wadahiro.midpoint.tester;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * One record midPoint wrote to its audit log during a test, as the tester captured it: the same fields as a row of
 * the audit table ({@code m_audit_event}), by name rather than by number.
 */
public final class AuditRecord {

    private final String eventType;
    private final String stage;
    private final String outcome;
    private final Instant timestamp;
    private final String targetOid;
    private final String targetName;
    private final String targetType;
    private final String initiatorOid;
    private final String initiatorName;
    private final String channel;
    private final String taskOid;
    private final String message;
    private final List<String> deltas;

    public AuditRecord(String eventType, String stage, String outcome, Instant timestamp, String targetOid,
            String targetName, String targetType, String initiatorOid, String initiatorName, String channel,
            String taskOid, String message, List<String> deltas) {
        this.eventType = eventType;
        this.stage = stage;
        this.outcome = outcome;
        this.timestamp = timestamp;
        this.targetOid = targetOid;
        this.targetName = targetName;
        this.targetType = targetType;
        this.initiatorOid = initiatorOid;
        this.initiatorName = initiatorName;
        this.channel = channel;
        this.taskOid = taskOid;
        this.message = message;
        this.deltas = Collections.unmodifiableList(deltas);
    }

    /** midPoint's {@code AuditEventType}: {@code ADD_OBJECT}, {@code MODIFY_OBJECT}, {@code DELETE_OBJECT}... */
    public String eventType() {
        return eventType;
    }

    /** {@code REQUEST} or {@code EXECUTION} (4.8 and later also {@code RESOURCE}). */
    public String stage() {
        return stage;
    }

    /** The {@code OperationResultStatus} of the operation ({@code SUCCESS}, {@code FATAL_ERROR}...); null if none. */
    public String outcome() {
        return outcome;
    }

    /** When it was recorded, by midPoint's clock. */
    public Instant timestamp() {
        return timestamp;
    }

    public String targetOid() {
        return targetOid;
    }

    /** The target's name as recorded, or null. */
    public String targetName() {
        return targetName;
    }

    /** The target's type ({@code UserType}...), or null. */
    public String targetType() {
        return targetType;
    }

    public String initiatorOid() {
        return initiatorOid;
    }

    /** The initiator's name, or null (no initiator, or one recorded by OID only). */
    public String initiatorName() {
        return initiatorName;
    }

    public String channel() {
        return channel;
    }

    /** The task that did the operation, or null. */
    public String taskOid() {
        return taskOid;
    }

    public String message() {
        return message;
    }

    /** The deltas of the record, one line each: {@code DELETE UserType 0f3c...}, with their outcome if any. */
    public List<String> deltas() {
        return deltas;
    }

    @Override
    public String toString() {
        return eventType + " " + stage + " " + (targetType != null ? targetType + " " : "")
                + (targetName != null ? targetName : targetOid) + (outcome != null ? " " + outcome : "");
    }
}
