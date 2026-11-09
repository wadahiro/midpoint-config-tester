package com.github.wadahiro.midpoint.tester.internal;

import java.util.concurrent.Callable;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * The line-specific part of the runtime: a subclass of midPoint's own {@code AbstractModelIntegrationTest} (one
 * instance per JVM), wired by Spring's TestContext but driven by the JUnit extension instead of TestNG.
 */
public interface Harness {

    <T> T bean(Class<T> type);

    /** A task owned by the administrator (cases, approvals and certification fail on a task without an owner). */
    Task createTask(String name);

    /** Runs {@code operation} with whatever thread-local test context the line's helpers expect. */
    <T> T inOperationContext(String name, Task task, Callable<T> operation) throws Exception;

    void loginAs(String username) throws Exception;

    PrismObject<UserType> findUser(String name) throws Exception;

    /** Tests the resource connection (fetches its schema); fails on error. */
    void checkResource(String oid, Task task, OperationResult result) throws Exception;

    /**
     * Imports one account from the resource, as the "import" action in the GUI does; the account is found by the value
     * of its naming attribute (e.g. {@code icfs:name} or {@code ri:dn}).
     */
    void importOneAccount(String resourceOid, QName namingAttribute, String accountName, Task task, OperationResult result)
            throws Exception;

    /**
     * Drops in-memory state that would no longer match the database after it was reset: the repository's own ID maps
     * (not covered by midPoint's cache invalidation) and every midPoint cache.
     */
    void resetInternals();

    // ---- background work ----------------------------------------------------------------------------------------

    /**
     * Waits until midPoint has no background work: no task thread running, no single-run task runnable now and no
     * recurring task due now (a recurring task between its runs, a waiting task and a task scheduled for later do not
     * count). Returns what is still pending after the timeout (empty when done).
     */
    java.util.List<String> awaitBackgroundWork(long timeoutMillis);

    /** Suspends the tasks {@link #awaitBackgroundWork} would wait for (before a reset). */
    void stopBackgroundWork();

    /** midPoint's local Quartz scheduler (in-memory job store); see {@link TaskSchedules}. */
    org.quartz.Scheduler quartzScheduler();

    /** The raw {@code AbstractModelIntegrationTest} instance, for helpers the neutral API does not cover. */
    Object raw();
}
