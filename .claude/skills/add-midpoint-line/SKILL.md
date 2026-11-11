---
name: add-midpoint-line
description: Adds support for another midPoint release line (for example 4.11, an older one like 4.2, or one between two supported lines like 4.9 between 4.8 and 4.10) to the midPoint tester in tester/ of midpoint-config-tester. Use when asked to support, add or port a midPoint version, to bump a line to a new patch release, or when a new midPoint release breaks the build. Covers the API research with a probe script, choosing base-4.0 or base-4.8, the harness and LineSupport, the recorder hook and identity placement, the line drivers, SQL and baseline objects, the typed initial names, the parent POM of config repositories, fixtures variants, line-gated tests, the static-check module, and the documentation.
---

# Adding a midPoint line

The tester (`tester/`) runs a real midPoint per line and records every expression evaluation. The shared code and the
shared tests (`tester/core`) are compiled into every line module; a line module (`tester/midpoint-X.Y`) pins the
midPoint version and provides the line-specific parts: harness, Spring context, recorder hook, drivers, SQL, baseline
objects. Supporting a line means: it builds, every shared test passes (or is skipped by an assumption that says why):
the scenario tests (`configtester`, the product) and the few engine tests of the PoC API that remain (`tester`), and a
config repository can use the line through its parent POM (`parent/midpoint-X.Y`).

Work in English (code, comments, README, commit messages; Conventional Commits, e.g. `feat(tester):`, `fix(tester):`,
`docs:`), on a feature branch.

[`docs/line-anatomy.md`](../../../docs/line-anatomy.md) lists what each existing line provides, how the harnesses
differ and the behaviour axes found per line (by the PoC tests removed in phase 4; git history has them). Read it
before step 1; adding the new
line's column to it is part of the work (step 9). `docs/redesign.md` explains why the tester is built this way.

**A line between two supported ones** (4.9 between 4.8 and 4.10) is mostly the reverse of a new line at the end: the
module is a copy, and the work is re-dating what the tests and docs attribute to the next line. See
[Inserting a line](#inserting-a-line-between-two-supported-ones) before step 1.

## 0. Prerequisites

- **The released version** (the latest patch). Tags only, without `devel`/`support` tags:

  ```bash
  git ls-remote --tags --refs https://github.com/Evolveum/midpoint.git 'v4.9*' | sed 's|.*refs/tags/||' \
      | grep -E '^v[0-9]+\.[0-9]+(\.[0-9]+)?$' | sort -V | tail -1
  curl -so /dev/null -w '%{http_code}\n' \
      https://nexus.evolveum.com/nexus/repository/public/com/evolveum/midpoint/model/model-api/4.9.9/model-api-4.9.9.pom
  ```

  200 means the artifacts are published (Nexus directory URLs return the UI page, not a listing).
- **midPoint sources at release tags**: the new line and every neighbour you compare with (a support branch head is
  the next SNAPSHOT and mixes in post-release changes): `git clone --depth 1 -b v4.9.9
  https://github.com/Evolveum/midpoint.git <dir>`. Read the sources: do not guess an API from a javadoc, from memory or
  from the version number.
- **Dependencies, online, before the probe.** Create the module POM and the root POM property first (step 2, first
  bullet), then `mvn dependency:resolve -pl tester/midpoint-X.Y -am`. The probe reads prism from the
  line's `prism-api` jar in `~/.m2`; without it the prism section is empty (it prints the `dependency:get` command).
  The static module (step 8) resolves a different tree: resolve it as well. Older lines pull from more repositories.
- **JDK**: the `project.source.version` / `maven.compiler.release` / `java.version` of midPoint's root POM (the probe
  prints it; 4.0 and 4.4: 11, 4.8+: 17). A line built for Java 11 compiles with `--release 11`. Its tests run on the
  JDK that runs Maven: the JDKs midPoint supports for the line (its release notes) go into the parent's `tester.jdk`
  (a version range, checked by maven-enforcer before the tests), and the line's CI job runs on the recommended one
  (`.github/workflows/ci.yml`, one job per line). Run the line's commands below with Maven on that JDK (`JAVA_HOME`).

