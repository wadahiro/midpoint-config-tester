package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.github.wadahiro.midpoint.configtester.names.CertificationDefinitionRef;
import com.github.wadahiro.midpoint.configtester.names.ReportRef;
import com.github.wadahiro.midpoint.configtester.names.ResourceRef;
import com.github.wadahiro.midpoint.configtester.names.TaskRef;
import com.github.wadahiro.midpoint.configtester.names.UserRef;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.Run;
import com.github.wadahiro.midpoint.tester.internal.Deployment;

/**
 * One scenario over the loaded deployment (injected into {@link MidPointConfigTest} test methods): change the source
 * systems, run the deployed tasks, move the clock, and check users, accounts and mails, all by name. Every test method
 * gets a fresh one, on the deployment as loaded.
 *
 * <p>Calls that make midPoint work ({@link Task#run()}, {@link #reconcile}, {@link TestClock#runDue()}...)
 * return when midPoint has finished, including the background tasks they started, and fail with midPoint's error if
 * it reports one. Assertions read the current state each time and fail with what was expected, what is there and,
 * where the tester recorded them, the mappings that produced the value in this test.</p>
 */
public final class MidPointConfigTester {

    private final Deployment deployment;
    private final List<Run> runs = new ArrayList<>();
    /** What the values read through the views came from, by the value read, in the order last read. */
    private final Map<String, Supplier<String>> reads = new LinkedHashMap<>();
    /** The users whose object the test read ({@link User#asObject()}), in the order last read. */
    private final java.util.Set<String> objectsRead = new java.util.LinkedHashSet<>();

    private static final ThreadLocal<MidPointConfigTester> CURRENT = new ThreadLocal<>();

    MidPointConfigTester(Deployment deployment) {
        this.deployment = deployment;
    }

    /** midPoint release line of this JVM, e.g. {@code "4.8"}. */
    public String line() {
        return deployment.api().line();
    }

    /**
     * A source system by its resource name (e.g. the HR feed): change it as its own users would, then let midPoint see
     * the change with {@link #task} or {@link #reconcile}. The same object as {@link #target}, named for reading.
     */
    public Resource source(String resource) {
        return new Resource(this, resource);
    }

    /** A target system by its resource name (e.g. the directory), to check its accounts. */
    public Resource target(String resource) {
        return new Resource(this, resource);
    }

    /** {@link #source(String)} by the generated name ({@code t.source(Resources.HR)}). */
    public Resource source(ResourceRef resource) {
        return source(resource.on(line()));
    }

    /** {@link #target(String)} by the generated name ({@code t.target(Resources.LDAP)}). */
    public Resource target(ResourceRef resource) {
        return target(resource.on(line()));
    }

    /** {@link #task(String)} by the generated name ({@code t.task(Tasks.RECONCILE_HR)}). */
    public Task task(TaskRef task) {
        return task(task.on(line()));
    }

    public MidPointConfigTester reconcile(ResourceRef resource) {
        return reconcile(resource.on(line()));
    }

    public MidPointConfigTester importAccounts(ResourceRef resource) {
        return importAccounts(resource.on(line()));
    }

    /** {@link #user(String)} by the generated name of a user the configuration holds. */
    public User user(UserRef user) {
        return user(user.on(line()));
    }

    public Actor actor(UserRef user) {
        return actor(user.on(line()));
    }

    public Certification certification(CertificationDefinitionRef definition) {
        return certification(definition.on(line()));
    }

    public Report report(ReportRef report) {
        return report(report.on(line()));
    }

    /** A task of the deployment, by name. */
    public Task task(String name) {
        return new Task(this, name);
    }

    /**
     * Reconciles the resource's default accounts as the GUI's "Reconcile" does (a single-run task, waited for); a
     * deployed reconciliation task runs with {@link #task}.
     */
    public MidPointConfigTester reconcile(String resource) {
        deployment.api().reconcile(resource);
        return this;
    }

    /** Imports the resource's default accounts as the GUI's "Import" does (a single-run task, waited for). */
    public MidPointConfigTester importAccounts(String resource) {
        deployment.api().importAccounts(resource);
        return this;
    }

    /** A user by name (it need not exist: {@link User#doesNotExist()}). */
    public User user(String name) {
        return new User(this, name);
    }

    /** midPoint's clock and the work that becomes due with time. */
    public TestClock clock() {
        return new TestClock(this);
    }

    /**
     * How often midPoint evaluated each mapping in this test so far, and how long it took, the longest first:
     * {@code t.mappingStats().print()} (see {@link MappingStats} for what the times are).
     */
    public MappingStats mappingStats() {
        return MappingStats.of(this);
    }

    /** The mails midPoint sent in this test (its mail servers are redirected to the tester's). */
    public Mails mails() {
        return Mails.of(deployment.api().mails());
    }

