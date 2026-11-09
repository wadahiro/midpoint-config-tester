# Kotest specs with shared prefixes: an evaluation

The earlier Kotlin evaluation (branch `exp/kotlin`, `docs/kotlin-evaluation.md` there) found one thing Kotest gives
that plain Kotlin and JUnit do not: tests nested under a common prefix. It ran each root on one state, so the nested
tests continued each other. This evaluation adds what makes the nesting pay: a container's tests each start from its
prefix, through a checkpoint, so the prefix runs once and the tests stay independent.

## What was built

- **Checkpoints** in the tester's core (`Deployment.checkpoint()`, `restore`, `release`; `internal/Checkpoint`): the
  reset's snapshot (repository and audit database, target systems, mapped files, configuration index) taken in the
  middle of a test, once the background work has finished and with Quartz held, plus what a reset does not keep:
  the stand-in databases (their own pgmem snapshots), midPoint's clock (override and offset, restored also back in
  time), whether the regular task runs are held, the mails and notifications, the captured logs, the HTTP stand-in's
  requests and answers, the test's audit and schedule positions. `CheckpointScenarioTest` (JUnit) checks it.
- **One lifecycle** for JUnit and Kotest (`MidPointConfigTestLifecycle`): load, begin (reset, tester), end (network
  check), the failure explanation, checkpoints. The JUnit extension calls it; its behaviour is unchanged.
- **`midpoint-config-tester-kotest`** (`tester/kotest`, Kotlin, class files for Java 11): `MidPointConfigSpec`, a
  FreeSpec in Kotest's SingleInstance mode, sequential. A root resets to the deployment; a container takes a checkpoint
  when its first test starts and restores it after each test; `steps(...)` containers continue each other (failfast).
  Work in a container between its tests fails the next test (midPoint's activity counter compared after the restore).
- **Build support** in the parent POMs (opt-in): kotlin-maven-plugin, the stdlib, Kotest and its Kotlin libraries
  managed over midPoint's BOM, `*Spec` in Surefire's includes. `examples/kotest-scenarios` uses it like a config
  repository, on 4.0 (JDK 11) and 4.8: `CheckpointSpec` checks the semantics (prefix once; users, clock, mails and an
  open work item back for each test; nested containers; steps; the next root from the deployment) and
  `ExplanationSpec` the failure explanation (a failed matcher in a container's test gets the mappings behind the
  value; a failing spec run showed the same in Surefire's report). A plain `mvn verify` runs the specs.

## Results

| | JUnit | JUnit `@Nested` | Kotest tree |
|---|---|---|---|
| A certification flow on a 4.10 deployment: a heavy start (a synchronization and a report import), then checks after it and after a revocation past the deadline | 5 tests, 34.4 s; Maven total 39.8 to 42.0 s | 5 tests, 23.2 s; 29.9 to 30.4 s | 5 tests, 23.4 s; 28.7 to 29.3 s |
| A lifecycle flow on a 4.0 deployment: one account's source deletion, then archive and deletion tasks after moved clocks (light steps) | 8 tests, 29.5 to 31.1 s (Maven total) | not measured | 7 tests, 27.2 to 28.3 s |

The saving is the checkpoints', not Kotest's: a `@Nested` class of the JUnit extension shares its prefix the same way
(README "Nested classes"), at the same speed. Times are the test class's (the same JVM start and deployment cache in
all); two runs each, within 0.1 s for the first row. The gain is the prefix's cost times the tests that share it, less a
snapshot and a reset per test: large for heavy prefixes, small for light ones. A checkpoint costs little
(`-Dtester.timing=true`, `CheckpointSpec` on the sample deployment): a snapshot 13 ms (4.8) / 14 ms (4.0), a restore
25 ms / 28 ms, mostly pgmem's reset (10 ms) and midPoint's caches (5 to 7 ms). In the second row most of the time is
midPoint's start and the deployment's load, which no structure of the tests saves.

Readability: the tree states the flow once, in the words of the test case ("the campaign has started", "revoked and
past the deadline"), and each check is a leaf named by its case ID; the JUnit class repeated the prefix in every
method (a `start(t)` helper and the same three lines of decision and deadline). Extension functions over
`MidPointConfigTester` (`t.startCampaign()`, `t.decide(campaign, "revoke")`, `t.pastTheDeadline(campaign)`) carry the
project's vocabulary.

Probed once (not kept as specs): a spec's tests and its containers' bodies run on one thread (`main`, Kotest's spec
coroutine), also after a nested test returns, so the tester's thread-bound state holds; a `steps` container stops at
its first failure (the later steps are skipped). A checkpoint restore keeps when each cron trigger was made by
midPoint's clock (4.4 re-creates the triggers of activity tasks in the reset: `TaskRunsTest`, on 4.0, 4.4 and 4.8).
The release profile builds the module's sources jar and a javadoc jar (a pointer, the module has no Java sources).

## Limits found

- A checkpoint does not cover midPoint's in-memory state that the reset does not restore either, nor a mail recipient
  rejected by the test (`rejectMails`), nor the why-trail's reads of the prefix (a failing test explains its own
  reads).
- Kotlin values computed by the prefix are not checkpointed (right for what the prefix created; a test must not change
  them).
- A failure in a container's prefix fails the container; Surefire reports containers as test sets with "Tests run: 0".
- The guard against work between tests reports an error of the container, not of a leaf.
- Kotest 6.2.6 runs on the JUnit Platform of each line's BOM (checked here on 4.0, 4.8 and 4.10); its Kotlin libraries
  need managing over midPoint's BOM (in the parent POMs now). IntelliJ needs the Kotest plugin to run one test.

## Decision

Adopted: the Kotest module and the checkpoints are part of the tester, opt-in as the README describes ("Nested
classes", "Kotest specs"). Worth it for flows whose prefix is heavy and shared by several checks (certification,
approvals over several stages, synchronization followed by time-based tasks); JUnit stays the default, and its
`@Nested` classes share a prefix at the same speed. The opt-in lives in the parent POMs (Kotlin and Kotest managed,
`*Spec` classes run), inert for a repository that adds no Kotlin. The base class is `MidPointConfigSpec`, in line with
`@MidPointConfigTest` and `MidPointConfigTester`.
