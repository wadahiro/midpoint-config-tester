package com.github.wadahiro.midpoint.tester.internal;

import java.util.Map;

/**
 * midPoint as a test has it at one point ({@link Deployment#checkpoint()}), to come back to
 * ({@link Deployment#restore(Checkpoint)}): the repository and audit database, the target systems, the mapped files
 * and the configuration index (a {@link TesterRuntime.State}), the stand-in databases, midPoint's clock (moved or
 * stopped), whether the regular task runs are held, and what the test has seen so far (mails, notifications, captured
 * logs, the HTTP stand-in's requests and answers, the audit and schedule positions, when each Quartz trigger was made by
 * midPoint's clock). Not an API: the Kotest layer's
 * containers share their prefix through it.
 */
public final class Checkpoint {

    final TesterRuntime.State state;
    final Map<String, String> databases;
    final Long clockOverride;
    final Long clockOffset;
    final int mails;
    final int notifications;
    final int logs;
    final Object[] http;
    final Object[] marks;
    final Map<org.quartz.TriggerKey, Long> scheduledAt;
    boolean released;

    Checkpoint(TesterRuntime.State state, Map<String, String> databases, Long clockOverride, Long clockOffset,
            int mails, int notifications, int logs, Object[] http, Object[] marks,
            Map<org.quartz.TriggerKey, Long> scheduledAt) {
        this.state = state;
        this.databases = databases;
        this.clockOverride = clockOverride;
        this.clockOffset = clockOffset;
        this.mails = mails;
        this.notifications = notifications;
        this.logs = logs;
        this.http = http;
        this.marks = marks;
        this.scheduledAt = scheduledAt;
    }
}