    /**
     * Runs the driver calls of the block in the channel, as midPoint's model sees a change that came through it: a
     * service that calls midPoint (gRPC, REST) or a GUI page. A hook or a script that looks at the channel then acts
     * as in production. E.g. {@code t.inChannel("http://midpoint.evolveum.com/xml/ns/public/model/channels-3#grpc",
     * () -> t.midpoint().modify(UserType.class, "jdoe", path, value))}.
     */
    public void inChannel(String channelUri, Runnable calls) {
        String before = com.github.wadahiro.midpoint.tester.internal.TaskChannel.use(channelUri);
        try {
            calls.run();
        } finally {
            com.github.wadahiro.midpoint.tester.internal.TaskChannel.use(before);
        }
    }

    /**
     * Runs the driver calls of the block as the caller presents them: a service that calls midPoint on a user's behalf
     * (a portal through midPoint's API) logs the user in, maybe privileged, owns the tasks for them and comes through
     * its channel; a hook, a script or an approval then sees the call as in production. E.g.
     * {@code t.callAs(Caller.user("jdoe").privileged().channel(API_CHANNEL), () -> t.midpoint().addObjectXml(xml))}.
     */
    public void callAs(com.github.wadahiro.midpoint.tester.Caller caller, Runnable calls) {
        deployment.api().callAs(caller, calls);
    }

    /** A stand-in database of the manifest ({@code databases}): the rows scripts read over JDBC, what they wrote. */
    public Database database(String name) {
        return new Database(deployment.database(name));
    }

    /**
     * midPoint's own database (its repository, and the audit log on the line's tables): to check what midPoint wrote
     * as another system reads it over JDBC, e.g. a script of another instance that queries the audit table. Read it
     * only; a row written here bypasses midPoint (its caches, its checks). The tables are the line's own (4.0:
     * {@code m_audit_event}, 4.4 on: {@code ma_audit_event}).
     */
    public Database repositoryDatabase() {
        return new Database(deployment.repositoryJdbcUrl(), deployment.repositoryUser());
    }

    /** The tester's HTTP stand-in: what it answers in this test ({@code t.httpServer().on(...).respond(...)}). */
    public HttpServer httpServer() {
        return new HttpServer();
    }

    /** The tester's SMTP server: makes it refuse mails ({@code t.mailServer().reject()}) for this test. */
    public MailServer mailServer() {
        return new MailServer(this);
    }

    /**
     * What midPoint wrote to its audit log in the test, e.g.
     * {@code t.audit().ofType("DELETE_OBJECT").execution().target("jdoe").single().outcome()}.
     */
    public Audit audit() {
        return Audit.of(deployment().api().auditRecords());
    }

    /**
     * What midPoint wrote to its audit log while the code ran, and the work it waited for, e.g.
     * {@code t.audit(() -> t.task("Delete users").run()).ofType("DELETE_OBJECT").execution().exactly(2)}: a part of
     * the test, without what came before it.
     */
    public Audit audit(Runnable during) {
        return Audit.of(deployment().api().auditRecords(during));
    }

    /**
     * What the configuration's scripts logged in this test ({@code log.info}, {@code log.error}...: INFO and up), and
     * the loggers of the manifest's {@code logs.capture}.
     */
    public Logs logs() {
        return Logs.of(com.github.wadahiro.midpoint.tester.internal.LogCapture.entries());
    }

    /**
     * The HTTP requests made to the URLs of the manifest's {@code http.redirect} in this test, in the order received:
     * a webhook a script calls, answered {@code 200} by the tester's stand-in.
     */
    public java.util.List<com.github.wadahiro.midpoint.tester.HttpRequest> httpRequests() {
        return deployment.api().httpRequests();
    }

    // ---- people, requests and approvals ----------------------------------------------------------------------

    /**
     * A user acting in the scenario: {@code t.actor("jack").request().assign("Privileged Access")}, or the approver or
     * reviewer of a decision ({@code approve(t.actor("cc-owner"))}). midPoint runs the action logged in as them.
     */
    public Actor actor(String user) {
        return new Actor(this, user);
    }

    /** The open approval work items, to narrow ({@code assignedTo}, {@code forRole}...) and decide. */
    public WorkItems workItems() {
        return new WorkItems(this, (w, c) -> true, "");
    }

    /** The approval cases (open and closed), to narrow and check. */
    public Cases cases() {
        return new Cases(this, c -> true, "");
    }

    // ---- certification, reports -------------------------------------------------------------------------------

    /** A certification definition of the deployment, by name: {@code t.certification("Role assignments").start()}. */
    public Certification certification(String definition) {
        return new Certification(this, definition);
    }

    /** A report of the deployment, by name: {@code t.report("Users by cost center").run(Map.of("costCenter", "IT"))}. */
    public Report report(String name) {
        return new Report(this, name);
    }

    // ---- refusals ---------------------------------------------------------------------------------------------