## 1. Research: how far is the new line from a supported one?

1. Run the probe on the new line and on its supported neighbours (both, for an inserted line), and diff:

   ```bash
   SKILL=.claude/skills/add-midpoint-line; mkdir -p target
   $SKILL/scripts/probe-api.sh <src-4.10.3> > target/probe-4.10.txt
   $SKILL/scripts/probe-api.sh <src-new> > target/probe-new.txt
   diff target/probe-4.10.txt target/probe-new.txt
   ```

   It prints, without line numbers (`--lines` adds them), every declaration the line modules call or read by
   reflection, grouped by the tester class that needs it (hooks, harness, drivers, prism, static checks), plus the
   evaluator elements, SQL scripts, test repository type, Spring contexts and initial objects. "(not in this tree)" is
   an answer too; "warning: N files" may show the wrong class (give the section a path hint in the script).
2. Compile the closest line against the new version: copy its module with the new version and build
   `-pl tester/midpoint-X.Y -am`. The compile errors are the most complete list of what differs, and cheap.

Then decide:

| The diff shows | Then |
|---|---|
| Only the patch version changes | Patch bump: change `midpointXY.version` in the root POM, refresh the SQL and baseline files of the release (step 3). Done after step 6. |
| Renamed or moved methods that `base-4.8` calls | A line under `base-4.8`: a module whose POM sets `midpoint.version`, with its own `LineSupport` adapters (like 4.10). An inserted line copies the neighbour with fewer differences (4.9: 4.10's `LineSupport`, only `LINE` changes). |
| A different mapping class, repository or service API (cases, certification, reports, scripting) | Its own line classes, in its own module under `base-4.0` (Java 11 lines) or a new base. When several lines share a shape, the shared classes go into `base-<first line>` (named after the first line it serves, like `base-4.8`), so the names stay true as more lines are added. |
| The line ships two repositories (4.4: generic and native) | Use the one midPoint recommends for new deployments of that line and state the choice in the README. |

Write the decision and the API differences down; they go into the anatomy at the end.

## 2. The line module

Copy the closest module and adapt it. Every class keeps its simple name and the package
`com.github.wadahiro.midpoint.tester.internal`: the shared code refers to them by name, so an artifact contains
exactly one line.

- `tester/midpoint-X.Y/pom.xml`: artifact `midpoint-config-tester-X.Y`. Under `base-4.8` it only sets `<midpoint.version>`;
  under `base-4.0` it imports its BOM and declares the dependencies the line's `model-intest` POM declares (see
  `midpoint-4.4/pom.xml`). Add the module to `tester/pom.xml` and the version property to the root POM.
  Every line module also sets `<tester.names.skip>false</tester.names.skip>` (the parents skip the generator of typed
  names; without it the shared scenario tests do not compile).
- **Connectors** (`ResourceOptions.realConnector()`): declare `com.evolveum.polygon:connector-csv` and
  `connector-ldap` with `runtime` scope and all transitive dependencies excluded (`*:*`), without a version where the
  line's BOM manages them (else the versions of midPoint's parent POM), plus `com.unboundid:unboundid-ldapsdk`; add
  the line's column to the "Connectors" table of `docs/line-anatomy.md` and confirm `HrLifecycleScenarioTest` (CSV
  and LDAP connectors) and `LiveSyncRealConnectorsTest`.
