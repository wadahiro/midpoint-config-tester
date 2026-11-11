package com.github.wadahiro.midpoint.tester;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SynchronizationSituationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * A real midPoint (Spring context, Clockwork, provisioning, cases, approvals, certification, reports) on an in-memory
 * PostgreSQL, driven through its production entry points: {@code MidPointConfigTester#midpoint()}, the escape hatch of
 * a test for what the tester's API has no call for.
 *
 * Every driver call returns a {@link Run} with the evaluations midPoint ran, and throws {@link OperationFailed} (which
 * carries the run) if midPoint reports an error. Configuration given as files is indexed so that evaluations can be
 * selected by their place in it ({@link Identity}: owner object and structural path), not by line numbers.
 */
public interface MidPoint {

    /** midPoint release line of this runtime, e.g. {@code "4.8"}. */
    String line();

    PrismContext prismContext();

    // ---- configuration ---------------------------------------------------------------------------------------

    /**
     * Imports an object as the GUI import does with its defaults ("Full processing"): references by filter are
     * resolved and the object is added through the Clockwork, overwriting an existing one.
     */
    Run importObject(File file);

    /**
     * Imports every {@code *.xml} file of the directory, in file name order, in one run: the evaluations of all
     * files, and one result (an error in any file fails the run).
     */
    Run importObjects(File directory);

    /** Like {@link #importObject(File)}, for XML given as text. */
    Run importObjectXml(String xml);

    /**
     * Imports an object as the GUI import does with "Full processing" unchecked: stored as is (raw), nothing is
     * evaluated.
     */
    void importObjectRaw(File file);

    /**
     * Imports a resource definition as deployed (e.g. a CSV or LDAP resource) with only its connector replaced by the
     * mirror connector: an in-memory target system with the real resource's schema and capabilities (see
     * {@link TargetSystem}). The resource as imported is written to {@code target/tester-resources/}. With
     * {@link ResourceOptions#realConnector()} the resource keeps its real CSV or LDAP connector instead.
     */
    TargetSystem importResource(File resourceFile);

    TargetSystem importResource(File resourceFile, ResourceOptions options);

    /** The target system of a resource imported by {@link #importResource(File)}, by resource OID or name. */
    TargetSystem target(String resource);

    /** The file of a resource (by name or OID) imported on the real CSV connector, see {@link CsvFile}. */
    CsvFile csv(String resource);

    /** The server of a resource (by name or OID) imported on the real LDAP connector, see {@link LdapDirectory}. */
    LdapDirectory ldap(String resource);

    // ---- drivers ---------------------------------------------------------------------------------------------

    /** Adds the object through the Clockwork (an ADD operation: assignments evaluated, projections provisioned). */
    Run addObject(ObjectType object);

    /** Like {@link #addObject(ObjectType)}; the OID is generated if the XML has none ({@link Run#oid()}). */
    Run addObjectXml(String xml);

    /** Executes the changes through {@code ModelService.executeChanges}. */
    Run execute(ObjectDelta<? extends ObjectType> delta);

    Run execute(Collection<ObjectDelta<? extends ObjectType>> deltas);

    /**
     * Replaces the values of an item of the object (by name or OID) through the Clockwork; plain strings are
     * accepted for polystring items.
     */
    <O extends ObjectType> Run modify(Class<O> type, String nameOrOid, ItemPath path, Object... values);

    <O extends ObjectType> Run recompute(Class<O> type, String nameOrOid);

    /**
     * Merges the right object into the left one (both by name or OID) as the GUI's merge does
     * ({@code ModelService.mergeObjects}) with the system configuration's {@code mergeConfiguration} of this name:
     * the left object is changed, the right one deleted.
     */
    <O extends ObjectType> Run merge(Class<O> type, String left, String right, String mergeConfiguration);

    /**
     * Imports one account of the resource (by OID or name) by its name: inbound mappings, correlation, synchronization
     * reactions, and whatever the resulting focus change evaluates.
     */
    Run importAccount(String resource, String accountName);

    /**
     * Computes the changes without executing them ({@code ModelInteractionService.previewChanges}); the model context
     * is in {@link Run#modelContext()}. On 4.8 nothing is written to the repository or the target systems and
     * sequences do not advance, but resource searches still create shadows.
     */
    Run preview(ObjectDelta<? extends ObjectType> delta);

    Run preview(Collection<ObjectDelta<? extends ObjectType>> deltas);

    // ---- changes, failures, hooks, notifications, authorizations, value policies, scripts --------------------

    /** A modification of the object (by name or OID) to build and run, see {@link Change}. */
    <O extends ObjectType> Change change(Class<O> type, String nameOrOid);

    /** Assigns the role, org or service (by name or OID) to the focus, with the default relation. */
    <F extends FocusType> Run assign(Class<F> type, String focus, String target);

    /** Assigns the target with this relation (e.g. {@code SchemaConstants.ORG_MANAGER}). */
    <F extends FocusType> Run assign(Class<F> type, String focus, String target, QName relation);

    /** Deletes the focus's assignments of the target with the default relation; fails if there is none. */
    <F extends FocusType> Run unassign(Class<F> type, String focus, String target);

    <F extends FocusType> Run unassign(Class<F> type, String focus, String target, QName relation);

    /** Adds values to an item of the object (by name or OID), see {@link #modify}. */
    <O extends ObjectType> Run modifyAdd(Class<O> type, String nameOrOid, ItemPath path, Object... values);

    /** Deletes values of an item of the object (by name or OID), see {@link #modify}. */
    <O extends ObjectType> Run modifyDelete(Class<O> type, String nameOrOid, ItemPath path, Object... values);

    /** Deletes the object through the Clockwork (projections are deprovisioned as configured). */
    <O extends ObjectType> Run deleteObject(Class<O> type, String nameOrOid);

    /** Executes the changes with these options (e.g. {@code ModelExecuteOptions.create().reconcile()}, raw). */
    Run execute(Collection<ObjectDelta<? extends ObjectType>> deltas, ModelExecuteOptions options);

    Run execute(ObjectDelta<? extends ObjectType> delta, ModelExecuteOptions options);

    /** Like {@link #execute}, but a failure is in the returned run ({@link Run#isSuccess()}) instead of thrown. */
    Run tryExecute(ObjectDelta<? extends ObjectType> delta);

    Run tryExecute(Collection<ObjectDelta<? extends ObjectType>> deltas, ModelExecuteOptions options);

    /**
     * Runs any driver call without throwing: returns its run, or the run of the {@link OperationFailed} it threw,
     * e.g. {@code mp.attempt(() -> mp.assign(UserType.class, "jdoe", "Admin"))}.
     */
    Run attempt(Supplier<Run> driverCall);

    /**
     * The messages sent to {@code dummy} transports since the test method started (each run has its own, see
     * {@link Run#notifications()}). Notifications are enabled as in production.
     */
    List<Notification> notifications();

    /**
     * The mails midPoint's mail transports delivered since the test method started (each run has its own, see
     * {@link Run#mails()}). The mail servers of an imported system configuration are redirected to the tester's
     * in-memory SMTP server.
     */
    List<Mail> mails();

    /**
     * The records midPoint wrote to its audit log since the test method started, in the order written: the request
     * and execution records of each change, logins... midPoint audits to its repository database (in memory), as in
     * production, so its own searches of the audit log (audit reports, dashboards, scripts) work.
     */
    List<AuditRecord> auditRecords();

    /** The records midPoint wrote to its audit log while the code ran (and the work it waited for). */
    List<AuditRecord> auditRecords(Runnable during);

    /**
     * The HTTP requests the configuration made to the URLs the manifest redirects to the tester
     * ({@code http.redirect}) since the test began, in the order received (the stand-in answered each {@code 200}).
     */
    List<HttpRequest> httpRequests();

    /** Calls made as this user, with its authorizations, see {@link Access}. */
    Access actor(String username);

    /**
     * Makes the driver calls of the block as the caller presents them ({@link Caller}: the user logged in, privileged
     * or not, the owner of their tasks, their channel), as a service that calls midPoint on a user's behalf does;
     * then the administrator again. A call of the block that names its own user ({@link #actor}) keeps it.
     */
    void callAs(Caller caller, Runnable calls);

    /**
     * Checks the value against the value policy (by name or OID) as midPoint does for a password
     * ({@code ModelInteractionService.validateValue}); {@code checkExpression}s are evaluated and recorded.
     */
    ValueCheck validateValue(String valuePolicy, String value);

    /** Like {@link #validateValue(String, String)}, for a value of this object (the {@code object} variable). */
    ValueCheck validateValue(String valuePolicy, String value, ObjectType object);

    /** Generates a value by the value policy (by name or OID); the value is random, its evaluations are recorded. */
    Answer<String> generateValue(String valuePolicy);

    /** An ad-hoc script with variables given by the test, see {@link ScriptCall}. */
    ScriptCall script(String code);

    /**
     * Executes a bulk action ({@code <s:executeScript>} or a scripting expression, as XML) as the bulk action page
     * does, synchronously; the scripts of {@code execute-script} actions are recorded.
     */
    Answer<BulkActionOutput> bulkAction(String xml);

    // ---- asynchronous work and services ------------------------------------------------------------------------
    //
    // A driver call returns when the background tasks it started (approved-change execution, certification and
    // report tasks...) have finished: their evaluations are in its run, marked with Evaluation#task(). It fails if
    // they do not finish within tester.taskTimeout seconds (60). The reset after each test waits for (then suspends)
    // what is still running, and sets the clock back.

    /**
     * Waits until midPoint has no background work (no task thread running, no single-run task due); the run has the
     * evaluations of what ran meanwhile.
     */
    Run waitForTasks();

    /** Runs the task (by name or OID) now, e.g. an imported recurring task, and waits for it. */
    Run runTask(String task);

    /**
     * Resumes the suspended task (by name or OID), as the GUI's "Resume" ({@code TaskService.resumeTask}): a
     * deployment that ships a task suspended and resumes it per environment. A recurring task, which the tester holds,
     * then runs only when the test runs it ({@link #runTask}, the due tasks of {@code MidPointConfigTester#clock()}).
     */
    Run resumeTask(String task);

    /**
     * Reconciles the resource's (by name or OID) default accounts ({@code account/default}) as the GUI's "Reconcile"
     * does: a single-run reconciliation task (an activity on 4.4+, the reconciliation task handler on 4.0), run now and
     * waited for. Its evaluations (inbounds, correlation, reactions, what the focus changes evaluate) are in the run,
     * with {@link Evaluation#task()} set. A deployed reconciliation task runs with {@link #runTask(String)}.
     */
    Run reconcile(String resource);

    /** Like {@link #reconcile(String)}, for the object type of this kind and intent. */
    Run reconcile(String resource, String kind, String intent);

    /** Imports the resource's default accounts as a single-run import task, like {@link #reconcile(String)}. */
    Run importAccounts(String resource);

    /**
     * Synchronizes the changes of the resource's default accounts as the GUI's "Live sync" task does: a single-run
     * live synchronization task (an activity on 4.4+, the live sync task handler on 4.0), run now and waited for. As in
     * production, its first run only fetches the token (the changes made after it are the ones the next run sees): the
     * first call creates the task and runs it, a later call runs the same task again from its token. The call fails
     * when the task's run fails (midPoint then suspends the task). The resource needs a connector that can synchronize:
     * the real CSV or LDAP connector, or the mirror. A deployed live synchronization task runs with
     * {@link #runTask(String)}.
     */
    Run liveSync(String resource);

    /** Like {@link #liveSync(String)}, for the object type of this kind and intent. */
    Run liveSync(String resource, String kind, String intent);

    Approvals approvals();

    Certification certification();

    Reports reports();

    Dashboards dashboards();

    /**
     * The files scripts read and write: directories the configuration names, mapped to directories of the test
     * (see {@link MappedFiles}); what a driver call wrote is in {@link Run#filesWritten()}.
     */
    MappedFiles files();

    /**
     * Names the driver calls that follow (until the next {@code step} or the end of the test method), instead of the
     * driver's label. Null goes back to the labels.
     */
    void step(String name);

    // ---- reading ---------------------------------------------------------------------------------------------

    /** The object with this name or OID, or null. */
    <O extends ObjectType> O find(Class<O> type, String nameOrOid);

    /** The user with this name, or null. */
    UserType user(String name);

    /**
     * The shadow linked to the focus (a user, else any focus, by name or OID) on the resource (by name or OID), as the
     * repository has it: situation, kind and intent, linked or dead, synchronization timestamps. Shadows the focus
     * links with any relation count (a dead one is linked as {@code related}); a live one is preferred to dead ones.
     * Null if there is none; fails if the focus does not exist, or if several live shadows match (several object types
     * or intents: give kind and intent).
     */
    Shadow shadow(String focus, String resource);

    /** Like {@link #shadow(String, String)}, of the object type with this kind ({@code account}) and intent. */
    Shadow shadow(String focus, String resource, String kind, String intent);

    /**
     * The synchronization situation of the focus's shadow on the resource ({@link #shadow(String, String)}), e.g.
     * {@code LINKED}; null if the focus has no shadow there.
     */
    SynchronizationSituationType situation(String focus, String resource);

    // ---- escape hatches --------------------------------------------------------------------------------------

    /** A Spring bean of the running midPoint, e.g. {@code ModelService} or {@code RepositoryService}. */
    <T> T bean(Class<T> type);

    /**
     * A new instance of the class with midPoint's beans injected (its {@code @Autowired} fields and constructor), as
     * Spring makes a component: a class of an add-on that the test calls itself (a service of an API add-on, a
     * component the deployment's Spring configuration would register).
     */
    <T> T createBean(Class<T> type);

    /**
     * Runs the code as a driver call: in its own task (the {@link #callAs} caller's, if any) and result, its
     * evaluations recorded, the background work it starts waited for, failing as a driver call fails. For code of the
     * deployment the test calls itself, e.g. a service of an add-on: {@code mp.invoke("add service", (task, result) ->
     * service.addService(request, task, result))}.
     */
    <T> Answer<T> invoke(String label, Invocation<T> code);

    /** The line-specific {@code AbstractModelIntegrationTest} instance, for helpers this API does not cover. */
    Object harness();
}