    /**
     * Runs an operation midPoint is expected to refuse (a policy violation, a missing authorization, a password the
     * policy rejects) and returns the refusal; fails when midPoint does it.
     *
     * <pre>{@code
     * Failure f = t.expectFailure(() -> t.user("ann").assign("Auditor"));
     * assertThat(f.isPolicyViolation()).isTrue();
     * }</pre>
     */
    public Failure expectFailure(Runnable operation) {
        try {
            operation.run();
        } catch (com.github.wadahiro.midpoint.tester.OperationFailed e) {
            return new Failure(e);
        }
        throw new AssertionError("Expected midPoint to refuse the operation, but it was done");
    }

    /**
     * The PoC API on the same midPoint and state, for what this API does not cover (prism objects and deltas,
     * previews, dashboards, the evaluations of a run). Changes made through it are part of the scenario.
     */
    public MidPoint midpoint() {
        return deployment.api();
    }

    // ---- package ----------------------------------------------------------------------------------------------

    /** The name of the referenced object (its stored target name, else read); null for no reference. */
    String nameOf(com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType ref) {
        if (ref == null || ref.getOid() == null) {
            return null;
        }
        if (ref.getTargetName() != null) {
            return ref.getTargetName().getOrig();
        }
        com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType object = deployment.api()
                .find(com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType.class, ref.getOid());
        return object != null ? object.getName().getOrig() : ref.getOid();
    }

    /**
     * Notes a value read through a view ({@code user.roles()}), with how to explain it (its {@link Why}, or what else
     * makes it what it is); a failed test shows the explanations ({@link MidPointConfigTestExtension}).
     */
    void read(String key, Supplier<String> explanation) {
        reads.remove(key);
        reads.put(key, explanation);
    }

    /** The scenario of the test running on this thread; null outside one. */
    static MidPointConfigTester current() {
        return CURRENT.get();
    }

    static void setCurrent(MidPointConfigTester tester) {
        if (tester == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tester);
        }
    }

    void readObject(String user) {
        objectsRead.remove(user);
        objectsRead.add(user);
    }

    /**
     * The explanations of a failure: those of the values read through the views ({@link #explanations()}), and the
     * mappings of this test that set an item of a user whose object the test read, when the item's current value
     * appears in the failure (its actual value, else its message): possibly related, as the match is by text.
     */
    String explanations(Throwable failure) {
        String views = explanations();
        String related = related(actualText(failure));
        if (related.isEmpty()) {
            return views;
        }
        return views.isEmpty() ? related : views + "\n" + related;
    }

    private static String actualText(Throwable failure) {
        if (failure instanceof org.opentest4j.AssertionFailedError
                && ((org.opentest4j.AssertionFailedError) failure).isActualDefined()) {
            return ((org.opentest4j.AssertionFailedError) failure).getActual().getStringRepresentation();
        }
        return failure.getMessage() != null ? failure.getMessage() : "";
    }

    private String related(String actual) {
        if (actual.isEmpty() || objectsRead.isEmpty()) {
            return "";
        }
        String haystack = actual.toLowerCase(java.util.Locale.ROOT);
        List<String> users = new ArrayList<>(objectsRead);
        java.util.Collections.reverse(users);
        StringBuilder sb = new StringBuilder();
        for (String user : users) {
            for (String item : Why.targets(this, user)) {
                List<String> values;
                try {
                    values = user(user).describedValues(item);
                } catch (RuntimeException | AssertionError e) {
                    // the user is gone: nothing to match
                    continue;
                }
                boolean appears = values.stream().anyMatch(v -> v != null && !v.isEmpty()
                        && java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])"
                                + java.util.regex.Pattern.quote(v.toLowerCase(java.util.Locale.ROOT))
                                + "(?![\\p{L}\\p{N}])").matcher(haystack).find());
                if (appears) {
                    sb.append("\n").append(Why.of(this, item, user, item + " of " + user));
                }
            }
        }
        if (sb.length() == 0) {
            return "";
        }
        String text = "Possibly related (the item's value appears in the failure):" + sb;
        return text.length() > 6000 ? text.substring(0, 6000) + "..." : text;
    }

    void readWhy(String key, Supplier<Why> why) {
        read(key, () -> {
            Why w = why.get();
            return w.isEmpty() ? null : w.toString();
        });
    }

    /** The explanations of the values read in this test, the last read first: at most 8, cut at 6000 characters. */
    String explanations() {
        List<Supplier<String>> newestFirst = new ArrayList<>(reads.values());
        java.util.Collections.reverse(newestFirst);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Supplier<String> explanation : newestFirst) {
            if (shown == 8) {
                break;
            }
            String text;
            try {
                text = explanation.get();
            } catch (RuntimeException | AssertionError e) {
                // the value may be gone by now (a deleted user): nothing to explain
                continue;
            }
            if (text == null || text.isEmpty()) {
                continue;
            }
            sb.append(sb.length() > 0 ? "\n" : "").append(text);
            shown++;
        }
        return sb.length() > 6000 ? sb.substring(0, 6000) + "..." : sb.toString();
    }

    Deployment deployment() {
        return deployment;
    }

    /** The runs of this test so far (also those of calls through {@link #midpoint()}). */
    List<Run> runs() {
        runs.addAll(deployment.takeRuns());
        return runs;
    }
}