- **GreenMail** (the in-memory SMTP server, `SmtpServer`): it must run on the line's own mail library, never next to a
  second copy of the same packages. Check the line's `notifications-impl` mail dependency
  (`dependency:tree -Dincludes='*:*mail*'`): `jakarta.mail` packages (Angus) take GreenMail 2.x whose parent POM names
  the same Angus version, excluding `jakarta.mail:jakarta.mail-api` (in `base-4.8`); `javax.mail` packages take
  GreenMail 1.6.x, excluding its `com.sun.mail:jakarta.mail` unless it is the line's own version (4.0 runs it on 1.4.5).
  A line under `base-4.0` declares it in its own POM; `SmtpServer` comes from its base (the anatomy's "Mail" table).
- **`LineSupport`** is the whole surface the shared code calls:
  - `LINE` (`"X.Y"`, compared by `Lines.atLeast`);
  - `CONFIG_FILE` and `config(jdbcUrl, user, extensionDir, connectorDir)`: midPoint's configuration file, written to
    midPoint home. Copy the repository part from the line's own test configuration (the probe prints its `<type>`):
    4.4 needs `sqale` (its `TestSqaleRepositoryBeanConfig` ignores `native`), 4.8+ accept `native`, 4.0 names a
    `repositoryServiceFactoryClass`. 4.0 always loads `test-config.xml`; 4.4+ take `test.config.file`;
  - `SCHEMA_SCRIPTS`, `BASELINE`: classpath resources (step 3);
  - `startHarness()`, `drivers(harness)`, `serializeRealValue(value)` (one-line JSON of a bean for the normalized text;
    `PrismContext.get()` where it exists, the harness's prism context on 4.0);
  - for `base-4.8` lines also the API adapters its sources call: `outputDefinition` (4.8 `toMutable()`, 4.9+
    `mutator()`), `mappingTargetName` (4.8 `getMappingQName()`, 4.9+ `getTargetItemName()`) and
    `clearRepositoryCaches` (see step 4).
- **`Harness<XY>`** implements `Harness` and extends the line's `AbstractModelIntegrationTest` with
  `@ContextConfiguration(locations = "classpath:tester/ctx-tester.xml")`. Start it as model-intest does (4.0:
  `initSystemConditional()`; 4.4+: `@PostConstruct initSystem` with `MidpointTestContextWithTask`), call
  `modelService.postInit`, enable notifications and register the `RecordingTransport`, hold a `SmtpServer`, register the
  `TaskThreadListener`, import the baseline. The anatomy's harness table shows how each line does each method.
- **`tester/ctx-tester.xml`**: the imports of the line's `ctx-model-intest-test-main.xml` (the probe prints them,
  without duplicates), plus `ctx-cases.xml` (4.8+; on 4.4 and 4.0 `workflow-impl` holds the case engine),
  `ctx-workflow.xml`, `ctx-certification.xml`, `ctx-report.xml` (4.4+; the probe says which exist), plus the
  `RecorderInstaller` bean.

## 3. SQL and baseline objects

- `src/main/resources/tester/sql/`: the release's PostgreSQL scripts (`config/sql/native/postgres*.sql` 4.8+; 4.4
  `config/sql/native-new/postgres-new*.sql`, 4.0 `config/sql/postgresql-4.0-all.sql`).
- **pgmem is PostgreSQL 18.** Since PostgreSQL 16 a column of an inherited table may be generated only if the parent's
  column is generated too. The native scripts of 4.8+ are fine; older ones fail (`objectType` / `containerType` /
  `referenceType` generated only in the concrete tables, `m_assignment` inheriting `m_container`). Apply the changes
  4.8's `postgres.sql` makes (the probe's "generated columns" section shows both forms), mark each change in the file
  (4.4's script marks them `clockwork:`) and explain it in the header. midPoint never writes these columns of the
  abstract tables, so it is unaffected.
- `src/main/resources/tester/baseline/`: copies of the initial objects midPoint's services need: 021, 023-025 (case
  archetypes), 041, 042 (Approver and Reviewer roles), and where the line has task archetypes 507, 511, 520, 521, 530,
  531. They are in `config/initial-objects` (from 4.8 in `archetype/` and `role/` subdirectories) up to 4.8 and in
  `repo/system-init/src/main/resources/initial-objects` from 4.9; the probe lists them. List them in
  `LineSupport.BASELINE`. A line without some of them leaves them out and says why (4.0 has no task archetypes). 4.0's
  parser cannot read an object that starts with a comment (the license header): `Harness40` strips it before the
  import.
