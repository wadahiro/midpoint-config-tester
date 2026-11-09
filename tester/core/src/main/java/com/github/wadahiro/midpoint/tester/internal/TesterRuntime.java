package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.Run;
import com.github.wadahiro.midpoint.tester.TargetSystem;

/**
 * The one midPoint of this JVM: in-memory PostgreSQL, the Spring context and the harness, started on first use and
 * shared by every test class. Isolation between tests comes from {@link #snapshot()} / {@link #reset(State)}.
 *
 * System properties:
 * <ul>
 *   <li>{@code tester.extensionSchemaDir}: directory with extension XSD files (as deployed to midPoint's
 *       {@code schema} directory);</li>
 *   <li>{@code tester.workDir}: working directory, {@code target} by default (midPoint home is
 *       {@code <workDir>/midpoint-home}).</li>
 * </ul>
 */
public final class TesterRuntime {

    public static final String ADMINISTRATOR = "administrator";
    /** How long a driver call waits for the background tasks it started (system property {@code tester.taskTimeout}, seconds). */
    static final long TASK_WAIT_MILLIS = Long.getLong("tester.taskTimeout", 60) * 1000;
    private static final long RESET_WAIT_MILLIS = 10_000;

    private static TesterRuntime instance;

    private final Path workDir;
    private final PgmemServer pg;
    private final Harness harness;
    private final MidPointImpl api;
    /** Target systems whose content is not in the database (mirrors, CSV files, LDAP servers), by resource OID. */
    private final Map<String, ManagedTarget> targets = new LinkedHashMap<>();
    private final State baseline;
    /** The runs of the driver calls since {@link #takeJournal()} was called last (golden results). */
    private final List<Run> journal = new ArrayList<>();
    /** Driver calls journaled since the start, and how often {@link #api()} was handed out ({@link #activity()}). */
    private long driverCalls;
    private long apiCalls;
    /**
     * {@link #activity()} at the end of the last reset to the baseline; null when anything else was reset since, or
     * that reset failed. See {@link #resetToBaseline()}.
     */
    private long[] atBaseline;
    /** midPoint as the line initializes it ({@link #resetToInitialized()}); null until first needed. */
    private State initialized;
    private InitialImport.Outcome initialImport;

    /**
     * Database snapshot plus, at that moment, which in-memory target system served each resource OID and their content,
     * and which configuration files were registered.
     */
    public static final class State {
        final String snapshot;
        final Map<String, ManagedTarget> targets;
        final Map<ManagedTarget, Object> contents;
        final Object configIndex;
        final Object files;
        /**
         * Whether the regular runs of recurring tasks stay held after a reset to this state ({@code tasks.recurring:
         * held} of a loaded deployment); otherwise a reset releases them.
         */
        final boolean regularRunsHeld;

        State(String snapshot, Map<String, ManagedTarget> targets, Map<ManagedTarget, Object> contents,
                Object configIndex, Object files, boolean regularRunsHeld) {
            this.snapshot = snapshot;
            this.targets = targets;
            this.contents = contents;
            this.configIndex = configIndex;
            this.files = files;
            this.regularRunsHeld = regularRunsHeld;
        }
    }

