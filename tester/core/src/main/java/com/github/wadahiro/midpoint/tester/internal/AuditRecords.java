package com.github.wadahiro.midpoint.tester.internal;

/**
 * What the lines' readers of the audit log share for the tester's
 * {@link com.github.wadahiro.midpoint.tester.AuditRecord}.
 */
final class AuditRecords {

    /** The audit log's position before any record. */
    static final long START = 0;

    private AuditRecords() {
    }

    /** One delta of a record: {@code DELETE UserType 0f3c...}, with its outcome if any. */
    static String delta(Object changeType, String objectType, String oid, Object status) {
        return changeType + " " + objectType + " " + oid + (status != null ? " " + status : "");
    }
}