- Refreshing a copied module's files from the release sources (`$SRC`, `$M` = `tester/midpoint-X.Y/src/main/resources/tester`):

  ```bash
  for f in $M/sql/*.sql; do cp "$SRC/config/sql/native/$(basename "$f")" "$f"; done
  for f in $M/baseline/*.xml; do
      cp "$(find "$SRC/repo/system-init/src/main/resources/initial-objects" "$SRC/config/initial-objects" \
          -name "$(basename "$f")" 2>/dev/null | head -1)" "$f"
  done
  git diff --stat $M
  ```

## 4. Recorder hook and identity placement

The recorder must see every mapping evaluation with its variables, sources, condition and output, and its owner and
position in the owner. The expression hook (`EvaluatorHook`, JDK proxies of the factories in
`ExpressionFactory.evaluatorFactoriesMap` / `defaultEvaluatorFactory`) and the script hook (wrapping
`ScriptExpressionFactory.evaluatorMap`) are installed by `RecorderInstaller.afterSingletonsInstantiated`, once the
evaluator factories have registered themselves.

1. **Mapping hook.** `RecorderInstaller` (a `BeanFactoryPostProcessor`) changes the class of a bean definition; Spring
   bean overriding is off, so never redefine a bean.
   - Where `MappingImpl` has a package-private constructor (4.4+), replace `mappingFactory` with
     `RecordingMappingFactory`, whose `createMappingBuilder()` returns a builder whose `build()` returns
     `RecordingMappingImpl`. That class must be in midPoint's package `com.evolveum.midpoint.model.common.mapping`
     (the constructor and `variables`, `sources`, `mappingConfigItem` are package-private). Check that every data
     mapping goes through `createMappingBuilder()` (also `ModelBeans.mappingFactory`; `RuntimeTest` asserts both).
   - Where it cannot be subclassed (4.0: private constructor, final builder), replace the model's `mappingEvaluator`
     with `RecordingMappingEvaluator` and read the mapping by reflection (`variables`, `sources`, `outputPath`); compute
     the condition result from the condition triple and the masks as `MappingImpl` does.
   - The `Recorder<XY>` fills a `Record`: mapping name, target, variables, sources, condition old/new (4.4+:
     `AbstractMappingImpl.conditionResultOld/New` by reflection), the output triple copied at the end, the error,
     owner and `elementPath`.
2. **Identity placement** (`Record.elementPath`, element names with 1-based positions among same-named siblings):
   - With `ConfigurationItemOrigin` (4.8+): the owner is `ConfigurationItemOrigin.InObject.getOriginatingPrismObject()`;
     the position is the place of the `MappingType` value in the owner as loaded; for a copy, from the origin path
     (followed as far as it goes, then the mapping below it by ID and name) or the copy's container IDs (4.8 copies
     whole resource object types, 4.10 single attribute definitions). `HrLifecycleScenarioTest.whyShowsTheMappingsBehindAValue`
     checks a template mapping and an outbound by owner and name; the placement of every kind of mapping was pinned by
     `MappingPlacementTest` (removed in phase 4, in git history: run it from there on a new line if `why()` misplaces
     a mapping). Fill the line's column of the table "Mapping placement" in docs/line-anatomy.md.
   - Without it (4.0, 4.4): `MappingPlacement` (line-neutral): the owner is the mapping's origin object; the position is
     the value's own place when it is still part of that object (templates, roles), else the place of the only value
     in the origin object that is prism-equivalent to the copy (resource object types, constructions). Two equivalent
     values give no place; the index then falls back to the name and the canonical form.
   - Nested expressions and non-mapping expressions are located by their canonical form (`EvaluatorFingerprint`,
     package `...midpoint.scan` in `tester/core`): check that the line's prism writes evaluator elements in the form
     `ConfigScanner` reads (the serializer in `RecorderInstaller` serializes a **copy**, `CloneUtil.clone`, of the
     element; serializing the original changes its XNode on 4.0).