    private TesterRuntime() throws Exception {
        long start = System.currentTimeMillis();
        checkForks();
        workDir = workDirOfThisJvm();
        Path home = workDir.resolve("midpoint-home");
        Files.createDirectories(home);
        System.setProperty("midpoint.home", home.toString());
        // the directories scripts' files are mapped to, and midPoint's report output directory (<home>/export)
        FileArea.get().init(workDir, home);

        long t = Timing.start();
        pg = PgmemServer.start("midpoint", workDir);
        Timing.end("startup.pgmem", t);
        t = Timing.start();
        SqlScript.run(pg.templateJdbcUrl(), pg.user(), LineSupport.SCHEMA_SCRIPTS);
        Timing.end("startup.schemaScripts", t);
        t = Timing.start();
        pg.forkTemplate();
        Timing.end("startup.fork", t);

        // the mirror connector, as a ConnId bundle in midPoint's connector scan directory
        Path connectorDir = home.resolve("connid-connectors");
        MirrorBundle.write(connectorDir);

        String extensionDir = System.getProperty("tester.extensionSchemaDir");
        Path schemaDir = extensionDir != null ? Path.of(extensionDir).toAbsolutePath() : home.resolve("schema");
        Files.createDirectories(schemaDir);
        Files.writeString(home.resolve(LineSupport.CONFIG_FILE),
                LineSupport.config(pg.jdbcUrl(), pg.user(), schemaDir.toString(), connectorDir.toString()),
                StandardCharsets.UTF_8);

        t = Timing.start();
        harness = LineSupport.startHarness();
        Timing.end("startup.harness (Spring, initSystem)", t);
        TaskSchedules.installHold(harness.quartzScheduler());
        api = new MidPointImpl(this, harness);
        harness.loginAs(ADMINISTRATOR);
        // the logging of a system configuration (the line's initial one, a deployment's) must not replace the
        // tester's: midPoint's own switch for its tests
        com.evolveum.midpoint.schema.internals.InternalsConfig.setAvoidLoggingChange(true);
        // Groovy and prism are loaded now: the JVM's clock follows mp.time() for them too (or not at all)
        String clock = TimeAgent.groovy();
        if (clock != null) {
            System.err.println("Tester: only midPoint's clock follows mp.time(): " + clock);
        }
        baseline = snapshot();
        Timing.add("startup.total", (System.currentTimeMillis() - start) * 1_000_000L);
        System.out.println("Tester: midPoint " + LineSupport.LINE + " ready in "
                + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     * With several test JVMs, golden recording, which needs every test of the line in one JVM, is refused. (Golden
     * verification works per class; the timing report of each fork covers its own classes only.)
     */
    private static void checkForks() {
        if (Integer.getInteger("tester.forks", 1) <= 1) {
            return;
        }
        List<String> refused = new ArrayList<>();
        if ("record".equalsIgnoreCase(System.getProperty("golden.mode", "").strip())) {
            refused.add("golden.mode=record");
        }
        if (!refused.isEmpty()) {
            throw new IllegalStateException(String.join(", ", refused) + " needs every test of the line in one JVM:"
                    + " run it with -Dtester.forks=1");
        }
    }

    /** {@code tester.workDir} ({@code target} by default): shared by the test forks (the cache is there). */
    static Path baseWorkDir() {
        return Path.of(System.getProperty("tester.workDir", "target")).toAbsolutePath();
    }

    /**
     * The working directory of this JVM: {@link #baseWorkDir()}, or {@code <base>/fork-<n>} when the tests run in
     * several JVMs ({@code tester.forks} above 1 and {@code tester.fork}, Surefire's fork number): each fork has its
     * own midPoint home, files, CSV files and reports.
     */
    public static Path workDirOfThisJvm() {
        String fork = System.getProperty("tester.fork", "");
        if (Integer.getInteger("tester.forks", 1) > 1 && !fork.isEmpty() && !fork.startsWith("$")) {
            return baseWorkDir().resolve("fork-" + fork);
        }
        return baseWorkDir();
    }

    /** Whether the runtime of this JVM has been started (by {@link #get()}). */
    public static synchronized boolean isStarted() {
        return instance != null;
    }

    public static synchronized TesterRuntime get() {
        if (instance == null) {
            try {
                instance = new TesterRuntime();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot start midPoint " + LineSupport.LINE + " for the tester", e);
            }
        }
        return instance;
    }

    /** Called for every driver call's run, also one that failed. */
    void journal(Run run) {
        synchronized (journal) {
            journal.add(run);
            driverCalls++;
        }
    }

    /** The runs since the last call, in the order of the driver calls; starts a new journal. */
    public List<Run> takeJournal() {
        synchronized (journal) {
            List<Run> runs = new ArrayList<>(journal);
            journal.clear();
            return runs;
        }
    }

    /** The working directory ({@code tester.workDir}, {@code target} by default). */
    public Path workDir() {
        return workDir;
    }

    /**
     * The objects of the line's initial object files as {@code {type element, OID, name}} (e.g. {@code role}), read as
     * plain XML from where the line's initial import takes them.
     */
    public static List<String[]> initialObjectsOfTheLine() {
        return InitialImport.objectsOfTheLine();
    }

    public MidPoint api() {
        synchronized (journal) {
            apiCalls++;
        }
        return api;
    }

    public State snapshot() {
        return snapshot(false);
    }

    /**
     * A snapshot; with {@code regularRunsHeld}, a reset to it keeps the regular runs of recurring tasks held
     * ({@link TaskSchedules#holdRegularRuns}) instead of releasing them.
     */
    State snapshot(boolean regularRunsHeld) {
        long t = Timing.start();
        Map<ManagedTarget, Object> contents = new IdentityHashMap<>();
        targets.values().forEach(target -> contents.put(target, target.capture()));
        Timing.end("snapshot.targets", t);
        long p = Timing.start();
        String snapshot = pg.snapshot();
        Timing.end("snapshot.pg", p);
        p = Timing.start();
        State state = new State(snapshot, new LinkedHashMap<>(targets), contents, ConfigIndex.get().capture(),
                FileArea.get().capture(), regularRunsHeld);
        Timing.end("snapshot.files", p);
        Timing.end("snapshot.total", t);
        return state;
    }

    /**
     * Database back to the snapshot, then midPoint's in-memory state, the target systems and the index of the
     * configuration files to match it.
     */
    public void reset(State state) {
        atBaseline = null;
        long total = Timing.start();
        long t = Timing.start();
        // background work must not run into the restored database (nor into the next test's runs)
        List<String> pending = TaskSchedules.awaitBackgroundWork(harness, RESET_WAIT_MILLIS);
        Timing.end("reset.awaitBackgroundWork", t);
        if (!pending.isEmpty()) {
            Timing.count("reset.stopBackgroundWork");
            System.err.println("Tester: suspending background work still running at the reset: " + pending);
            harness.stopBackgroundWork();
        }
        // no task may start before the database, Quartz, the files and the targets match the snapshot (TaskSchedules)
        org.quartz.Scheduler scheduler = harness.quartzScheduler();
        TaskSchedules.hold(scheduler);
        try {
            // a task Quartz started after the wait, or one the suspension did not stop yet, would keep a transaction
            // open in the database being reset (pgmem fails the reset after 5 s)
            t = Timing.start();
            awaitJobs();
            Timing.end("reset.awaitJobs", t);
            t = Timing.start();
            pg.reset(state.snapshot);
            Timing.end("reset.pg", t);
            t = Timing.start();
            harness.resetInternals();
            Timing.end("reset.internals", t);
            t = Timing.start();
            TaskSchedules.rearm(scheduler, api::lastRunStart);
            Timing.end("reset.rearm", t);
            t = Timing.start();
            // the clock is back to the real time: Quartz's schedule again from the release on, unless the state holds
            // the regular runs (set before the release: a never-run task's trigger fires at once)
            TaskSchedules.releaseRegularRuns();
            if (state.regularRunsHeld) {
                TaskSchedules.holdRegularRuns();
            }
            ConfigIndex.get().restore(state.configIndex);
            FileArea.get().restore(state.files);
            // targets registered after the snapshot are emptied and forgotten, the others get their content back
            targets.values().stream().filter(target -> !state.contents.containsKey(target))
                    .forEach(target -> target.restore(null));
            targets.clear();
            targets.putAll(state.targets);
            targets.values().forEach(target -> target.restore(state.contents.get(target)));
            Timing.end("reset.filesAndTargets", t);
        } finally {
            t = Timing.start();
            TaskSchedules.release(scheduler);
            Timing.end("reset.release", t);
        }
        t = Timing.start();
        login(ADMINISTRATOR);
        Timing.end("reset.login", t);
        Timing.end("reset.total", total);
    }

    /**
     * Replaces the database by a dump of another JVM ({@link PgDump}, the deployment cache) with the reset's machinery:
     * background work awaited, Quartz held, the dump copied in, midPoint's caches and Quartz's jobs synchronized with
     * the restored rows, then {@code inWindow} (the targets' and files' content, the values that differ in this JVM),
     * still with Quartz held; the regular runs of recurring tasks held or released as {@code regularRunsHeld} says.
     */
    void restoreDatabase(Path dump, boolean regularRunsHeld, Runnable inWindow) throws java.io.IOException {
        atBaseline = null;
        List<String> pending = TaskSchedules.awaitBackgroundWork(harness, RESET_WAIT_MILLIS);
        if (!pending.isEmpty()) {
            System.err.println("Tester: suspending background work still running at the restore: " + pending);
            harness.stopBackgroundWork();
        }
        org.quartz.Scheduler scheduler = harness.quartzScheduler();
        TaskSchedules.hold(scheduler);
        try {
            awaitJobs();
            // the node of this JVM, not the one of the JVM that wrote the dump (midPoint's task manager stops its
            // scheduler when its own node record is gone)
            List<com.evolveum.midpoint.prism.PrismObject<com.evolveum.midpoint.xml.ns._public.common.common_3.NodeType>>
                    nodes = api.stored(com.evolveum.midpoint.xml.ns._public.common.common_3.NodeType.class);
            long t = Timing.start();
            PgDump.restore(pg.jdbcUrl(), pg.user(), dump);
            Timing.end("cache.restore.pg", t);
            api.replaceStored(com.evolveum.midpoint.xml.ns._public.common.common_3.NodeType.class, nodes);
            harness.resetInternals();
            login(ADMINISTRATOR);
            inWindow.run();
            harness.resetInternals();
            TaskSchedules.rearm(scheduler, api::lastRunStart);
            TaskSchedules.releaseRegularRuns();
            if (regularRunsHeld) {
                TaskSchedules.holdRegularRuns();
            }
        } finally {
            TaskSchedules.release(scheduler);
        }
        login(ADMINISTRATOR);
    }

    /** Writes the database's data to {@code dir} ({@link PgDump}); midPoint must be idle. */
    void dumpDatabase(Path dir) throws java.io.IOException {
        org.quartz.Scheduler scheduler = harness.quartzScheduler();
        TaskSchedules.hold(scheduler);
        try {
            awaitJobs();
            PgDump.dump(pg.jdbcUrl(), pg.user(), dir);
        } finally {
            TaskSchedules.release(scheduler);
        }
    }

    /** The target systems registered now, by resource OID. */
    Map<String, ManagedTarget> targets() {
        return java.util.Collections.unmodifiableMap(targets);
    }

    /**
     * Waits (up to the reset's wait) until no Quartz job executes; the scheduler is held, so none starts. A job left
     * (one the suspension did not stop) keeps a transaction open in the database being reset.
     */
    private void awaitJobs() {
        long deadline = System.nanoTime() + RESET_WAIT_MILLIS * 1_000_000L;
        try {
            org.quartz.Scheduler scheduler = harness.quartzScheduler();
            while (!scheduler.getCurrentlyExecutingJobs().isEmpty() && System.nanoTime() - deadline < 0) {
                Thread.sleep(20);
                Timing.count("reset.awaitJobs.sleep20");
            }
            if (!scheduler.getCurrentlyExecutingJobs().isEmpty()) {
                System.err.println("Tester: Quartz jobs still executing at the reset: "
                        + scheduler.getCurrentlyExecutingJobs().size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (org.quartz.SchedulerException e) {
            throw new IllegalStateException("Cannot list the executing Quartz jobs", e);
        }
    }

    /**
     * {@link #reset(State)} to the state after startup. Skipped when nothing can have changed since the last reset to
     * the baseline (the next class's reset right after the previous class's one): no driver call, no access to the
     * API, no task thread or task run started, none running, no Quartz job executing. Otherwise (or when unsure) the
     * full reset.
     */
    public void resetToBaseline() {
        if (atBaseline != null && java.util.Arrays.equals(atBaseline, activity())) {
            Timing.count("reset.toBaselineSkipped");
            return;
        }
        reset(baseline);
        atBaseline = activity();
    }

    /**
     * Resets to midPoint as the line initializes it on its first start, before the post-initial import: the startup
     * baseline, then midPoint's initial import of the line's initial objects ({@link InitialImport}), with the mail
     * servers redirected. Made on first use in this JVM, then a snapshot; the recurring tasks among the initial objects
     * (cleanup, validity and trigger scanners) stay held after a reset to it, until the caller releases them. The PoC
     * API keeps the startup baseline ({@link #resetToBaseline()}).
     */
    public synchronized void resetToInitialized() {
        if (initialized != null) {
            reset(initialized);
            return;
        }
        resetToBaseline();
        long t = Timing.start();
        org.quartz.Scheduler scheduler = harness.quartzScheduler();
        TaskSchedules.hold(scheduler);
        try {
            initialImport = InitialImport.run(harness, api.drivers(), api::redirectMail);
            // as at midPoint's first start: the scheduler starts after the post-initial import
            TaskSchedules.holdRegularRuns();
        } finally {
            TaskSchedules.release(scheduler);
            login(ADMINISTRATOR);
        }
        api.awaitBackgroundWork("the initial import");
        takeJournal();
        clearNotifications();
        initialized = snapshot(true);
        atBaseline = null;
        Timing.end("startup.initialImport", t);
        if (!initialImport.failed.isEmpty()) {
            System.err.println("Tester: initial objects of midPoint " + LineSupport.LINE + " that failed: "
                    + initialImport.failed);
        }
    }

    /** What the initial import did ({@link #resetToInitialized()}); null before. */
    InitialImport.Outcome initialImport() {
        return initialImport;
    }

    /** What changes when midPoint may have done something: see {@link #resetToBaseline()}. */
    private long[] activity() {
        int jobs;
        try {
            jobs = harness.quartzScheduler().getCurrentlyExecutingJobs().size();
        } catch (org.quartz.SchedulerException e) {
            return null;
        }
        synchronized (journal) {
            return new long[] { driverCalls, apiCalls, Recorder.backgroundStarts(), Recorder.runningTaskThreads(), jobs };
        }
    }

    public void release(State state) {
        if (state != null && state != baseline) {
            pg.release(state.snapshot);
        }
    }

    /**
     * Forgets the notifications and mails sent so far ({@link MidPoint#notifications()} and {@link MidPoint#mails()}
     * cover one test method).
     */
    public void clearNotifications() {
        api.drivers().clearNotifications();
        api.drivers().clearMails();
        HttpStandIn.clearIfStarted();
    }

    public void login(String username) {
        try {
            harness.loginAs(username);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot log in as " + username, e);
        }
    }

    <T extends ManagedTarget> T registerTarget(T target) {
        targets.put(target.resourceOid(), target);
        return target;
    }

    ManagedTarget findTarget(String resourceOid) {
        return targets.get(resourceOid);
    }

    /** Writes a file for the user to inspect (e.g. a resource as imported) under the working directory. */
    Path writeWorkFile(String relativePath, String content) {
        Path file = workDir.resolve(relativePath);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // diagnostics only
        }
        return file;
    }

    TargetSystem target(String resourceOid) {
        TargetSystem target = targets.get(resourceOid);
        if (target == null) {
            throw new IllegalArgumentException("No imported resource " + resourceOid + "; imported: " + targets.keySet());
        }
        return target;
    }
}