3. **Script hook.** `ScriptHook48` / `ScriptHook40` recognize a hook's script by the description midPoint gives it
   (`hook '<name>'`, `scripting hook in system configuration`; the probe prints them). Report scripts run straight on
   the `ScriptExpressionFactory` (`ReportServiceImpl.evaluateScript`); the report service sets `objectVariableMode` on
   the bean, which the fingerprint leaves out.
4. **Reset.** `Harness.resetInternals` drops what no longer matches the restored database: every midPoint cache
   (`CacheDispatcher.dispatchInvalidation`), the repository's own maps (native: `SqaleRepoContext.clearCaches()`; old
   repository: `ExtItemDictionary.initialize()`), Quartz jobs (`TaskManager.synchronizeTasks`), 4.0's provisioning
   `ResourceCache`. A new line may add a map that `initialize` does not clear (4.9+: `ExtItemCache.nameToExtItem`,
   cleared in `LineSupport.clearRepositoryCaches`). Symptoms: order-dependent failures after a reset, such as
   "Expected primary identification" or a shadow missing `icfs:name`; `ResetTest` fails on a cache that survives.

Every reflective access above is a line-specific contract: the probe prints the field or method; check each one on
the new line.

## 5. Line drivers

The drivers whose midPoint API differs are behind `LineDrivers` (`Drivers<XY>`: notifications, `isAuthorized`,
`validateValue` / `generateValue`, ad-hoc scripts, bulk actions) and the service classes `ApprovalsImpl`,
`CertificationImpl`, `ReportsImpl`, `DashboardsImpl`, `TimeImpl`, plus `TaskThreadListener` and `RecordingTransport`.
The anatomy's table shows which midPoint API each line uses. On a new line check, from the probe:

- work item completion and delegation (`CaseService` on 4.8+, `WorkflowService` on 4.4 and 4.0), the approval preview
  (`partialProcessing.approvals=PROCESS`, `ModelContext.getHookPreviewResults`);
- certification (`AccessCertificationService` as the GUI uses it, or `CertificationManager`); whether stage opening
  runs in a task (4.9+) — the driver waits for background work in any case;
- report export (classic export activity submitted through `ModelInteractionService.submit` and found by
  `ReportTestUtil.findOutputFile`, or `ReportManager.runReport`), dashboard `createWidgetData` arity;
- scanners (activities, or tasks with the scanner handler URIs on 4.0), `Clock.overrideDuration` / `resetOverride`;
- `TaskListener` signatures, `TaskManager.getNextRunStartTime`, `suspendTasks`, `switchToBackground`;
- the notification transport registration (`TransportService` on 4.8+, `NotificationManager` on 4.4 and 4.0);
- mail: the mail transport class and where it reads its servers (`MailMessageTransport` from
  `messageTransportConfiguration`, or `MailTransport` from `notificationConfiguration`): `MailRedirect` rewrites both
  places; a new place needs a line there and in its test. `MailNotificationTest` pins the per-line behaviour;
- account import (`importAccountsRequest()` 4.8+, else a shadow search and `importFromResource`), `testResource` arity.

A feature the line does not have is a driver that throws `UnsupportedOperationException` with the reason (4.0
reports), and the tests that need it are skipped by an assumption (step 6).

## 6. Shared tests on the new line

Run them: `mvn test -pl tester/midpoint-X.Y -am` (about 35 s for a line once resolved, 6 s of it
midPoint's start).
`RuntimeTest` checks that the line matches the release the module is built against and that the recorder is wired.
Each failure is one of:

- **A tester bug**: fix it in the shared code (`tester/core`) or the line classes, never by weakening the test.
- **A fixture the line cannot read** (a newer format): add a variant with the same relative path under
  `tester/core/src/test/resources/fixtures-<line>/`. The variant of the oldest such directory not older than the
  running line wins (`Fixtures.of(mp, file)`, `Fixtures.dir(mp, name)`), so `fixtures-4.4/` serves 4.4 and 4.0.
- **A line difference**: read the midPoint code, then pin both behaviours in the test with `Lines.atLeast(mp, "X.Y")`
  (equality with `mp.line()` only for a behaviour of that single line) and a comment naming the midPoint class and
  method. Decide per place, never per line: a line can side with the older lines at one place and with the newer ones
  at the next.
- **A feature the line lacks**: skip with `assumeTrue(..., "<why, citing the source>")`.

Every gate needs a decision for the new line, also those whose test passes; the anatomy's axes table lists them with
what each line does:

```bash
grep -rn 'Lines.atLeast\|mp.line()\|assumeTrue' tester/core/src/test/java
```

Then build every other line too (shared code changed): `mvn test -pl tester/midpoint-4.0,...`.

**Typed initial names.** `InitialNamesTest` fails when the line's initial objects or user schema differ from
`configtester.names.midpoint`: write the classes again from the initial objects of every supported line (the jars are
in `~/.m2` once the line modules have been built) and check the diff:

```bash
M=~/.m2/repository/com/evolveum/midpoint
python3 -I .claude/skills/add-midpoint-line/scripts/initial-names.py \
    tester/core/src/main/java/com/github/wadahiro/midpoint/configtester/names/midpoint \
    4.0=$M/gui/admin-gui/4.0.3/admin-gui-4.0.3-classes.jar 4.4=$M/gui/admin-gui/4.4.11/admin-gui-4.4.11-classes.jar \
    4.8=$M/repo/system-init/4.8.12/system-init-4.8.12.jar ... X.Y=$M/repo/system-init/<v>/system-init-<v>.jar
```

A name that only differs by a typo or reads badly as a constant goes into the script's `SKIP` or `CONSTANTS`; a user
item the line adds or drops gets its `Lines` in `UserItems`.

**Parent of config repositories.** Add `parent/midpoint-X.Y/pom.xml` (a copy of the nearest line's: the line's
version property, its dependency management, the versions its tester POM pins over midPoint's BOM, the argLine or
its `tester.jdk`) and the module to `parent/pom.xml`; give the line a job in `.github/workflows/ci.yml`. Check it with a config repository on it, e.g. the example
`examples/hr-ldap` with its parent switched to the new line (not committed).

## 7. Cross-line check

The tests of the new line passing (step 6) are the check that the configuration's behaviour holds: they assert the
use cases' requirements. Golden results (recording every evaluation to compare lines) were removed: on an upgrade the
configuration changes too, so most differences were noise.

## 8. Static checks

The static checks run without a runtime, one module per line under `tester/static/` (own parent: it compiles only
`check/**` and, of `tester/core`, `Identity`, `ConfigFiles`, `ConfigScanner`, `EvaluatorFingerprint`, `MidPointLine`).

- `tester/static/midpoint-X.Y/pom.xml`: the line's BOM, model-impl, notifications-impl, report-impl, workflow-impl,
  certification-impl (the Groovy compile classpath), logback, JUnit, AssertJ. Add it to `tester/static/pom.xml` and
  resolve it online (`dependency:resolve -pl tester/static/midpoint-X.Y -am`). A JDK 11 line sets
  `maven.compiler.release` 11 (see `midpoint-4.4`); 4.0 also takes the JUnit BOM
  first, explicit logback and AssertJ versions and the tester's reporting exclusions (`midpoint-4.0`).
- `com.github.wadahiro.midpoint.tester.check.StaticLine`: `LINE`, `prismContext(extensionSchemaDir)`
  (`MidPointPrismContextFactory`), `evaluators(prismContext)` (the members of `c:expressionEvaluator`'s substitution
  group; the probe lists them from the xsd), `upgradeFindings(prismContext, object)` (`ObjectUpgradeValidator`; null
  where the line has none, before 4.4: the report then marks `UPGRADE` skipped). Copy the nearest line's class and
  change only what the probe shows (the validator's constructor and `ValidationItem`; 4.9+: a record, a no-argument
  validator).
- Run `mvn test -pl tester/static/midpoint-X.Y -am`. `StaticCheckTest` asserts per-line
  expectations (`MidPointLine.isBefore(LINE, ...)`: Groovy's syntax messages, upgrade item wording, prism's message
  names, the evaluator set): give the
  new line its branch where its results differ, and add a column to the line table of `docs/redesign.md` (step 5).

## 9. Document

- `docs/line-anatomy.md`: the new line's column in every table, a row for every new gate.
- README: the supported lines table (midPoint, artifact, JDK, Groovy, repository), the line notes table (a row per gate
  of step 6's grep, with the pinning test), the parents of Getting started, the build commands, every "4.8+" or line
  list you find (`grep -n '4\.[0-9]' README.md`).
- `docs/redesign.md`: a section per line step (findings, pinned differences, cross-line results).

## Inserting a line between two supported ones

- **Step 1**: probe both neighbours at their release tags and diff the new line against each; copy the module (and in
  step 8 the `StaticLine`) of the neighbour with fewer differences.
- **Steps 6 and 8**: every gate at the next line (`Lines.atLeast(mp, "<next>")`, `isBefore(LINE, "<next>")`,
  `assumeTrue(... "<next>")`) needs a decision from the new line's sources: the behaviour may start at the new line.
  Failing tests show some; a gate whose assertion holds on both sides passes either way and stays mis-dated unless you
  read the source. Split a set where the new line has part of it (4.9's evaluators: the association ones, not
  `filter`).
- **Everywhere**: grep for the next line's version, not only the gates: expected strings (`"not evaluable by midPoint
  4.10"`), test method names (`linesBefore410...`), comments ("new in 4.10", "4.10+", "up to 4.8"), README, anatomy, redesign:

  ```bash
  grep -rnE '4\.10|[A-Za-z]410' tester/core/src/main tester/core/src/test/java \
      tester/static tester/base-* docs README.md .github     # fixtures left out: their OIDs contain 410
  ```

- **Step 7**: both hops; keep the direct pair when it is an upgrade path users take.

## Branch and commits

- If the base branch moves while you work, rebase before writing accepted files (run keys, driver labels and the file
  format may change), then rerun steps 6 and 7.
- Commit so that every commit builds: the root POM property with the line module that uses it, the static module after
  both; tests and their accepted entries together. Finish with a build of every line, each with Maven on its
  JDK (README, JDKs).

## Pitfalls seen so far

- A JDK or Groovy mismatch fails at run time, not at compile time: Groovy 2.x needs JDK 11; Groovy 4 drops
  `groovy.util.XmlSlurper` and friends.
- Package-private and private members differ between lines (`MappingImpl`'s constructor; 4.10's
  `ReferenceSearchExpressionEvaluatorFactory`): subclasses in midPoint's package, or reflection.
- `EvaluatorHook` proxies implement only the interfaces of a factory or evaluator. A line whose code casts an evaluator
  or factory to its class breaks with them: grep the line for such casts (`instanceof [A-Za-z]+ExpressionEvaluator\b`,
  `getEvaluatorFactory(`).
- midPoint keeps static singletons: one line per JVM. A reset that misses a cache shows only in a later test.
- Tasks need an owner (cases and certification fail without one): the harness creates tasks owned by the
  administrator; the approval and certification drivers log the acting user in and make them the owner.
- 4.0 Quartz can fire a recurring task without running it after a reset; the harness does not wait for such a task.
- zsh does not word-split `$OPTS`: `mvn $OPTS` passes one argument. Use an array.
