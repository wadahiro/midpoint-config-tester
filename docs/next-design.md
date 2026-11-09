# midpoint-config-tester: zero-base redesign proposal

Scope: a design review of the PoC at `~/dev/src/github.com/wadahiro/midpoint-config-tester` (main `0db1f39`), written
against the owner's three complaints: the API is too complex for its users; tests should be operation scenarios over a
whole configuration, not per-expression checks; the configuration should be loaded as a deployment (a directory), not
file by file in every test. No code was changed.

The one-sentence verdict: **the runtime underneath the PoC is the product; the API on top of it is the recorder's
API, and the recorder should become a debugging tool.** Rebuild the surface, keep the engine.

---

## Decisions

The owner's decisions on the review. Where they differ from the review below, they win; the review text is kept as
written, for the reasoning.

1. **Target lines: the LTS lines 4.0, 4.4 and 4.8 first.** 4.9 and 4.10 come later; the design must be able to add
   them. (The review recommended 4.8+ only, open question 2.)
2. **The baseline is loaded with midPoint's post-initial import semantics, not raw.** The project points the tester
   at its `post-initial-objects` directory. midPoint's `PostInitialDataImport` (`gui/admin-gui`, present on 4.0, 4.4
   and 4.8) walks `midpoint.home/post-initial-objects` recursively, sorts the files (`DataImport.sortFiles`), imports
   each XML file with `ModelService.importObjectsFromFile` and `overwrite(true)` (`raw(false)`, full processing, on
   4.4 and 4.8), executes the files that hold a bulk action (`executeScript` or a scripting expression), stops at the
   first failure and renames the files it has done to `.done`. The tester reproduces exactly that per line, except
   that it never renames the user's files (it works on a copy) and that an import failure fails the load loudly, with
   the file and the operation result. (The review recommended a type-ordered raw import, section 3.1 and open
   question 1.)
3. **Time shifting is core and on by default** (midPoint's clock plus the JVM wall clock agent, as in the PoC). A key
   scenario is: an HR record imported with a future start date provisions nothing; the clock advances to the date;
   the scanners and triggers run; the accounts are provisioned. The API offers `advanceTo(date)` as well as
   `advance(duration)`, and one call that runs what has become due (validity scanner, trigger scanner, due recurring
   tasks). (The review made the agent opt-in, open question 4.)
4. The remaining questions follow the review's recommendations: expression-level golden stays only as an opt-in deep
   mode, later (not in phase 1); Java only, shaped to be pleasant from Kotlin; encrypted values are handled by
   overlay files and properties.

## Phase 1 as built

### Manifest

`midpoint-config-tester.yaml`: the tool's name, so a config repository shows at a glance what reads the file. Paths
are relative to the manifest; unknown keys fail the load. Reference (`internal/Manifest`):

| Key | Meaning |
|---|---|
| `config.dirs` | Directories of post-initial objects, layered: a later directory replaces the same relative path. Objects present from the start (users, orgs as in production) are one more directory. |
| `config.exclude` | Globs on the path relative to a config directory. |
| `config.properties` | `{{name}}` in the files is replaced; an unknown name fails the load with file and line. `${...}` is left alone: it is Groovy's GString syntax in `<code>` and Velocity's in templates, and midPoint itself expands nothing in objects at import (its own mechanism is `<const>` with config.xml constants, not supported yet). |
| `config.overlays."<line>"` | `dirs` whose files replace or add files on that line (exact line, e.g. `"4.0"`; quote the key), `remove` for relative paths to drop. |
| `config.schema` | Extension XSDs; must be known before midPoint starts (one schema per JVM). |
| `targets.<resource>` | `connector: mirror` (default for every resource) / `csv` / `ldap`; `initialData` (`.csv` file content for csv, `.ldif` for ldap); `namingAttribute`, `identifier`, `referentialIntegrity`, `caseInsensitiveNames`, `computed` as the PoC's `ResourceOptions`. |
| `mail.redirect` | Default true: the mail servers of the system configuration go to the tester's SMTP server (`MailRedirect`). |
| `files.map` | Directories scripts read and write, mapped to the tester's (`FileArea`). |
| `tasks.recurring` | `held` (default): the regular Quartz runs of the loosely bound recurring tasks never fire; a scenario runs them with `s.task(name).run()` or `s.time().runDue()`, and a task that never ran counts as due (in production it would have run right after the deployment). `scheduled`: Quartz fires them on the real time as in production (their first run happens during the load) until a scenario moves the clock; not covered by a test yet. Tightly bound recurring tasks (4.0's default binding) are not held. |

### Load sequence (`internal/Deployment`, once per JVM and manifest)

1. Reset the runtime to the tester's startup baseline (phase 2: to midPoint as the line initializes it, the line's
   initial objects imported, see "Phase 2 as built").
2. Copy the config directories, layered, to `<workDir>/tester-deployment/<name>/post-initial-objects`; apply the
   excludes, the line's overlay and the properties. The user's files are only read.
3. In the copy, move every `<resource>` (a root or a child of `<objects>`) to its stand-in (`MirrorResourceXml` /
   `RealResourceXml`, as `importResource` did), register the target system, load its initial data; redirect the mail
   servers; list the encrypted values left (a warning: this midPoint has no production key). So the full-processing
   import never reaches a real system.
4. Hold Quartz and run midPoint's post-initial import on the copy (below); the first failing file fails the load
   with a `DeploymentLoadException` naming the file and the errors of midPoint's operation result (the whole result in
   `load-failure.txt`), and the runtime goes back to the baseline.
5. Hold the regular runs of recurring tasks (`held`), release Quartz, wait for the background work, test the
   connection of every resource, wait again.
6. Snapshot (database, target systems, mapped files, configuration index, the hold) and write `load-report.txt`
   (import order, resources and their stand-ins, warnings). `ScenarioExtension` resets to this snapshot before every
   test method (also after a PoC test class, which resets to the startup baseline).

### Post-initial import per line (`internal/PostInitialImport`, `LineDrivers`)

Ported from `PostInitialDataImport` and `DataImport` of `gui/admin-gui` on each line (sources of v4.0.3, v4.4.11 and v4.8.12),
because that class lives in the GUI module, reads the midPoint home, renames files and only logs its failures:

- files: `listFiles` recursively, then `sortFiles`: a stable sort by the number the first three characters of the
  file name make when they are all digits, else 0 (so `10-x.xml` sorts as 0). midPoint leaves files with the same
  number in the file system's order; the tester orders each directory's entries by name, so a load is the same
  everywhere.
- `.done` files are skipped, other non-`.xml` files are skipped with a warning.
- a file that parses as one item which is an `executeScript` or a scripting expression is executed as a bulk action;
  any other file (one object, `<objects>`, a file that does not parse as one item) goes to
  `ModelService.importObjectsFromFile`.

| | 4.0 | 4.4 | 4.8 |
|---|---|---|---|
| Import options | `overwrite(true)` only: no model execution options, so `ObjectImporter` imports **raw** (`modelOptions.raw` defaults to true) | `overwrite(true)`, `raw(false)`: full processing | as 4.4 |
| Task channel | `CHANNEL_GUI_INIT_URI` | `CHANNEL_INIT_URI` | `CHANNEL_INIT_URI` |
| Bulk action files | `ScriptingService.evaluateExpression` | `ScriptingService.evaluateExpression` | `BulkActionsService.executeBulkAction`, origin `external(CHANNEL_INIT_URI)`, `withExecutionPhase()` |
| `ImportOptionsType` | `common.api_types_3` | `common_3` | `common_3` |

Deliberate deviations: the files are a prepared copy and are never renamed to `.done`; midPoint records a failed
object of a file in the operation result and goes on (`importObjectsFromFile` itself returns SUCCESS, the file is
marked done), the tester stops at the first file whose result is an error; the import runs logged in as
`administrator` (midPoint: a fake privileged principal `initAdmin`) in a task owned by it; Quartz is held during the
import (midPoint's scheduler is not running yet while its post-initial import runs).

Phase 1 started from the tester's small baseline instead of midPoint's initial objects; phase 2 imports the line's
initial objects first (below, "Phase 2 as built").

### Scenario API (`com.github.wadahiro.midpoint.configtester`)

| Type | Methods |
|---|---|
| `@MidPointScenarios("path" \| "classpath:path")` | the manifest (default: `tester.manifest`, else `midpoint-config-tester.yaml`); JUnit 5 extension `ScenarioExtension` injects `Scenario` |
| `Scenario` | `line()`, `source(name)`, `target(name)`, `task(name)`, `reconcile(resource)`, `importAccounts(resource)`, `user(name)`, `time()`, `mails()`, `midpoint()` (the PoC API) |
| `Resource` | `add(id, attributes)`, `modify(id, attribute, values...)`, `delete(id)`, `account(id)`, `accounts()`, `system()` |
| `Account` | `exists()`, `doesNotExist()`, `hasAttribute(name, values...)`, `hasNoAttribute(name)`, `memberOf(group)`, `notMemberOf(group)`, `isEnabled()`, `isDisabled()`; views `value(name)`, `values(name)`, `attributes()`, `groups()`, `status()` |
| `User` | `exists()`, `doesNotExist()`, `hasFullName(v)`, `hasItem(path, values...)`, `hasRole(name)`, `hasNoRole(name)`, `hasAccountOn(resource)`, `hasNoAccountOn(resource)`, `isEnabled()`, `isDisabled()`, `assign(role)`, `unassign(role)`, `recompute()`; values `object()` (the `UserType`), `values(path)` |
| `ScenarioTask` | `run()`, `name()` |
| `ScenarioTime` | `now()`, `today()`, `advance("P30D")`, `advance(Duration)`, `advanceTo(Instant)`, `advanceTo(LocalDate)`, `runDue()` (validity scanner, trigger scanner, due recurring tasks), `runValidityScanner()`, `runTriggerScanner()` |
| `Mails` | `to(address)`, `withSubject(text)`, `list()`, `count()`, `subjects()`, `bodies()`, `exactly(n)`, `none()`, `single()` |
| `DeploymentLoadException` | the load failed: file, midPoint's errors |

Assertion failures give expected and actual values and, for user items and account attributes, the mapping
evaluations of the test that targeted the item (name, owner, focus, task, sources old -> new, condition, output),
from the recorder: a first version of `why()`.

**Values for AssertJ.** The fluent assertions read as a chain of the tester's own verbs; a reader has to learn them.
The values are for a general assertion library instead. A user is midPoint's own `UserType` (`User.object()`, read
anew by every call), the bean the configuration's scripts see, rather than tester views named after items (an earlier
`roles()` / `accounts()` / `status()` hid whether they meant `assignment` or `roleMembershipRef`, accounts or every
`linkRef`); no dynamic subclass of `UserType` (a live, recording proxy was considered and rejected: a method it fails
to intercept would silently read an empty bean). Accounts, mails and work items keep their values (they are not
midPoint objects). The library-neutral parts are in the core: `Describe` (midPoint's values as text), `Refs` (a
reference against a typed name: by OID when the name has one, else by type and name), and the explanations of a
failure. `configtester.assertj` adapts them: `MidPointRepresentation` (registered by `ScenarioExtension` for each test)
and `MidPointConditions` (`targeting`, `refTo`); a Kotest layer would adapt the same. `ScenarioExtension`, a
`TestExecutionExceptionHandler`, appends to a failed test's `AssertionError` the mappings behind the account values it
read, and, labelled possibly related, the mappings of the test that set an item of a user whose object it read whose
current value appears (as a word, ignoring case) in the failure's actual value, else its message (the last 8 reads,
6000 characters; opentest4j's expected and actual kept for the IDE's diff; skipped when the message already ends with
the mappings, as the fluent assertions' do; `-Dscenario.whyOnFailure=false`). The fluent assertions stay as the
shorthand.

### 4.9 and 4.10

The line modules compile and run the new surface unchanged (they share `base-4.8`, so they use 4.8's post-initial
import calls), and the sample scenarios pass there, but the lines are not targeted: their post-initial semantics were
not checked beyond 4.8's. Their PoC API stays as it is.

### Open for phase 2

- (done in phase 2) midPoint's initial objects of the line before the post-initial import.
- (done in phase 2) Requests and approvals, certification, reports in the scenario API.
- (done in phase 2: `why()`) `why()` as an API; outcome snapshots per step with the upgrade check on them (phase 3).
- Initial data of mirrors (accounts in the manifest), initial CSV rows, `connector: none`.
- `<const>` and config.xml constants; one extension schema per manifest needs one JVM per manifest (surefire
  executions or a module each).
- A real customer configuration as the load's trial (the review's phase 1 deliverable).
- 4.9 / 4.10: check `PostInitialDataImport` of those lines and target them.

## Phase 2 as built

### The line's initial objects before the post-initial import (`internal/InitialImport`, `LineDrivers`)

On its first start midPoint's `InitialDataImport` imports the line's initial objects (system configuration,
administrator, Superuser, End user, Approver, Reviewer, archetypes, default security and password policies, object
collections, reports, dashboards, lookup tables, marks, the cleanup, validity and trigger tasks...), and only then
`PostInitialDataImport` imports the deployment's files. The tester now does the same before every deployment load.
Ported from `InitialDataImport` of `gui/admin-gui` (v4.0.3, v4.4.11, v4.8.12):

| | 4.0 | 4.4 | 4.8 |
|---|---|---|---|
| Shipped in | `admin-gui-4.0.3-classes.jar`, `initial-objects/` (39 files) | `admin-gui-4.4.11-classes.jar`, `initial-objects/` (76) | `system-init-4.8.12.jar`, `initial-objects/<type>/` (115) |
| How the tester gets them | the line module's build unpacks `initial-objects/**` of the classes jar into `tester/initial-objects/` of the tester artifact (`maven-dependency-plugin:unpack`); the GUI module is not put on the classpath (its `ctx-init.xml` would run midPoint's own imports) | as 4.0 | from the classpath, as midPoint (`system-init` was already a dependency) |
| Files | the files of `initial-objects` (no subdirectories) | `classpath*:/initial-objects/*` | `classpath*:/initial-objects/**/*.xml` |
| Order | `DataImport.sortFiles`: by the number of the name's first three characters, stable (the tester orders by name first) | by file name | by file name (the subdirectory does not count) |
| An object that exists | skipped | skipped (`overwrite(false)`) | skipped; a type the repository does not support is skipped too |
| Import | `executeChanges(add)` with `createIsImport()`: full processing; a report gets `ReportTypeUtil.applyDefinition` | `setIsImport().overwrite(false)`, full processing | as 4.4 |
| Channel | `CHANNEL_GUI_INIT_URI` | `CHANNEL_INIT_URI` | `CHANNEL_INIT_URI` |
| In the tester | 32 imported; the 7 Jasper reports skipped (no Jasper libraries for 4.0) | 76 | 115 |

**What overrides what.** `PostInitialDataImport` imports with `overwrite(true)` on every line (raw on 4.0, full
processing on 4.4+): a deployment's file with the OID of an initial object replaces it as a whole. A deployment's
system configuration therefore replaces the initial one entirely: what the file does not say (the initial
`adminGuiConfiguration`, `cleanupPolicy`, `logging`, `globalSecurityPolicyRef`...) is gone, exactly as in production
(`InitialObjectsScenarioTest.theDeploymentsSystemConfigurationReplacesTheInitialOne`). An initial object the
deployment does not ship stays as the line made it.

**The baseline before the post-initial import** is "midPoint as the line initializes it" plus the tester's stand-ins:
`TesterRuntime.resetToInitialized()` resets to the startup baseline of the PoC, runs the initial import with Quartz
held, holds the regular runs of the recurring tasks among the initial objects (midPoint's scheduler starts only after
the post-initial import) and snapshots the result, once per JVM; every deployment load starts from that snapshot. The
startup baseline has objects with the OIDs of initial objects (a bare system configuration, Superuser, an
administrator, and copies of the archetypes and the Approver and Reviewer roles, identical to the line's): the line's
version replaces them (imported with `overwrite(true)`), every other existing object is skipped as midPoint does. The
PoC API (`@MidPointTest`) keeps its startup baseline; nothing changed for it.

Stand-ins and deviations:

- The mail servers of the line's system configuration are redirected as a deployment's are.
- The logging configuration of a system configuration (the line's, a deployment's) is not applied: midPoint's own
  test switch `InternalsConfig.avoidLoggingChange` is set at startup, so the tester's `logback` stays (otherwise
  midPoint reconfigures logback at INFO into `midpoint.home/log`). The object is imported unchanged.
- Run logged in as the administrator (midPoint: a fake privileged principal `initAdmin`); 4.8's random initial
  password of the administrator is not generated (the tester logs users in without passwords); a PolyString
  normalizer in the system configuration's `internals` is not applied.
- A failing initial object is noted in `load-report.txt` and the import goes on, as in midPoint (these are the line's
  files, not the user's); `DeploymentLoadTest.theLinesInitialObjectsAreImportedFirst` pins that none fails.
- With `tasks.recurring: held` the initial cleanup, validity and trigger tasks are held like the deployment's: they
  are due (never run) and `s.time().runDue()` runs them. With `scheduled` they are released after the load.

The 4.4 jar was not in the local Maven repository: `admin-gui-4.4.11-classes.jar` was fetched once from Evolveum's
Nexus (`mvn dependency:get`); the build itself runs offline. The 4.0.3 one was already there.

### Requests, approvals, certification, reports, refusals

Names only; the PoC drivers (`ApprovalsImpl`, `CertificationImpl`, `ReportsImpl`) do the line's mechanics.

| Type | Methods |
|---|---|
| `Scenario` | `as(user)`, `workItems()`, `cases()`, `certification(definition)`, `report(name)`, `expectFailure(() -> ...)` |
| `Actor` | `request()` (for themself), `request(forUser)`, `name()` |
| `Request` | `assign(role)`, `unassign(role)`: submitted as the actor (the GUI's "Request access"); a role with an approval policy waits in a case |
| `WorkItems` | filters `assignedTo(user)`, `forUser(user)`, `forRole(role)`, `inStage(n)`; `list()`, `count()`, `exactly(n)`, `none()`, `single()`, `approve(actor)`, `reject(actor)` |
| `WorkItem` | `approve(actor[, comment])`, `reject(actor[, comment])`, `assignees()`, `candidates()`, `stage()`, `escalationLevel()`, `deadline()`, `caseOf()`, `hasAssignee(user)`, `atStage(n)`, `atEscalationLevel(n)`; read anew: `isOpen()`, `isApprovedBy(user)`, `isRejectedBy(user)`, `isCancelled()` |
| `Cases` / `Case` | filters `forUser`, `forRole`, `named`, `open()`, `closed()`; `Case`: `isOpen()`, `isClosed()`, `isApproved()`, `isRejected()`, `hasOutcome(o)`, `atStage(n)`, `state()`, `outcome()`, `stage()`, `user()`, `role()`, `workItems()` |
| `Certification` | `start()` (create and open stage 1, as "Create campaign" and "Start campaign"), `create()` |
| `Campaign` | `reviews()`, `nextStage()`, `closeStage()`, `remediate()`, `close()`, `isInStage(n)`, `hasState(s)`, `hasOutcome(user, role, outcome)`, `outcomeOf(user, role)`, `reviewersOf(user, role, stage)`, `cases()` |
| `Reviews` / `Review` | filters `forReviewer`, `forUser`, `forRole`, `undecided()`; `Review`: `accept(actor[, comment])`, `revoke(actor[, comment])`, `decide(actor, response, comment)`, `response()` |
| `Report` / `ReportOutput` | `run()`, `run(Map)`; `lines()`, `text()`, `columns()`, `rows()` (CSV by header; 4.8's subscription note is not a row), `column(header)`, `hasRows(n)`, `hasRowWith(header, value)`, `hasNoRowWith`, `contains(text)`. 4.0: `UnsupportedOperationException` (Jasper reports) |
| `Failure` | `message()`, `isPolicyViolation()`, `isNotAuthorized()`, `hasMessageContaining(text)`, `exception()` |
| `User` | also `setPassword(value)` (checked by the security policy's password policy) |

```java
@MidPointScenarios("classpath:scenarios/access/midpoint-config-tester.yaml")
class AccessRequestScenarioTest {
    @Test
    void privilegedAccessNeedsTheOwnerThenASecurityOfficer(Scenario s) {
        s.as("jack").request().assign("Privileged Access");
        s.user("jack").hasNoRole("Privileged Access");
        Case request = s.cases().forUser("jack").forRole("Privileged Access").single();
        request.isOpen().atStage(1);
        s.workItems().assignedTo("role-owner").single().approve(s.as("role-owner"), "ok for jack");

        request.atStage(2);                                  // a work item per Security officer
        WorkItem analyst = s.workItems().assignedTo("sec-analyst").single();
        s.workItems().assignedTo("sec-officer").single().approve(s.as("sec-officer"));
        analyst.isCancelled();                               // firstDecides
        request.isApproved();
        s.user("jack").hasRole("Privileged Access");
    }

    @Test
    void theSegregationOfDutiesRuleRefusesAuditorToAnAccountant(Scenario s) {
        Failure failure = s.expectFailure(() -> s.user("ann").assign("Auditor"));
        assertThat(failure.isPolicyViolation()).isTrue();
        s.user("ann").hasNoRole("Auditor");
    }
}
```

Not covered by a test yet: `Actor.request(forUser)`, `Request.unassign`, `Cases.named`/`closed`,
`WorkItem.escalationLevel`/`atStage`, `Campaign.nextStage` past stage 1, `close()`, `reviewersOf`, `Review.decide` with
other responses, `ReportOutput.isHtml`, `Failure.exception`; and `tasks.recurring: scheduled` with the initial tasks
released after the load.

The second sample deployment, `scenarios/access`, has no resources: a system configuration that keeps the initial
default security policy, a user template that assigns the initial End user role by name, roles with an approval
policy (role owner, then the Security officers) and an exclusion, users, a certification definition with automated
remediation, an object collection report (removed on 4.0 by its overlay) and a bulk action that recomputes the users
at the end of the import (4.0's post-initial import is raw: without it the template would not have run). Tests:
`InitialObjectsScenarioTest`, `AccessRequestScenarioTest`, `CertificationScenarioTest`, `ReportScenarioTest`, on
4.0, 4.4 and 4.8 (the report tests are skipped on 4.0).

### `why()`

`s.user("e1001").why("fullName")` and `s.target("LDAP").account(dn).why("cn")` return a `Why`: the mapping
evaluations of this test whose target is the item (for the user; for an account, for the user its shadow is linked
to), oldest first. `steps()`, `last()`, `isEmpty()`, `print()`, `toString()`; a `Why.Step` has `mapping()`,
`owner()`, `focus()`, `sources()` (`[old] -> [new]` per source), `condition()`, `output()`, `task()`, `channel()`,
`operation()`. The assertion failure messages end with the same lines (the last eight). The channel is the operation's
(the lens context's, else the task's), recorded per evaluation (`Evaluation.channel()`): 4.8's activity tasks have no
channel on the task object.

```
Mappings that set fullName of e1001 in this test:
  - 'user-fullName' of User template for e1001 (task Reconcile HR, reconciliation): givenName [] -> [John], familyName [] -> [Doe]; output [John Doe]
```

A change runs a mapping several times (later evaluations of the same operation see the new state on both sides), so
`last()` is not always the one with the change; `steps()` has them all.

### Per-line differences found

- Where the initial objects are and how they are ordered and imported: the table above. 4.0 imports its reports with
  Jasper definitions, which the tester skips.
- 4.0's post-initial import is raw: user objects in the deployment do not go through the user template. The sample
  recomputes them with a bulk action file, as a 4.0 deployment would.
- The initial recurring tasks of 4.4 and 4.8 have no `recurrence` (recurring because of their interval); the tester
  took them for due single-run tasks and waited for them. It now asks midPoint's `TaskTypeUtil.getEffectiveRecurrence`
  (4.4 and 4.8+ harness).
- The default password policy: 4.0 and 4.4 at least 5 characters, 3 unique; 4.8 8 to 14 characters with a lowercase,
  an uppercase letter and a digit.
- 4.8's report export ends with "No active subscription..." (not a row in `ReportOutput.rows()`).
- A decided certification work item stays open until its stage closes (all lines): `Reviews.undecided()`.
- An approver given as a role reference (`approverRef` to a role) makes the role's members candidates: they claim the
  work item first, and no one is mailed for it (all lines). The sample expands such groups on work item creation
  (see "Group approvers expanded" below).
- The channel of an evaluation in a 4.8 activity is only on the lens context, not on the task.
- 4.10 (not targeted, same `InitialDataImport` algorithm as 4.8): its `250-mark-*` initial objects sort before the
  archetype of marks (`701-...`), so their archetype reference fails (PARTIAL_ERROR) on a real first start too; pinned
  in `DeploymentLoadTest`. 4.9 imports all its initial objects.

### Decisions (phase 2)

1. The initial objects are taken from midPoint's own jars at build time (unpack of `initial-objects/**`), not copied
   into this repository and not put on the classpath as the GUI module (whose Spring context would run midPoint's own
   imports). For 4.4 the jar had to be fetched once.
2. "Freshly initialized" is built on the PoC's startup baseline: objects of the same OID are replaced by the line's
   version (the administrator too: the tester needs no password), everything else as midPoint (skip if present). One
   snapshot per JVM; the PoC tests keep their baseline.
3. Logging of system configurations is switched off with midPoint's `avoidLoggingChange`, not by editing the files.
4. A failing initial object is a warning in the load report, pinned to none by the tester's test; 4.0's Jasper reports
   are skipped with a reason.
5. Actors are `Actor` objects (`approve(s.as("x"))`), not strings, to keep decisions readable and free of
   two-string overloads. Per-line mechanics (work item claims, the report footer, certification stage tasks) stay in
   the drivers and the outcome objects.
6. `expectFailure` catches only what midPoint refused (`OperationFailed`), never an assertion of the scenario API.
7. Reports on 4.0 stay unsupported: a capability gap, skipped in the tests with `assumeFalse`, not a branch.

Full build (`verify`, offline, all lines): 446 tests per line, all pass (skipped 78 / 33 / 9 / 1 / 3 on 4.0 / 4.4 /
4.8 / 4.9 / 4.10), static checks pass.

### Open for phase 3

- Outcome snapshots per step and the cross-line upgrade check on them (the review's phase 3), YAML runner later.
- 4.9 / 4.10: their `InitialDataImport` (4.10's checked: the same as 4.8's) and `PostInitialDataImport` are taken as 4.8's (they share `base-4.8`; the
  sample scenarios pass there); check and target them.
- `why()` shows every evaluation of a mapping; a view of only the evaluations that changed the value would read
  better.
- Approval escalation and certification deadlines in scenarios (the time verbs are there; no sample test yet);
  delegate/forward of work items; notifications of approvals in the sample.
- From phase 1: initial data of mirrors, initial CSV rows, `connector: none`, `<const>`, one schema per manifest, a real customer
  configuration as the load's trial.

## Phase 3 as built

The owner's decisions for phase 3: the Kotlin DSL is postponed (Java stays); names in strings are replaced by typed
names generated from the deployment; the loaded deployment is cached across runs; test JVMs can run in parallel from
the cached deployment (opt-in).

### Typed names (`configtester.names`, `internal/NamesModel`, `internal/NamesCheck`)

- **Mechanism**: a main class of the tester artifact, `configtester.names.NamesGenerator <output dir> <manifest or
  directory>...`, run by exec-maven-plugin (3.0.0, offline in `~/.m2`) and added as a test source root with
  build-helper. It reads only XML and YAML (no midPoint). A downstream project needs about 30 POM lines (README); this
  repository runs it at `generate-test-sources` from each line module's own classes (`tester/pom.xml`, skipped in the
  parent POMs by `tester.names.skip`). A downstream project binds it at `generate-sources` so the IDE's "generate
  sources" runs it. Considered and rejected (an advisor's review agreed): a Maven plugin (fewer POM lines, but
  `maven-plugin-plugin`'s untested offline tail and an uninstalled reactor plugin with `-pl` without `-am`); an
  annotation processor (its inputs are not the annotated class, so incremental compilers would not re-run it; JDK 23+
  needs `-proc:full`; the Filer does not see the resource directories).
- **Where the package comes from**: the manifest, `names: { package: com.example.idm.names }`; the generator searches
  the given directories for manifests with it. One source of truth, opt-in per manifest, one package per deployment;
  two manifests with one package fail.
- **Line-independent**: the names of every line together (`*`, the files every line without an overlay gets, and each
  line of `config.overlays`, read with `Manifest.sources(line)`, the same layering the load uses, now in `Manifest`);
  a name carries its `Lines` (`ALL`, `except("4.0")`, `only("4.8")`). Shared test sources compile on every line;
  `ref.on(line)` fails a use on another line with what the configuration has.
- **Generated**: `Resources` (`ResourceRef`), `Roles` / `Orgs` / `Services` / `Archetypes` (`AssignableRef`), `Users`
  (`UserRef`, users the configuration holds), `Tasks`, `Reports`, `CertificationDefinitions`, `<Resource>Attrs`
  (`AttributeRef`: `schemaHandling` attribute refs, a stored schema's elements, the CSV connector's unique/name/password
  columns, the header of a CSV file of initial data, the attribute names of an LDIF of initial data without `objectClass`), `UserExtension` (`ItemRef`,
  `extension/<name>` of the `config.schema` types that extend `UserType`), `Names.all()`. Constants: `Dept Sales` ->
  `DEPT_SALES`, `firstName` -> `FIRST_NAME`; a collision fails the generation. Non-ASCII is written as unicode escapes.
  A file is written only when its content changes; stale generated files are deleted.
- **API**: overloads next to the string ones: `Scenario.source/target/reconcile/importAccounts(ResourceRef)`,
  `task(TaskRef)`, `user/as(UserRef)`, `certification(CertificationDefinitionRef)`, `report(ReportRef)`;
  `User.hasRole/hasNoRole/assign/unassign(AssignableRef)`, `hasAccountOn/hasNoAccountOn(ResourceRef)`,
  `hasItem/values/why(ItemRef)`; `Resource.modify(id, AttributeRef, ...)`, `add(id, row -> row.set(attr, v))`,
  `add(row -> ...)` (the account's name from the naming attribute's value: the CSV name column, `dn`);
  `Account.hasAttribute/hasNoAttribute/values/why(AttributeRef)` (an attribute of another resource fails);
  `Request.assign/unassign`, `Actor.request(UserRef)`, the `WorkItems`, `Cases`, `Reviews` filters,
  `Campaign.hasOutcome/outcomeOf`.
- **Check at load** (`NamesCheck`, after a fresh load and after a restore): every name of `<package>.Names` that exists
  on the line must be in the loaded deployment: objects by OID (and then the name) or by name, attributes in the schema
  midPoint stored in the resource (case-insensitive; the naming attribute counts; no stored schema: not checked),
  extension items in midPoint's schema. The load fails with the list (`DeploymentLoadTest
  .generatedNamesThatTheDeploymentDoesNotHaveFailTheLoad`, with a hand-written stale `Names`). The first run caught a
  real mismatch: `objectClass` from the LDIF of initial data is no attribute of the LDAP connector's schema.
- **Tests**: the phase 1-2 scenario tests use the typed names; `HrLifecycleScenarioTest.theStringsAreTheEscapeHatch`
  keeps the strings; `theSiteGoesToTheUsersExtension` (the HR sample gained a `site` column mapped to the users'
  `extension/site`; the manifest names the tester's extension schema; a schema directory with the same XSDs as the
  JVM's is accepted); `ReportScenarioTest` skips 4.0 with `Reports.USERS_BY_COST_CENTER.existsOn(line)` (the 4.0
  overlay removes the report); `NamesGeneratorTest` (identifiers, lines, collisions, incremental writing).

### Deployment cache (`internal/DeploymentCache`, `internal/PgDump`)

- **pgmem**: version 1.18.2's control protocol has `snapshot`, `reset`, `fork`, `close` (other ops answer
  `unknown_op`; `--help` has no persistence flag): snapshots live in memory only. The database is therefore dumped with
  PostgreSQL's binary `COPY` over JDBC (pgjdbc `CopyManager`; pgmem is PGlite, PostgreSQL 18.3): every plain table
  (`relkind r`, partitions included) in one repeatable-read transaction, the sequences, and a fingerprint of the
  schema (tables, columns, types). Restore: one transaction, `session_replication_role = replica`, `TRUNCATE ...
  CASCADE` of every table (a foreign key of a partitioned parent needs it), `COPY FROM`, `setval` / `RESTART`.
  Measured: 50 MB in or out in about 0.1 s.
- **Saved** (after the fresh load, the background work done, the snapshot taken): the database; the target systems'
  content (`ManagedTarget.persistentContent()`: the LDAP server's snapshot, which serializes, change log included; the
  CSV file and its sync files; the mirror's objects and its live-sync token counter); the mapped files and report
  exports (relative paths); the import order, warnings, resource OIDs and what the line's initial import did.
- **Key**: SHA-256 of the format, the line, every jar of the class path (name, size, time: midPoint, the connector
  bundles, the libraries; found by their manifests, also behind Surefire's manifest-only jar), the tester's classes
  (the jar, or every file of the classes directory), the manifest, every file of the deployment on the line (config
  dirs, excludes, overlay; the properties are in the manifest), the initial data, the extension schema directory.
- **Layout**: `<tester.cache.dir, default <tester.workDir>/tester-cache>/<deployment id>/<key>/`, written to a temporary
  directory, digests of every file, then renamed; only the newest entry of a deployment is kept.
- **Restore**: reset to the startup baseline (not to "initialized": the line's initial import is skipped), prepare the
  copy again (the stand-ins of this JVM: LDAP port, CSV path; initial data not loaded), then with the reset's machinery
  (`TesterRuntime.restoreDatabase`): background work awaited, Quartz held, the dump copied in, **this JVM's node
  record put back** (midPoint's task manager shut its scheduler down when the dump's node replaced its own; found by
  the first restore), caches cleared and Quartz synchronized (`resetInternals`), the targets' content and the files
  restored, the stand-ins' `connectorConfiguration` and the mail redirection of the system configuration written over
  the restored values (`MidPointImpl.patchStored`: the stored XML transformed, the changed top-level items replaced in
  the repository), `rearm`, the regular runs held as the manifest says. Then the names check, the snapshot every test
  starts from, `load-report.txt` with "Restored from the deployment cache".
- **Fallback**: any failure of the restore (digest mismatch, schema fingerprint, a COPY error, a resource OID that
  differs) is logged (`Tester: the cached deployment ... cannot be restored, loading it afresh: ...`), the entry is
  discarded, the runtime goes back to the baseline and the deployment is loaded afresh (and cached again). A names
  check failure is not a cache problem: it fails the load.
- **Concurrency**: an entry is complete or absent (rename); a builder takes an exclusive file lock of the deployment's
  cache directory, a JVM that finds no entry waits for the lock and checks again, so with forks one JVM builds and the
  others restore.
- **Tests** (`DeploymentCacheTest`, on a copy of the HR sample with a cache directory of its own): a restored
  deployment gives the joiner scenario's results of the fresh load (user, roles, LDAP attributes, mails) and resets
  to itself; a changed file is a new entry (the old one deleted); a damaged table file is detected and loaded afresh;
  `tester.cache=false` loads afresh every time.
- **Measured** (4.8, load time per JVM without midPoint's start): `access` 2.4 s -> 0.27 s (its fresh time includes the
  line's initial import), `hr` 0.8 s -> 0.38 s; synthetic 300 roles / 50 orgs / 1000 users with three assignments
  each: 9.2 s -> 0.54 s (4.4: 9.4 -> 0.56 s; 4.0 raw: 4.2 -> 0.46 s). The scenario tests of 4.8: 19.1 s -> 15.6 s for
  `mvn test` of the module.
- **Limits**: timestamps in the restored database are those of the cached load; the cache does not see what the key
  does not cover (an environment variable or an outside file a script reads, a connector bundle loaded from a
  directory rather than the class path); `tasks.recurring: scheduled` with the cache has no test (the dump holds
  Quartz and waits for running jobs).

### Parallel test JVMs (`-Dtester.forks=N`)

- Surefire `forkCount=${tester.forks}` (default 1), `reuseForks=true`; the fork number goes in the `argLine`
  (`-Dtester.fork=${surefire.forkNumber}`: in `systemPropertyVariables` Surefire 3.5.2 left it empty). With more than
  one fork every JVM works in `<tester.workDir>/fork-<n>/` (midPoint home, pgmem binary, CSV files, mapped files, mail
  files, `tester/` reports, the tests' own copies through `TesterRuntime.workDirOfThisJvm()`); the cache stays in
  `<tester.workDir>/tester-cache` for all. Ports were already per JVM (pgmem, LDAP and SMTP on free ports).
- A test that loaded a deployment with a copy of the JVM's extension schema set the JVM's schema to the copy (which
  `@TempDir` then deletes): a schema directory with the same files as the given one is now kept.
- Golden verification works per class in each fork; coverage and timing reports cover the fork's classes; golden
  recording, `coverage.dir` and the places corpus are refused with forks above 1 (they need every test in one JVM).
- Measured (10-core Apple laptop, 4.8, warm cache; wall time of `mvn test` for the module, peak RSS of the test JVMs and
  pgmem, average CPU): full suite 82 / 86 / 90 s with 1 / 2 / 4 forks (6.8 / 7.5 / 7.9 GB, 200 / 420 / 685 %); the
  scenario tests 17 / 20 / 26 s (2.0 / 2.9 / 5.9 GB); 48 joiner/mover/leaver scenarios (a probe, not committed) 41 /
  40 / 54 s. midPoint keeps about two cores busy per JVM and four midPoints starting together take 17-21 s each instead
  of 7 s: on this machine forks only add heat. The default stays 1.

### Decisions (phase 3)

1. Generator: exec-maven-plugin running a main class of the tester artifact (advisor consulted; reasons above).
2. The package of the generated names is in the manifest (`names.package`), not in the POM.
3. Names are line-independent with per-line availability (`Lines`), so shared tests compile on every line.
4. Typed handles carry no compile-time resource type (`AttributeRef` of HR on LDAP fails at run time, at once): generic
   resource types would make every `Resource`/`Account` signature generic for little gain.
5. The cache is on by default (fallback to a fresh load on any problem); `tester.cache=false` turns it off.
6. The database is dumped with binary `COPY` over JDBC (pgmem cannot persist); target content with Java serialization
   (the LDAP server's own snapshot serializes).
7. Volatile values (ports, paths of this JVM) are not in the key: the restore writes this JVM's values over them.
8. Forks: one working directory per fork, a shared cache with a file lock; reports that need every test refused.

Full build (`verify`, offline, all lines, forks 1): 458 tests per line, all pass (skipped 78 / 33 / 9 / 1 / 3 on 4.0 /
4.4 / 4.8 / 4.9 / 4.10), static checks pass; one run crossed midnight and failed `ActivationTest
.aMappingSeesTheAdvancedTime` (`LocalDate.now()` a day apart, an old flake of the real date changing during the test),
passing when run again. The 4.8 module with `-Dtester.forks=2`: 458 tests pass.

### Open for phase 4

- Outcome snapshots per step and the cross-line upgrade check (from phase 2).
- Typed names of the line's initial objects (End user, Superuser, the archetypes) and of standard user items.
- Compile-time resource types for attributes, if wanted.
- A parent POM for config repositories that carries the generator, the source root and the Surefire wiring.
- Forks pay only with long-waiting tests or many cores: a measurement on a CI machine.
- `tasks.recurring: scheduled` together with the cache (the dump while Quartz would fire tasks) is untested.
- A new line module needs `<tester.names.skip>false</tester.names.skip>` in its POM (the parents skip the generator;
  the add-midpoint-line skill says so now).
- From phase 1-2: initial data of mirrors, initial CSV rows, `connector: none`, `<const>`, one schema per manifest, a real customer
  configuration as the load's trial, 4.9 / 4.10 targeted.


## Phase 4 as built

The owner's goals for phase 4: a fast suite (the scenario tests are the product's tests, the PoC API stays only as
`s.midpoint()` and as the engine), a parent POM for config repositories, typed names of midPoint's own objects and
items.

### A fast suite

**Measured first** (4.8, HEAD of phase 3, warm deployment cache; surefire's time per class, midPoint's start of about
6 s included in the first class): 458 tests in 118 classes, 75.7 s; the 9 scenario classes 13.0 s (the deployment
cache test 5.8 s, the HR scenarios 4.4 s), the 109 PoC classes (4 of them internal unit tests) 62.7 s (`TaskRunsTest` 17.8 s with the start, 11 s of
it real-time waits; every other class 0.1-2.2 s). The other lines alike: 4.0 80.0 s, 4.4 78.1 s, 4.9 92.3 s, 4.10
96.9 s. Each PoC class was sorted by what it protects:

| Kind | Classes | Done |
|---|---|---|
| Engine mechanisms the scenario API relies on and no scenario test covers | `RuntimeTest` (line, baseline, recorder wiring, the reset after each method), `ResetIntegrityTest` + `ResourceRedefinitionTest` (no midPoint cache survives a reset: the snapshot every scenario starts from and the cache's restore), `TaskRunsTest` (Quartz hold: imported and added tasks run once, `runTask`, the reset runs nothing and restores the schedule, `runDueTasks` by midPoint's clock, the hold of regular runs after the clock moved, first run included: the same hold as `tasks.recurring: held`), `ClockTest` (the JVM agent of the time shift), `FilesTest` (file redirection and its agent, `files.map`), `LiveSync*Test` (live sync on the CSV file, the LDAP change log, the mirror's token), `MembershipTest` (mirror options of the manifest: referential integrity, computed memberships, case-insensitive DNs), `GoldenTest`, the internal unit tests (`MirrorResourceXml`, `MailRedirect`, `IdentityKeys`) | kept; the two reset guards merged into `ResetTest` (3 and 2 repetitions instead of 10 and 4), `TaskRunsTest` without its two real-time methods (a task every 2 s firing by itself before the clock moves, the held runs after it moved, which the remaining method covers) and a 1 s sleep; `ClockTest` took over "scripts see the advanced time" |
| Covered by a scenario test now | `HrToLdapScenarioTest` and its mirror / real-connector variants, `LdapTest` (`HrLifecycleScenarioTest` and the new `MirrorTargetScenarioTest`: the HR deployment with both resources on mirrors, from a manifest), `ApprovalTest`, `CertificationTest`, `ReportTest`, `MailNotificationTest`, `ExtensionTest` (access and HR scenarios), `RecorderTest` (`why()`) | deleted |
| PoC-only: expression places and line notes, recorder details, PoC selection and identity | the other 88 classes (list in the deletion commit `1e39281`) | deleted, with the 228 fixture files only they used |

**Reached** (full build, same machine and cache; surefire's class time, midPoint's start in brackets):

| Line | Before: tests, time | After: tests, time | After without the start | Scenario classes | Engine tests (with the start) |
|---|---|---|---|---|---|
| 4.0 | 458 (78 skipped), 80.0 s | 117 (3 skipped), 31.4 s (7.0 s) | 24.4 s | 12.5 s | 18.9 s |
| 4.4 | 458 (33 skipped), 78.1 s | 117, 33.4 s (6.6 s) | 26.8 s | 16.1 s | 17.3 s |
| 4.8 | 458 (9 skipped), 75.7 s | 117, 31.3 s (5.8 s) | 25.5 s | 14.2 s | 17.1 s |
| 4.9 | 458 (1 skipped), 92.3 s | 117, 37.7 s (6.2 s) | 31.5 s | 17.9 s | 19.7 s |
| 4.10 | 458 (3 skipped), 96.9 s | 117, 42.8 s (7.4 s) | 35.4 s | 20.8 s | 22.0 s |

The 25 s target is met on 4.0 and about met on 4.4 and 4.8; 4.9 and 4.10 are slower in midPoint itself (every class
about 25 % slower, as before). Of the 117 tests 40 are in the 10 classes of `configtester` (scenarios, load, cache, names;
`MirrorTargetScenarioTest` and `InitialNamesTest` new), 77 in the 12 engine and unit test classes of `tester`. `TaskRunsTest` still has about 2 s of real-time wait (a held
regular run must be seen held). Full build (`verify`, offline, all lines, the static checks and the example): 469 s
before, 226 s after.

### Features removed

- The coverage report (`internal.Coverage`, `coverage.md`, `-Dcoverage.dir`) and the expression-places matrix
  (`ExpressionPlaces`, `PlaceMatrix`, `-Dplaces.corpus`, `-Dplaces.testCorpus`, `docs/expression-places*.md`), with
  `CoverageTest`: QA outputs of the per-expression PoC tests (section 3.6 recommended moving them out).
  `scan/ConfigScanner` and `EvaluatorFingerprint` stay (recorder identities, static checks).
- The cross-line golden chain of the PoC tests: `accepted-<a>-<b>.txt` (each entry names the PoC test pinning the
  difference), `AcceptedDifferencesTest`, the CI jobs `tester-golden` and `tester-cross-line`, the skill's
  `golden-pair.sh` and `golden-chain.sh`. The golden machinery stays as an opt-in of the PoC API, `GoldenTest` its
  self-test.
- `StaticCheckTest.testerFixtures` (the static checks over the PoC fixtures): the static module keeps its own fixtures.

The README describes the PoC API under "The PoC API (`s.midpoint()`)"; the line notes and `docs/line-anatomy.md`
keep the differences the deleted tests found, with the commit that has the tests.

### Parent POMs for config repositories (`parent/`)

`midpoint-config-tester-parent-<line>` for 4.0, 4.4, 4.8, 4.9 and 4.10, over `midpoint-config-tester-parent-common`
(itself under the root POM). A config repository writes a parent and its coordinates:

- the tester artifact of the line (test scope) and `junit-jupiter`;
- the line's dependency management: midPoint's BOM (4.0: the JUnit BOM first), Byte Buddy, and the versions the
  tester's POM pins over the BOM (UnboundID, GreenMail, pgmem; 4.0 also the connectors, Spring, TestNG, logback,
  PostgreSQL, AssertJ). Without them the repository's class path is not the tester's: the first build of the example
  failed on UnboundID 7.0.4 (midPoint's BOM) instead of the tester's 7.0.3;
- the generator of typed names (exec-maven-plugin at `generate-sources`, on `tester.manifest.file`, default
  `midpoint-config-tester.yaml` next to the POM) and its test source root (build-helper). The common parent configures
  it in `pluginManagement`, the per-line parents declare it: the common parent has no tester on its class path, and a
  parent POM built in this reactor runs it on a manifest it does not have (the generator says so and writes nothing);
- Surefire: `forkCount ${tester.forks}`, `reuseForks`, the manifest as `tester.manifest` (the default of
  `@MidPointScenarios`), the argLine of the line (4.8+: the agent of the file redirection through
  `dependency:properties`; 4.0 and 4.4: `-Xshare:off`, the JDK 11 toolchain, `maven.compiler.release` 11);
- from the root POM: Evolveum's repository, plugin versions, the pgmem classifier profiles.

One parent per line, not one parent with profiles: a repository tests one line per module (one midPoint per JVM), a
profile would make two builds of one module, and the JDK 11 toolchain and release are per line. The internal
`midpoint-config-tester-parent` (`tester/pom.xml`, which adds `tester/core` to every line module) keeps its name.

**Example** `examples/hr-ldap`: a POM of 19 lines (the 4.8 parent with a `relativePath` into this repository), the
manifest, `post-initial-objects/` (the HR sample in the 4.8 format, without the extension item), the LDAP initial data and three
scenario tests (`JoinerMoverLeaverTest`, `FutureJoinerTest`) on the generated names `com.example.idm.names` and the
typed initial names. It is a module of the reactor (`mvn test` builds and tests it after `midpoint-config-tester-4.8`,
resolved from the reactor). The other parents were checked once with throwaway modules of the HR sample on 4.0, 4.4,
4.9 and 4.10 in an aggregator outside the repository (all pass; not part of the build).

### Typed names of midPoint's own objects and items (`configtester.names.midpoint`)

- `InitialUsers`, `InitialRoles`, `InitialArchetypes`, `InitialOrgs`, `InitialServices`, `InitialTasks`,
  `InitialReports` (existing ref types, so every verb takes them), `InitialCollections`, `InitialDashboards`,
  `InitialPolicies` (security, value and 4.9+ policy objects), `InitialMarks` (4.8+), `InitialTemplates`
  (`MidPointObjectRef`: name, OID, type, for `s.midpoint().object(...)`), 171 names; `UserItems`, 33 items of
  midPoint's user schema (`ItemRef` without namespace) for `hasItem/values/why`.
- **Where they live**: shipped in the tester, line-independent, each name with its `Lines`; not generated downstream
  (the generator runs against one configuration and cannot know other lines' initial objects), not generated at the
  tester's build (each line module sees only its own line). The classes are written by the skill's
  `scripts/initial-names.py` from the five lines' initial-objects jars (an advisor agreed): constants by
  `NamesGenerator.constant`, a few overrides (`USERS_IN_MIDPOINT`, `EXECUTE_CHANGE_TASK`, `EXECUTE_DELTAS_TASK`), 4.4's
  misspelt name of the deltas task left out, no reports on 4.0 (the tester skips its Jasper reports). A name that
  differs between lines is one constant per name (`CERTIFICATION_DECISIONS_REPORT` 4.4,
  `CERTIFICATION_WORK_ITEMS_REPORT` 4.8+).
- **Check**: `InitialNamesTest` on every line: each name of the line exists after the initial import with its OID and
  name; each initial object of these types the line ships (`TesterRuntime.initialObjectsOfTheLine()`, the files the
  initial import reads) has a name; each user item is in the line's `UserType` definition exactly on its lines (it
  confirmed `employeeType` 4.0 only and `personalNumber` 4.8+).
- The scenario tests use them for what remained strings: `InitialRoles.END_USER`, `InitialPolicies
  .DEFAULT_SECURITY_POLICY` / `DEFAULT_PASSWORD_POLICY`, `InitialUsers.ADMINISTRATOR`, `UserItems.COST_CENTER` /
  `FULL_NAME` / `DESCRIPTION`. Strings that stay: users the scenarios create (`e1001`), DNs, report parameters and
  columns, `HrLifecycleScenarioTest.theStringsAreTheEscapeHatch`.

### Decisions (phase 4)

1. Keep the scenario tests and only the engine tests no scenario covers (table above); delete the rest with their
   fixtures (git history keeps them). Replace the mirror scenario of the PoC by a scenario-level test from a manifest.
2. Golden: delete the cross-line chain, its accepted files, their test and CI jobs (the owner postponed the cross-line
   check; every entry named a deleted pinning test); keep the golden machinery with `GoldenTest` as a minimal self-test
   (decision 4 of the owner: expression-level golden as an opt-in deep mode). Advisor consulted.
3. Coverage and the place matrix: removed now, code, docs and properties (advisor consulted; section 3.6).
4. Static checks: keep the module and its own fixtures; drop the check of the tester's fixtures.
5. One parent POM per line over a common parent under the root POM; the per-line parents pin the tester's versions
   over midPoint's BOM. Advisor consulted.
6. Typed initial names shipped as curated, script-written constants with per-line availability, checked per line; not
   generated (advisor consulted).
7. The tester version of a parent is `${project.parent.version}`, not `${project.version}`: inherited properties are
   interpolated in the repository's model, whose version is its own (the example has `1.0-SNAPSHOT` to keep this
   checked).
8. The engine tests stay in the PoC API (`@MidPointTest`): they test its mechanisms directly, and rewriting them as
   scenarios would need deployments of their own (more loads, slower).

Full build (`verify`, offline, all lines, forks 1): 226 s (469 s before), every module passes: 117 tests per line
(skipped 3 on 4.0, the report tests), the static checks 9 per line, the example 3.

### Open for phase 5

- `DeploymentCacheTest.aDamagedEntryIsLoadedAfresh` takes 3 s on 4.8 (a failed restore, then a fresh load): the
  slowest scenario test.
- (escalation done: "Approval mails as built" below) Approval escalation, delegation and forwarding
  (`WorkItem.escalationLevel`) had only PoC tests (`ApprovalTest`, `TimedActionsTest`, removed): delegation and
  forwarding still have no scenario test.
- Live sync and file redirection are tested in the PoC API only (`LiveSync*Test`, `FilesTest`): scenario verbs for
  live sync tasks and `files.map` have no scenario test.
- The parents of 4.0, 4.4, 4.9 and 4.10 are not built with an example in this repository's build (checked once by
  hand); the example could get one module per line, at about 15 s of build time each.
- The PoC API's README sections still describe the deleted tests' examples; a shorter reference of `s.midpoint()`
  would read better.
- From phase 3: outcome snapshots and the cross-line upgrade check, compile-time resource types, forks on a CI
  machine, `tasks.recurring: scheduled` with the cache, initial data of mirrors, `<const>`, one schema per manifest.

## Approval mails as built

Approval notifications verified over SMTP as scenario tests (`ApprovalMailScenarioTest`), on the `scenarios/access`
sample, which a deployment of approvals with notifications would look like.

- **Deployment** (later merged into Privileged Access, see "Group approvers expanded"): a new role, Production Access:
  stage 1 the role owner with `duration` P5D and a timed action at the deadline that escalates (`replaceAssignees`,
  `escalationLevelName` deputy, `duration` P3D) to a new user `owner-deputy`, with `notifyBeforeAction` P1D; stage 2
  the Security officers with `groupExpansion` `onWorkItemCreation` and `evaluationStrategy` `firstDecides`, a second
  member `sec-analyst`. Mail addresses for the users; the new users have no cost center (the report counts those
  with one). Five `simpleWorkflowNotifier` handlers in the system configuration, one event and one recipient each:
  `workflowProcessEvent` add (submitted) and delete (approved / rejected with the deciding approver's name and
  comment, from `event.case.workItem`) to the requester; `workItemLifecycleEvent` add to the assignee;
  `workItemAllocationEvent` modify (the reminder; `event.timeBefore`, `workItem.deadline`) to the assignee;
  `workItemAllocationEvent` add filtered on `event.operationKind` escalate to the new assignee. The role name comes
  from `midpoint.resolveReferenceIfExists(event.case.targetRef)` (works as the requester and the approvers, whose End
  user and Approver roles read the role). The 4.4 format; an overlay for 4.8 with the transport as a named
  `messageTransportConfiguration/mail` (4.9 and 4.10 read the base file's legacy transport, as the HR sample).
- **Tests**: the two stages approved (the owner's mail, both officers' mails, the requester's "approved" with the
  comment, `perRecipient()` exact); a rejection (the requester's mail with the comment, no stage 2 mails); the
  deadline (`advance("P4DT1H").runDue()`: the reminder only; `advance("P1D").runDue()`: escalation level 1, the deputy
  the only assignee, a later deadline, her mail; she and an officer approve, the request completes).
- **API**: `Mails.from(address)`, `withBodyContaining(text)`, `perRecipient()` (mails per address, the same on every
  line though 4.0 and 4.4 send one SMTP mail to all recipients and 4.8+ one each); `Mail.hasBodyContaining(texts...)`;
  `WorkItem.deadline()`, `atEscalationLevel(n)`, `hasAssignee(UserRef)`. `MailsTest` covers the selections without
  midPoint.
- **Per-line behaviour**: the same events, recipients and mails on 4.0, 4.4, 4.8 (and 4.9, 4.10). Differences are
  configuration only: the transport element; 4.8+ appends its subscription notice to bodies. On every line a work item
  offered to a group to claim has no assignee and raises no mail (`OpenStageAction` notifies assignees and deputies
  only), and every new work item raises a `workItemAllocationEvent` add besides `workItemLifecycleEvent` add. 4.8's
  schema has `simpleCaseManagementNotifier`, but no notifier is registered for its type (`EventHandlerRegistry`
  throws "Unknown handler"; `SimpleCaseManagementNotifier` handles `simpleWorkflowNotifier`): the sample uses
  `simpleWorkflowNotifier` on every line (read in the sources, not run).
- **Time**: `ApprovalMailScenarioTest` 1.5 s on 4.0, 4.4 and 4.8 (0.5 s approvals, 0.9 s the deadline with two
  `runDue()`, 0.1 s the rejection), 1.8 s on 4.9 and 4.10; `MailsTest` 0 s; the other access tests unchanged. Full
  build (`verify`, offline, all lines): 222 s (226 s before), 123 tests per line, all pass (3 skipped on 4.0).

Decisions: extend `scenarios/access` instead of a second deployment (one load per JVM; advisor consulted); a new
role rather than changing Privileged Access; group members as assignees (`onWorkItemCreation`, `firstDecides`)
because midPoint mails no candidate; `simpleWorkflowNotifier` on every line; one handler per event and recipient so
the counts per recipient are exact; `perRecipient()` instead of counts per line.

## Group approvers expanded

The owner's projects always expand group approvers (`groupExpansion` `onWorkItemCreation`), so the sample does too.

- **Deployment**: one approval role. Privileged Access takes over Production Access's stage 1 settings (`duration`
  P5D, the escalation to the deputy, the reminder; the approvers still by `approverRelation` owner) and its stage 2
  (the Security officers expanded, `firstDecides`); Production Access is removed. No scenario offers a work item to a
  group to claim any more.
- **Tests**: `AccessRequestScenarioTest` asserts the expanded flow: two stage-2 work items, each assigned to one
  officer, no candidates; one approves (`isApprovedBy`), the other's item is closed without a decision
  (`isCancelled`), the case is approved; a stage-2 rejection by one officer cancels the other's item and rejects the
  case. `ApprovalMailScenarioTest` is unchanged but for the role's name (the same per-recipient counts).
- **API**: `WorkItem.isOpen()`, `isApprovedBy(user)`, `isRejectedBy(user)`, `isCancelled()` read the item anew (by
  case and id); `WorkItem.claim` and `WorkItems.offeredTo` removed (unused); `WorkItem.candidates()` kept, and shown
  in failure messages, so an unexpanded group is visible. The PoC `Approvals.claim` / `release` stay.
- **Per-line behaviour**: the same on 4.0, 4.4 and 4.8: the first decision of `firstDecides` closes the other
  members' work items with a close timestamp and no output (no outcome, no performer).
- `allMustApprove` not added: the owner's projects use `firstDecides`; it would need a second approval role.
- **Time**: `AccessRequestScenarioTest` 0.55-0.77 s per line (the approval 0.2-0.3 s, the stage-2 rejection
  0.15-0.23 s); `ApprovalMailScenarioTest` as before. Full build (`verify`, offline, all lines): 220 s (222 s before),
  123 tests per line, all pass (3 skipped on 4.0).

## Authorizations behind requests and decisions

Requests (`Actor.request()`) and decisions (`WorkItem.approve`, `reject`) already run logged in as the user, through
`ModelService` and `CaseService.completeWorkItem`, so midPoint's authorizations apply; the sample's requesters are End
users (the user template), its approvers have midPoint's initial Approver role (their users' file).
`AccessRequestScenarioTest` now checks what those roles do:

- the requester is an End user and not an Approver; the approvers are Approvers (the assignments of
  `user.object()` against `InitialRoles`, `targeting`);
- a role that is not requestable is refused to the End user (as before);
- an approver cannot decide another approver's work item, and the requester cannot decide their own request: refused
  as not authorized, the item stays open;
- without the Approver role (unassigned in the test), the role owner's decision on their own item is refused on 4.8+
  and done on 4.0 and 4.4.

**Per-line behaviour.** Before 4.8, completing a work item needs no authorization from its assignee (or their deputy,
or a candidate); `#completeAllWorkItems` lets anyone complete any. 4.8 checks `#completeWorkItem` for every decision,
which the Approver role gives for the items assigned to the user (midPoint commit `08657c345b7`, 2023-06, "Migrate case
mgmt authorizations to new style"). A deployment that takes the Approver role away from its approvers works on 4.4
and stops working on 4.8; the test shows it.

Full build (`verify`, offline, all lines): 220 s, 129 tests per line, all pass.

Not covered yet: the execution phase on its own (an operation allowed in the request phase and refused in the
execution one), the GUI authorizations (`#gui` URLs, decided by the GUI), the REST ones, and the visibility of
objects (`s.midpoint().as(user).search(...)` of the PoC API, not in the scenario API).

## What the GUI shows a user

The visibility of objects and the GUI authorizations are now in the scenario API, on `Actor`, each a call as the user
(`Access` of the PoC API, which records the run and the authorization filters):

- `search(type)`: `ModelService.searchObjects` with no query, as typed refs (`ObjectRef.of`); `canSearch(object)`:
  the search finds it; `canGet(object)`: `getObject`; `canRead(object)`: both. Named after midPoint's `#search`,
  `#get` and `#read` (the last allows the first two), since a user may have one without the other: the End user's
  `#get` of any user is such a case.
- `canOpen(actions...)`: `SecurityEnforcer.decideAccess(principal, actions)` with `#guiAll` added (and 4.0's deprecated
  `#guiAll`), as `MidPointGuiAuthorizationEvaluator` decides a page from `DescriptorLoader`'s actions. midPoint
  combines them the same way on 4.0, 4.4 and 4.8: a denial denies, else one allowed allows. `LineDrivers.decideAccess`
  holds the per-line signature (4.0 takes a `List`).
- `form(object)`: `ModelInteractionService.getEditObjectDefinition` in the request phase, the phase of the GUI's
  `WrapperContext`, on the object as the user gets it; `Form` reads `canRead`/`canModify` of the item definitions.
- On failure, the user's authorizations (from their `roleMembershipRef`, by role) are among the explanations,
  registered like the reads of the views.

The execution phase is not asked apart: in midPoint's use a change goes through both phases, and the requests and
changes of the scenario API already do; an execution-only call does not happen from the GUI.

The sample's Help desk role (`020-roles.xml`) reads the users of the IT cost center (contact items) and modifies their
telephone number; `GuiAuthorizationScenarioTest` shows it. Writing it showed that midPoint's initial End user role
(4.0, 4.4 and 4.8 alike) lets every user get any user with all items (`assignment-target-get`): a search is narrowed,
a get is not, so the Help desk's item restrictions show no more than any End user gets. The test asserts that, as a
review of a configuration would want to know it.

Pages by URL: `canOpen("/admin/users")` (one argument starting with `/`; more, or an action URI, are a page's
actions). The build writes an index per LTS line (`GuiPagesIndex`, exec-maven-plugin at `process-classes`, from the
jars `dependency:copy` puts in `target/gui`: `admin-gui` classes jar on 4.0 and 4.4, `admin-gui` and
`authentication-impl` on 4.8; `tester.gui.skip` keeps 4.9 and 4.10 without one). It reads the `@PageDescriptor`s
with Byte Buddy's `TypePool` (no class loaded: the pages need Wicket) and emulates `DescriptorLoader.loadActions`
(`matchUrlForSecurity`, else the mount URL with `/**`; 4.0's `url`; `permitAll` and login pages; `#noAccess`), and
loads the URL mapping enum of the evaluator. `GuiPages` matches as `decide()`: every matching pattern's actions
together. Writing the tests showed what the overlap does: `/admin/user/**` (`#userDetails`) matches `/admin/user`, so
a help desk given `#userDetails` opens the new user page too (the add itself needs `#add`). The 4.8 line moved to
4.8.14 for it (the GUI jar must be the tested release), which showed 4.8.14's report CSV starts with a byte order
mark.


## An authorization matrix

The spec of authorizations (the GUI's pages and midPoint's model: actions, assignments, items) as a matrix the
tester runs (`AuthzMatrixTest`, `AuthzMatrixSpec`, `AuthzMatrixReport`), decided with the owner and Fable. First
named for the GUI (`gui-authz`); renamed, as most of its rows are the model's authorizations:

- **YAML, the matrix as rules.** Personas (columns) are users the tester creates from roles, orgs and an archetype
  (only what the user template does not give already); objects are samples on both sides of what authorizations
  select (the filter itself is configuration, not spec: the sample's label says which side it is on); rows are a
  tree of checks; the expected values are a default and rules, later winning, with a note. Not CSV (no grouping,
  rules or notes), no own DSL (a grammar, its errors and an IDE to maintain), no JSON Schema (it would only help to
  write the file in an IDE: the file is checked when read, with its lines), no Excel export for now (the HTML is the
  review document).
- **No `accepted`.** First the known deviations (midPoint's End user getting any user) were cells "accepted" apart
  from the expected value. The owner found the difference hard to follow: whether a deviation is fine depends on the
  context, so the expected value is what is wanted, and a rule's note says why.
- **`auto` rows.** Every page of the GUI (from the index of the GUI's jars), the actions on an object, every item of
  its type: the spec lists what is allowed, everything else is checked as N. The rows differ between lines, so a
  rule's pattern that selects nothing on a line is a warning there.
- **objects, not targets.** In midPoint's authorizations `object` is what is acted on and `target` what an assignment
  assigns. The matrix uses the same words: `objects`, and `assignment-targets` for what assign and unassign rows
  assign (with a relation); a persona's org takes a relation, for `orgRelation`.
- **The authorizations as compiled at login.** Every cell is checked as the persona logged in through midPoint's
  own login service (`GuiProfiledPrincipalManager`, `UserProfileService` on 4.0), so inducements, relations and
  conditions count as in the GUI. The listing and the samples' check first read the roles' own authorizations
  (`roleMembershipRef`), which missed what an org induces and listed what a relation such as owner does not give;
  they read the logged-in principal's now (the bean of a compiled authorization has no getter: read by its type).
- **The GUI profile as compiled at login, not a model of the GUI.** `guiProfile()` reads the logged-in principal's
  compiled profile (`CompiledGuiProfile`, `CompiledUserProfile` on 4.0), so the merge of the system configuration's
  and the roles' `adminGuiConfiguration` is midPoint's. Rendering the GUI (WicketTester) would be closer still but
  starts the whole GUI; rebuilding the side menu would reimplement code that differs on every line. The one piece of
  the menu modeled is whether it lists a view (`inMenu`), a few lines of each line's `LeftMenuPanel` (4.0
  `PageBase`), with the source cited.
- **GUI rows from the compiled profile.** The matrix's `views`, `features` and `menu-links` rows are the personas'
  GUI profiles (the union of what any persona's has, so every persona has the same rows). A view is in the menu by
  the profile, the line's rule (`inMenu`) and the actions the menu asks for a type's views (`LeftMenuAuthzUtil`
  `viewsAuthorizationMaps`, the same on 4.0's `PageBase`), decided as a page (one allowed, none denied; the GUI asks
  whether the user has any of the actions). The menu's sections and pages are the page rows; a details page's panels
  are left out: their defaults come from the GUI module (`objectDetails` is null in the tester's profile).
- **Details panels on hold (a 4.8 spike, not kept).** The defaults are compiled at login by the GUI's
  `DefaultGuiConfigurationCompiler` (a ClassGraph scan of all of `com.evolveum.midpoint` for `@PanelInstance` and
  `@PanelType`, which loads the panels and the listed page packages: Wicket must be on the classpath). Outside
  Spring it runs with its two beans set by reflection: `postProcess` on an empty profile gives the 47 default
  details pages (not the shadow's and the resource's: their panels are added only to a profile that configures
  them; no experimental panels). As a build-time snapshot it needs a module of its own: as a plugin dependency of
  exec-maven-plugin the GUI's jar is resolved without midPoint's BOM (wrong Spring Boot). The snapshot's XML
  parses back to the same XML, but not to `equals()` values (an empty `ItemPathType` is held differently). Then at
  a page: the archetype's policy (`determineArchetypePolicy`, `mergeObjectDetailsPageConfiguration`, both outside
  the GUI) and `DetailsNavigationPanel.isMenuItemVisible` (visibility; `applicableForOperation` null or modify); no
  action URLs. Held because a snapshot is one more generated artifact per line, and 4.0/4.4 hard-code their tabs.
- **The samples on both sides of each selector, as midPoint selects them.** The spec does not repeat the filters
  (they are configuration); instead each selector of a persona's authorizations is checked against the samples:
  midPoint makes the search filter of the selector alone (`preProcessObjectFilter` with a principal that has only
  a `#read` of it: a clone on 4.8, the logged-in principal's list swapped and restored before), and the repository
  finds the samples by it, so a filter, `special: self` and `orgRelation` mean what midPoint makes of them. A
  selector of more than a type with no sample inside or outside is a warning, not an error: the samples are the
  author's choice. Clauses midPoint searches by only on some lines or repositories (`owner`) or not at all
  (`delegator`) are listed as not checked rather than guessed.
- **Assignments decided as the clockwork decides them, not executed.** `previewChanges` authorizes only the
  projector's preliminary pass (no full information), and executing changes the samples and starts approvals. So
  the drivers repeat the clockwork's order (`ClockworkRequestAuthorizer` on 4.8, `ClockworkAuthorizationHelper`
  before): `#modify` of the assignments or of all items, else `#assign`/`#unassign` with the target and the
  relation (and `#delegate`), in the request; then `#modify` of the change in the execution, as the change executor
  does. The projector's own changes (`roleMembershipRef`) are left out; a scenario test checks the decision against
  real requests (`AssignAuthorizationScenarioTest`).
- **One test of the scenario.** All cells are checked inside the test factory (one reset); the dynamic tests compare
  the results, and their failure messages are built by the matrix (expected, actual, rule, note, authorizations,
  report), not by the scenario's explanations.

What it found in midPoint's initial roles is in the README ("An authorization matrix").

## Mapping evaluation counts and times

The recorder's three mapping wrappers (4.0: the `MappingEvaluator` bean; 4.4 and 4.8: `RecordingMappingImpl`) time
midPoint's evaluation with `System.nanoTime()` around the call (the recorder's own work before and after excluded;
its recording of the expressions inside a mapping included) and pass it to the listener; `Evaluation.duration()`
gives it. In `Why`, the same evaluation repeated in a row is one line with its count. `Scenario.mappingStats()` groups
the evaluations of the test by mapping (name and owner): count, median, the total after the first, the first apart
(it is far slower: in the HR sample's first evaluations 2-300 ms against medians of 0.1-2 ms, the script's
compilation and the first search of LDAP), sorted by the total after the first. The projector evaluates a mapping 3
times per user and operation on 4.0 and 2 times on 4.4 and 4.8 in the HR sample.

---

## 1. Retrospective of the PoC

### 1.1 What is valuable and must survive

These are measured, line-ported (4.0 to 4.10) and hard to redo. They are the engine.

| Capability | Where it lives today | Why it stays |
|---|---|---|
| A real midPoint per line inside the test JVM, started once (~8 s) | `Harness40/44/48`, `tester/ctx-tester.xml`, `TesterRuntime`, `LineSupport` | The whole premise: midPoint computes, nothing is re-implemented. The 4.8/4.9/4.10 line modules are 61-79 lines each, which proves the layering works. |
| In-memory PostgreSQL with snapshot reset (~15-20 ms) | `PgmemServer`, `TesterRuntime` reset (DB + caches + Quartz hold + task schedules + targets + files + clock) | Makes "reset per test to a loaded deployment" affordable. The reset integrity work (`TaskSchedules`, `ResetIntegrityTest`) is subtle and done. |
| Mirror connector built from the resource's own stored schema and capabilities | `MirrorResourceXml`, `MirrorTargetImpl`, `MirrorBundle`, `SimulatedActivation` | Tests the resource definition **as deployed**; only `connectorRef` changes. Activation simulation, password readability and live-sync tokens behave like the real connector's configuration says. |
| Real CSV and LDAP connectors, redirected by rewriting the resource on import | `RealResourceXml`, `CsvTargetImpl`, `LdapTargetImpl` (UnboundID in-memory server with change log) | Covers what a mirror cannot (CSV quoting, DN handling, LDAP schema checks). The rewrite table in README "Real connectors" is exactly a declarative overlay already; it just has no declarative front. |
| Mail redirected to GreenMail by rewriting the system configuration's transports | `MailRedirect` | Nothing mocked between notifier and wire; `Mail` is a clean outcome object. |
| Background work handled like production | `waitForTasks`, `runTask`, Quartz hold after a clock move, `runDueTasks`, scanners as tasks, approval execution tasks awaited | A scenario test is impossible without this; it took most of the "step 3b" effort. |
| Clock shifting (midPoint `Clock` + JVM wall clock) | `TimeImpl`, `TimeAgent` | Joiner/leaver, delayed delete, certification deadlines, escalation all need "30 days later". |
| File redirection for scripts | `FileAgent`, `FileArea`, `MappedFiles` | Real configs read `/opt/midpoint/var/import/*.csv`. |
| Service drivers: approvals, certification, reports (export/import), dashboards, bulk actions, value policies, `as(user)` | `ApprovalsImpl`, `CertificationImpl`, `ReportsImpl`, `DashboardsImpl`, `AccessImpl`, `Drivers4x` | Each hides real line drift (`WorkflowService` vs `CaseService`, stage opening via task on 4.9+). This is the "line adapter" the owner would otherwise write per project. |
| Golden infrastructure: normalization (`gen:<name>` OIDs, masked secrets, folded class names), diff report, **accepted differences with reasons**, per-line fixture variants | `Normalizer`, `GoldenComparison`, `Golden`, `accepted-<a>-<b>.txt`, `Fixtures.of(mp, file)` | The upgrade check is the single most valuable feature for an integrator; the *comparison unit* is what has to change (see 3.5), not the machinery. |
| The Line notes table (README 1175-1250) and the pinned-difference tests | `tester/core/src/test`, `Lines.atLeast` | This is a behaviour catalogue of midPoint across five releases that does not exist anywhere else. It belongs to the tool's own test suite, not to the user's. |

### 1.2 What is accidental complexity, and where it leaked into the API

The public API is ~4,500 lines in 34 types. About 1,800 of those lines (`Run`, `Evaluation`, `Selection`,
`Identity`, `Location`, `Normalizer`) describe **expression evaluation records**, not **outcomes**. Every driver
returns a `Run`, so the recorder is the first thing a user meets. Concrete symptoms, all from the tool's own tests
(which are also its only documentation of usage):

1. **Tests select mapping evaluations where they should assert outcomes.** `HrToLdapScenarioTest` lines 73-80: after
   asserting the LDAP `cn`, `sn`, `ou`, `mail` and group membership (the actual outcome), the test goes on to assert
   `hire.forFocus("e1001").mapping("hr-familyName").newValues()`, `hire.select().path("correlation").element("expression")`,
   `hire.templateMapping(...)`, `hire.forFocus("e1002").in("LDAP").mapping("ldap-cn")`. The second half says nothing
   more about the configuration than the first half; it exists to exercise the recorder.
2. **Line branches in user-facing tests for differences with identical outcomes.** `HrToLdapScenarioTest` 115-123
   branches on `Lines.atLeast(mp, "4.4")` over whether a *deleted* user's construction was evaluated with a false
   condition or the account's outbounds were evaluated. The outcome (Kate and her account are gone) is the same on
   every line. `PolicyTest.contractorsNeedAnEndDate` builds an identity string with
   `(Lines.atLeast(mp, "4.8") ? "custom" : "objectState")`. `ApprovalTest.stage1Order` exists because 4.4 orders
   approvers differently. The README's Line notes table has ~75 rows; most are evaluation-order or variable-binding
   differences with the same end state. **Expression-level assertions turn every such row into a test branch.**
3. **Structural identity strings in tests.** `ApprovalTest` 67-85: `preview.run().select().within("policyRule", "name",
   RULE).within("stage", "number", "1").element("approverExpression")`, then a 200-character identity string asserted
   with `hasToString`. `CorrelationTest` declares three identity constants before its first test. An integrator reads
   `role:Privileged Access/assignment[policyRule=...]/policyRule[...]/policyActions/approval/approvalSchema/stage[number=1]/approverExpression[description=the IT cost center owner]`
   and asks what this tells them about their config. The identity grammar (README 537-568: discriminators, ordinals,
   `focusMappings=`, `targetFilter=`, `label=` cut at 40 chars) is a sophisticated internal key that became a user
   concept.
4. **Golden plumbing in test bodies.** `mp.step("hire")` exists only so golden run keys match across lines. `Run.operation()`
   labels are asserted (`"request modify user:jack [assignment]"`) because they are golden keys.
   `HrToLdapScenarioTest.runOnce` imports `internal.Recorder` to count task runs.
5. **OIDs and prism types everywhere.** `assign(Class<F> type, String focus, String target)`, `modify(UserType.class,
   "ann", ADMINISTRATIVE_STATUS, ActivationStatusType.DISABLED)`, `ObjectDelta` in `approvals().request(delta)`,
   `(ObjectDelta<UserType>) (ObjectDelta<?>) mp.change(...).delta()` casts, `ROLE = "10000000-...-c020"` constants,
   `mp.addObjectXml(user("ann", role(CRM_USER, ""), ""))` string-built XML. The user has to know prism to write a test.
6. **Configuration is loaded per class, per file, with per-call options.** `@BeforeAll` methods call
   `importObject`/`importResource(file, ResourceOptions.defaults().realConnector())` one file at a time, in an order the
   test author chooses, with `Fixtures.of(mp, file)` for per-line variants. `importObjects(dir)` exists but imports in
   file-name order, excludes resources (they need `importResource`) and does not handle references between files.
   This is precisely the owner's third complaint. The `ResetMode` choice (per method vs per class) is a consequence:
   because setup is expensive to express, tests want to share it.
7. **Debug/validation outputs presented as features.** Coverage (`coverage.md`), the expression-places matrix
   (`expression-places-<line>.md`, 3,600 lines of docs), `-Dplaces.corpus`, `golden.outcomes=all`,
   `golden.baseline=compare`. These validated that the *recorder* sees every place of the schema; they are the tool's
   own QA, not the integrator's.
8. **`MidPointImpl` is 1,207 lines** and `MidPoint` has 60+ methods across configuration, drivers, reading, services
   and escape hatches. The "A first test" in the README (lines 142-167) needs a `Run`, an `Evaluation`, `identity()`,
   `expression().variable("focus")` and a `UserType` getter to assert one family name.

In short: the PoC was built to prove "every evaluation can be captured and keyed across lines" (redesign.md,
experiments E1-E3) and it proved it. The API then grew as the lens through which that proof was verified. The owner's
users do not need the lens.

---

## 2. Users and jobs-to-be-done

| User | Job | What a test looks like for them |
|---|---|---|
| **Integrator changing an IGA configuration** (daily) | Change a mapping, a role, a policy; know before committing that hire/move/leave, approvals and deprovisioning still do the right thing | A scenario: fill the source system, run the deployed task or the operation, assert users/accounts/cases/mails. Runs in seconds locally. Reads top-down like a runbook. |
| **Reviewer** (PR review, sign-off) | Understand what a config change does without reading Groovy | The same scenarios, plus a diff of outcome snapshots: "after this change, user X also gets role Y; mail Z is no longer sent". |
| **Upgrade project** (4.8 -> 4.10) | Prove the migrated configuration behaves as before on the new line, and list what changed | The same scenarios run on A (config A) and on B (migrated config B); a report of outcome differences, each accepted with a reason or fixed. |
| **CI of a config repository** | Fail the build on regressions; no midPoint server, no DB | `mvn test` in the config repo with one dependency per line; artifacts of snapshots/diffs. |

The scenario types an IGA configuration needs, and the outcomes each asserts:

| Scenario | Drives | Asserts |
|---|---|---|
| Joiner from HR | add an HR row, run the deployed HR task (reconcile / live sync / import) | user exists with computed items, archetype, auto-assigned roles; accounts on targets with attributes, group memberships, enabled; welcome mail |
| Mover | change HR row (department, manager, name), run task | role changes, account attribute changes, old group left, new joined; manager notified |
| Leaver | delete HR row or set end date, run task, advance time, run scanners | user disabled then deleted; accounts disabled, deleted after N days; manager and IT mails |
| Role request and approval | request as user, approve/reject/escalate as approvers, advance time | case states, work item assignees per stage, assignment present only after final approval, mails to approvers and requester |
| Certification campaign | create campaign, open stages, decide as reviewers, close, remediate | reviewers per stage, revoked assignments gone, accounts deprovisioned, reminder mails after deadline |
| Password / self-service | change own password as user, policy violations | policy outcomes, password propagated to targets, mail |
| Reconciliation of drift | change the target directly, run reconcile | drift corrected or tolerated per `tolerant` settings; unmatched accounts linked/deleted per reactions |
| Deprovisioning with delayed delete | unassign, advance 10 days, 31 days | account disabled with reason, trigger set, deleted at the right day, not earlier |
| Report output | run a report with parameters | lines of the export, columns, who is included |
| Notifications | any of the above | mails: to, subject, body contains, attachments |
| Upgrade regression | all of the above on two lines | outcome snapshots equal modulo accepted differences |

Note what is absent: "the `hr-familyName` mapping returned `Doe`". That is an intermediate, reached for only when an
outcome assertion fails ("why is `sn` empty?").

---

## 3. Proposed shape

### 3.1 How a project uses it: a config directory, a manifest, one load per JVM

```
my-iga-config/
  config/                      the deployed XML set, as checked in (any sub-structure)
    system-configuration.xml
    schema/*.xsd
    function-libraries/*.xml
    archetypes/*.xml  templates/*.xml  resources/*.xml  roles/*.xml  orgs/*.xml  tasks/*.xml ...
  config-4.10/                 overlay for the migrated line (only the files that differ)
  tests/
    midpoint-tester.yaml       the manifest (below)
    initial-data/              LDIF, CSV, users as deployed in production (optional)
    src/test/java/...          scenarios
    pom.xml                    one module; profiles per line
```

The manifest declares what today is scattered across `@BeforeAll`, `ResourceOptions`, surefire properties and
convention:

```yaml
config:
  dirs: [../config]                       # the deployment
  overlays:
    "4.10": [../config-4.10]              # files here replace same-named files for that line
  extensionSchema: ../config/schema
  properties:                             # ${...} / config.properties values the XML references
    ldap.baseContext: dc=example,dc=com
  exclude: ["**/task-cleanup.xml"]        # not loaded (e.g. production-only tasks)

targets:                                  # per resource name; default is mirror
  HR:    { connector: csv,  initialData: initial-data/hr.csv }
  LDAP:  { connector: ldap, initialData: initial-data/people.ldif, referentialIntegrity: true, caseInsensitiveNames: true }
  CRM:   { connector: mirror, computed: [{ attribute: memberOf, from: groupOfNames.member }] }
  AD:    { connector: mirror }            # no real AD connector: mirror from its stored schema

mail:  { redirect: true }                 # GreenMail; false = leave transports (they fail), dummy transports always recorded
files: { map: ["/opt/midpoint/var"] }     # redirected directories (needs the agent)
tasks: { autorun: false }                 # imported recurring tasks do not fire until a scenario runs them
initialData:                              # objects imported after the config, part of the baseline
  - initial-data/users-and-orgs.xml
actors:                                   # named users with roles, for "as(...)"
  requester: { user: jdoe }
```

**Lifecycle.** The JUnit extension starts the line's midPoint once per JVM, loads the manifest, imports everything,
loads the initial data, lets startup tasks settle, takes **the baseline snapshot**, and **resets to it before every test**.
`ResetMode.PER_CLASS` goes away: there is one state, the deployment, and a test method is a scenario from that state.
Scenario-local setup (a few users, a row in HR) is written in the test, which is right: it is the scenario's data.
Projects that want heavier shared initial data put it in `initialData:`.

**Import order.** Before midPoint starts: the extension schemas (`*.xsd`, one set per JVM, as today). Then, type-ordered
by default, with reference resolution as a second pass:

1. system configuration (raw), security and value policies, lookup tables
2. function libraries
3. archetypes, object templates
4. connectors (host-type `ConnectorType`: ignored, the line's bundled ones are used), resources (rewritten per `targets:`)
5. roles, orgs, services (two passes: objects first, then inducements/assignments with `targetRef` by filter)
6. users, shadows (initial data)
7. tasks last (held, see `tasks.autorun`)

A `dependsOn` is not needed in the manifest: midPoint objects reference by OID or by filter; OIDs need no order
(references are not validated on raw import), filters are resolved by the tool after everything is in. Unresolved
references fail the load with the file and the path. Objects of a type not in the list (e.g. `ReportType`, `DashboardType`,
`AccessCertificationDefinitionType`, `MessageTemplateType`, `SequenceType`, `ObjectCollectionType`) load with step 3.

**Raw vs model import.** Recommendation: **raw import for the baseline** (what `ninja import` does: straight to the
repository), with the tool resolving filter references. Today's `importObject` is a full-processing model import, which runs
autoassign mappings and hooks against the configuration objects themselves (README 1186: it fails for a system
configuration on 4.0) and which no deployment does. Model import stays available as a driver for "an admin imports
this object" scenarios.

**Protected strings.** Deployed XML carries encrypted values (connector passwords, bind passwords, mail passwords).
Connector configuration is rewritten anyway (mirror or redirected real connector), mail credentials are removed on
redirect. Anything else encrypted (e.g. a `protectedString` in a role) needs an overlay file or a `properties:` entry.
The loader should list the encrypted values it left untouched, so the user sees what cannot be decrypted here.

### 3.2 Target systems: declarative, per resource

Everything `ResourceOptions` and `importResource` do today moves into `targets:`. Internally `MirrorResourceXml` and
`RealResourceXml` are unchanged. Rules:

- Default is **mirror** from the resource's stored schema (export the resource from production with its schema; the
  loader warns when a mirror has no stored schema and falls back to `schemaHandling`).
- `connector: csv | ldap` keeps the real connector, redirects file/host/port as today.
- `initialData:` is content present at baseline (LDIF, CSV rows, or a small YAML of accounts/groups for mirrors).
- A resource not in `targets:` is still loaded, as a mirror. A resource marked `connector: none` is loaded but
  provisioning to it fails (to test error handling), or `exclude:` it.
- `additionalConnector` stays unsupported (as today) and is reported at load.

The test then refers to a target by resource name: `ldap.account("uid=e1001,...")`, `hr.row("1001")`. No per-test code.

### 3.3 Test authoring API: a small scenario vocabulary, outcomes first

Principles: names, not OIDs or `Class<?>`; verbs that an integrator says out loud; results are plain objects with
assertion helpers; the recorder is reachable but not returned.

```java
@MidPointScenarios                                   // one annotation; manifest found by convention or by attribute
class JoinerMoverLeaverTest {

    @Test void joinerGetsDirectoryAccount(Scenario s) {
        s.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
        s.task("Reconcile HR").run();                // the deployed task; or s.resource("HR").reconcile() without one

        s.user("e1001")
            .hasFullName("John Doe")
            .hasRole("Dept Sales")
            .isEnabled();
        s.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com")
            .hasAttribute("cn", "John Doe")
            .memberOf("cn=sales,ou=groups,dc=example,dc=com")
            .isEnabled();
        s.mails().to("manager@example.com").withSubject(containing("New employee")).exactly(1);
    }
}
```

Vocabulary (complete enough to judge, not final):

| Area | Verbs | Outcome objects |
|---|---|---|
| Sources/targets | `s.source(name).add/modify/delete/row(...)`, `s.target(name).account(id)`, `.group(id)`, `.accounts()` | `Account`: `hasAttribute`, `hasNoAttribute`, `isEnabled/isDisabled`, `memberOf`, `hasPassword`, `exists/doesNotExist` |
| Tasks and time | `s.task(name).run()`, `s.resource(name).reconcile()/importAccounts()/liveSync()`, `s.time().advance("P30D")`, `s.time().runDueTasks()`, `s.time().runScanners()` | `TaskRun`: status, processed/failed counts |
| Focus changes | `s.user(name).set("costCenter", "IT")`, `.assign("Role")`, `.unassign("Role")`, `.disable()`, `.enable()`, `.delete()`, `.recompute()`; `s.as("jdoe").user("jdoe").changePassword(...)` | `User`: `hasItem(path, values)`, `hasRole`, `hasNoRole`, `hasArchetype`, `isEnabled`, `hasAccountOn(resource)`, `hasNoAccountOn`, `situationOn(resource)` |
| Requests and approvals | `s.as("jdoe").request().assign("Privileged Access")`, `s.cases().open()`, `s.workItems().assignedTo("cc-owner")`, `.approve(as: "cc-owner", comment: ...)`, `.reject`, `.delegateTo`, `.claim`; `s.case(name).cancel()` | `Case`: `isOpen/isClosed`, `stage()`, `outcome()`, `WorkItem`: `assignees`, `candidates`, `escalationLevel` |
| Certification | `s.certification("role-assignments").start()`, `.openNextStage()`, `.workItems().forReviewer("sec-officer").decide("revoke")`, `.closeStage()`, `.remediate()`, `.close()` | campaign, cases, work items |
| Reports | `s.report("users-by-cost-center").run(param: value)` | `ReportOutput`: `lines()`, `rows()` (parsed by the report's format), `columns()` |
| Mails and notifications | `s.mails()`, `s.mails().since(step)`, `s.notifications()` | `Mail` as today (it is good) |
| Files | `s.files().write(path, text)`, `s.files().read(path)` | `WrittenFile` as today |
| Failure | `s.expectFailure(() -> ...)` returns the failure with its message and policy violations | |
| Low level | `s.midpoint().bean(...)`, `s.midpoint().execute(ObjectDelta)`, `s.midpoint().object(type, name)` | escape hatch, prism types allowed here only |
| Why | `s.why().item("e1001", "familyName")`, `s.why().attribute("LDAP", dn, "cn")`, `s.why().lastRun()` | the mapping evaluations behind one value, as a readable trace; also printed automatically when an outcome assertion fails |

`hr.hire(...)`, `hr.transfer(...)` are **project-defined helpers** over `s.source("HR")` and `s.task(...)`: the tool
cannot know that "hire" means "a CSV row with these columns and a run of this task". The example in section 4 shows
a five-line `Hr` helper. The tool ships generic verbs and makes helpers trivial to write; it should not ship a fake
HR vocabulary.

**Expression inspection becomes `why()`.** The recorder stays installed (its cost is ~0.09 ms per evaluation). When
`user("e1001").hasFullName("John Doe")` fails, the message shows the value, then the template mapping and inbounds that
produced `fullName` for that user in the last run, with their sources and condition. `why()` exposes the same on demand.
The `Selection`/`Identity` API survives under `s.why().evaluations()` for the tool's own tests and for power users; it
is not in the first package a user opens.

**Steps.** `s.step("hire")` stays, with a different purpose: it names a **snapshot point** for the upgrade check (3.5)
and groups mails/evaluations. It is optional; a test without steps snapshots at its end.

**Non-Java scenario files (YAML / Gherkin).** Recommendation: **not now.** Reasons: scenarios need computed values
(dates relative to now, DNs built from names), loops over users, conditionals for optional branches, and IDE navigation
to the helper that defines "hire"; Gherkin step definitions would be the same Java the fluent API already is, plus a
grammar to maintain. The fluent API should be designed so that a scenario reads like a runbook (the example in section 4
is the bar). Revisit a YAML runner (`midpoint-tester run scenarios/*.yaml`) after the verb set has stabilized on two
or three real projects; then it is a thin interpreter over the same API, and mainly useful for reviewers who do not
open an IDE. Kotlin is attractive for named arguments (`row(firstName = "John")`) and should be supported by shape
(no overload traps), but Java stays the primary API because config repositories are Maven projects maintained by
integrators, not developers.

### 3.4 Debugging and reviewing: outcome snapshots

Every scenario step produces an **outcome snapshot**: normalized text of what the scenario touched. Default content:

- users the scenario created or changed (name, items, assignments by target name, activation, archetypes, link
  situations per resource), orgs/roles changed
- accounts on every target (naming attribute, attributes, status, memberships), by target
- cases and work items (name, stage, assignees, outcome)
- mails (to, subject, body hash or body), notifications
- report outputs (lines), files written

Normalization reuses `Normalizer` (generated OIDs by name, masked secrets, timestamps relative to the clock). The
snapshot is written to `target/tester/snapshots/<Test>.<method>/<step>.txt` on every run; `-Dsnapshot.mode=record`
checks it into `src/test/snapshots/`, `verify` diffs. This gives the reviewer a readable "what changed" for a config
PR without any assertion being written, and it is the unit of the upgrade check.

### 3.5 Cross-line upgrade check

Fits the scenario model directly:

1. Scenarios are written once against the manifest. Line B uses `overlays."4.10"` for the migrated files.
2. `mvn test -Pline-4.8 -Dsnapshot.mode=record`, then `mvn test -Pline-4.10 -Dsnapshot.mode=verify`.
3. The diff report lists outcome differences per scenario step: a user item, an account attribute, a mail subject, a
   report line, a case's assignees. **Not** evaluation counts, old-state sets or variable names.
4. `accepted-4.8-4.10.txt` keeps the format and discipline of today's file (kind | scenario | step | path | reason) at
   outcome granularity: `changed | ApprovalScenario.twoStages | request | case[Assigning Privileged Access to jack]/workItems/order  # 4.4 orders candidates first, MID-xxxx`.
5. Expression-level golden stays as an **opt-in deep mode** (`-Dsnapshot.level=evaluations`) for the tool's own
   suite and for an integrator who wants to know *why* an outcome moved. The existing `GoldenComparison`, `moved` and
   `new-state` options survive there unchanged.

What this loses: a mapping that changes its intermediate output but not the final state is no longer reported by
default. That is the point: the ~75 Line notes rows show these are mostly noise for an integrator. The honesty note
from README 1071-1076 stays: this compares two fresh databases with two configurations, not a migrated database.

### 3.6 What to drop or move out

| Item | Decision | Reason |
|---|---|---|
| Expression-places matrix (`docs/expression-places-*.md`, `ExpressionPlaces`, `PlaceMatrix`, `-Dplaces.corpus`) | Move to the tool's own QA (a report of the tool's test suite), out of the user artifact and README | It measures recorder coverage of the schema, a tool-internal property. |
| Per-place tests (`VariablesTest`, `RecorderTest`, `IdentityTest`, `MappingPlacementTest`, `CoverageTest`...) | Keep in the tool's repo as recorder tests; never present as examples | They are correct as tests of the engine. |
| `coverage.md` of user config | Replace by "which objects/resources/roles did any scenario touch" at outcome level (optional) | Expression coverage of a config is not what reviewers ask. |
| `ResetMode.PER_CLASS` | Drop | One baseline, reset per test. |
| `importObject(s)`/`importResource` in tests | Drop from the main API; keep `s.midpoint().importObject` as a driver for admin scenarios | The manifest loads the deployment. |
| `Run` returned by every driver | Drop; drivers return outcome objects or nothing; evaluations via `why()` | The main complaint. |
| Static checks (`tester/static`) | **Separate artifact and CLI** (`midpoint-config-check-<line>`), run by the loader as a pre-flight (schema/upgrade findings printed before the first scenario) | Valuable for upgrade projects; wrong place in a test API. The Groovy compile check is a 6 s pre-flight nobody should have to write a test for. |
| JVM agent (clock + file redirect) | Keep, **opt-in through the manifest** (`files.map`, `time.shiftJvmClock: true`), passed as `-javaagent` by the generated POM, not dynamically attached | JDK 21 warns, a future JDK refuses dynamic attach; most scenarios only need midPoint's `Clock`. |
| 4.0 and 4.4 lines | Owner decision (question 2) | 4.8+: 2,318 lines shared in `base-4.8` plus 61-79 per line. 4.0/4.4: 837 shared in `base-4.0` plus 1,176/1,288 per line, a second recorder/driver implementation and a JDK 11 toolchain. Keep if an active 4.4 -> 4.8 upgrade project needs them; otherwise freeze the PoC for them. |
| `MidPoint` god interface / `MidPointImpl` | Split into `Loader`, `Targets`, `Drivers`, `Services`, `Why` behind `Scenario` | Maintainability. |
| `mp.script(code)`, `validateValue`, `generateValue`, `bulkAction` | Keep under `s.midpoint()` | Useful, not scenario-level. |

### 3.7 Packaging

- **JUnit 5 extension + per-line artifacts** (`midpoint-config-tester-4.8`, `-4.9`, `-4.10`): one line per JVM is a
  hard constraint (midPoint static state), and surefire already gives one JVM per module. No Maven plugin: a plugin
  would re-invent forking and classpath per line and hide the test from the IDE.
- **A starter** (`mvn archetype:generate` or a copied `pom.xml` in docs): one `tests` module in the config repo with a
  profile per line (`-Pline-4.8`, `-Pline-4.10`) that swaps the tester dependency, the JDK toolchain and the overlay.
  Running two lines is `mvn test -Pline-4.8 && mvn test -Pline-4.10`.
- **CLI** (`midpoint-config-tester check <dir>` for static checks; later `run` for YAML scenarios) as a shaded jar per
  line. Not in phase 1.
- Publish to Maven Central or GitHub Packages; today's "install from source" is a real adoption blocker.

---

## 4. Example: joiner / mover / leaver and a role request with approval

Config directory: the `fixtures/scenario` set (HR CSV resource with inbounds and reactions, LDAP resource, a user
template that assigns `Dept Sales`/`Dept IT` from `costCenter`, roles with LDAP constructions and group associations,
the recurring task `Reconcile HR`) plus `role-privileged-access.xml` and `approvers.xml` from `fixtures/async`
(two-stage approval: cost-center owner and IT department, then security officers; escalation after 5 days), with a
system configuration that adds mail notifiers for new employees, approvers and requesters (the PoC fixtures have none;
the mail assertions below assume them).

`tests/midpoint-tester.yaml`:

```yaml
config: { dirs: [../config], extensionSchema: ../config/schema }
targets:
  HR:   { connector: csv }
  LDAP: { connector: ldap, initialData: initial-data/people-and-groups.ldif, referentialIntegrity: true }
mail: { redirect: true }
initialData: [initial-data/approvers.xml]          # cc-owner, it-member, sec-officer with their roles and orgs
```

### 4.1 In the proposed API

```java
@MidPointScenarios
class HrLifecycleTest {

    /** Project helper: what "hire" means in this deployment. Five lines, owned by the project. */
    record Hr(Scenario s) {
        void hire(String no, String first, String last, String dept) {
            s.source("HR").add(no, Map.of("firstName", first, "lastName", last, "department", dept));
            s.task("Reconcile HR").run();
        }
        void update(String no, String column, String value) {
            s.source("HR").modify(no, column, value);
            s.task("Reconcile HR").run();
        }
        void terminate(String no) {
            s.source("HR").delete(no);
            s.task("Reconcile HR").run();
        }
    }

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";
    static final String SALES = "cn=sales,ou=groups,dc=example,dc=com";
    static final String IT = "cn=it,ou=groups,dc=example,dc=com";

    @Test
    void joinerMoverLeaver(Scenario s) {
        Hr hr = new Hr(s);

        s.step("hire");
        hr.hire("1001", "John", " DOE ", "Sales");
        s.user("e1001").hasFullName("John Doe").hasItem("costCenter", "Sales").hasRole("Dept Sales")
                .hasAccountOn("HR").hasAccountOn("LDAP");
        s.target("LDAP").account(JOHN).hasAttribute("cn", "John Doe").hasAttribute("ou", "Sales").memberOf(SALES);

        s.step("move to IT");
        hr.update("1001", "department", "IT");
        s.user("e1001").hasRole("Dept IT").hasNoRole("Dept Sales");
        s.target("LDAP").account(JOHN).hasAttribute("ou", "IT").memberOf(IT).notMemberOf(SALES);

        s.step("leave");
        hr.terminate("1001");
        s.user("e1001").doesNotExist();
        s.target("LDAP").account(JOHN).doesNotExist();
        s.target("LDAP").group(IT).hasNoMember(JOHN);
    }

    @Test
    void privilegedAccessNeedsTwoApprovals(Scenario s) {
        new Hr(s).hire("1001", "John", "Doe", "IT");

        s.step("request");
        s.as("e1001").request().assign("Privileged Access");
        s.user("e1001").hasNoRole("Privileged Access");
        Case c = s.cases().single();
        c.isOpen().atStage(1);
        c.workItems().assignedTo("cc-owner").exactly(1);
        c.workItems().offeredTo("IT department").exactly(1);
        s.mails().to("cc-owner@example.com").withSubject(containing("Privileged Access")).exactly(1);

        s.step("stage 1");
        c.workItems().assignedTo("cc-owner").approve(as("cc-owner"));
        c.workItems().offeredTo("IT department").claim(as("it-member")).approve(as("it-member"));
        c.atStage(2);
        c.workItems().offeredTo("Security officers").exactly(1);

        s.step("escalation after the deadline");
        s.time().advance("P6D").runScanners();
        c.workItems().open().single().hasEscalationLevel(1).hasAssignee("sec-officer");

        s.step("stage 2");
        c.workItems().open().single().approve(as("sec-officer"));
        c.isClosed().withOutcome("approve");
        s.user("e1001").hasRole("Privileged Access").hasItem("description", "privileged IT-100");
        s.mails().to("e1001@example.com").withSubject(containing("approved")).exactly(1);
    }
}
```

If `hasAttribute("ou", "IT")` fails, the failure message shows the account's attributes and then, from the recorder,
the outbound `ldap-ou` with its source `costCenter` old/new values and condition, and the inbound `hr-costCenter`
that fed it. The user reads the trace; they never select it.

### 4.2 The same in today's API (condensed from `HrToLdapScenarioTest` and `ApprovalTest`)

```java
@MidPointTest
class HrLifecycleTest {
    static final File DIR = new File("config");
    static final String ROLE = "10000000-0000-0000-0000-00000000c020";   // Privileged Access
    static final String TASK_OID = "10000000-0000-0000-0000-00000000f501";

    @BeforeAll
    static void configure(MidPoint mp) {
        mp.importObject(new File(DIR, "object-template-user.xml"));
        mp.importObject(new File(DIR, "system-configuration.xml"));
        mp.importResource(new File(DIR, "resource-hr.xml"), ResourceOptions.defaults().realConnector());
        mp.importResource(new File(DIR, "resource-ldap.xml"),
                ResourceOptions.defaults().realConnector().referentialIntegrity());
        mp.importObject(new File(DIR, "roles.xml"));
        mp.importObject(new File(DIR, "approvers.xml"));
        mp.importObject(new File(DIR, "role-privileged-access.xml"));
        mp.importObject(new File(DIR, "task-reconcile-hr.xml"));
        mp.ldap("LDAP").loadLdif(PEOPLE_AND_GROUPS);
    }

    @Test
    void joinerMoverLeaver(MidPoint mp) {
        TargetSystem hr = mp.target("HR"), ldap = mp.target("LDAP");
        hr.addAccount("1001", Map.of("firstName", "John", "lastName", " DOE ", "department", "Sales"));
        mp.step("hire");
        Run hire = mp.runTask("Reconcile HR");
        UserType john = mp.user("e1001");
        assertThat(john.getFullName().getOrig()).isEqualTo("John Doe");
        assertThat(john.getCostCenter()).isEqualTo("Sales");
        assertThat(john.getAssignment().stream().map(a -> a.getTargetRef().getOid())).contains(SALES_ROLE_OID);
        assertThat(mp.situation("e1001", "LDAP")).isEqualTo(SynchronizationSituationType.LINKED);
        assertThat(ldap.attribute(JOHN, "cn")).isEqualTo("John Doe");
        assertThat(ldap.groupValues(SALES, "member")).contains(JOHN);
        assertThat(hire.forFocus("e1001").mapping("hr-familyName").newValues()).containsExactly("Doe");

        hr.modifyAccount("1001", "department", "IT");
        mp.step("change");
        Run change = mp.runTask("Reconcile HR");
        assertThat(ldap.attribute(JOHN, "ou")).isEqualTo("IT");
        assertThat(ldap.groupValues(IT, "member")).contains(JOHN);
        assertThat(ldap.groupValues(SALES, "member")).doesNotContain(JOHN);
        assertThat(change.forFocus("e1001").mapping("hr-costCenter").newValues()).containsExactly("IT");

        hr.deleteAccount("1001");
        mp.step("leave");
        Run leave = mp.runTask("Reconcile HR");
        assertThat(mp.user("e1001")).isNull();
        assertThat(ldap.accountNames()).doesNotContain(JOHN);
        boolean constructionOfTheDeletedUser = leave.forFocus("e1001").mappings("it-group").stream()
                .anyMatch(e -> Boolean.FALSE.equals(e.conditionNew()));
        assertThat(constructionOfTheDeletedUser).isEqualTo(Lines.atLeast(mp, "4.4"));   // line branch
    }

    @Test
    void privilegedAccessNeedsTwoApprovals(MidPoint mp) {
        mp.target("HR").addAccount("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "IT"));
        mp.runTask("Reconcile HR");
        String johnOid = mp.user("e1001").getOid();
        ObjectDelta<UserType> assign = (ObjectDelta<UserType>) (ObjectDelta<?>)
                mp.change(UserType.class, johnOid).assign(ROLE).delta();

        Run request = mp.approvals().request("e1001", assign);
        assertThat(mp.approvals().cases()).hasSize(1);
        assertThat(mp.approvals().cases().get(0).stageNumber()).isEqualTo(1);
        assertThat(request.select().within("stage", "number", "1").element("approverExpression").size()).isEqualTo(2);
        Approvals.WorkItem ccOwner = mp.approvals().workItems().stream()
                .filter(i -> i.assignees().contains("cc-owner")).findFirst().orElseThrow();
        assertThat(request.mails()).extracting(Mail::to).anyMatch(t -> t.contains("cc-owner@example.com"));

        mp.approvals().approve(ccOwner, "cc-owner");
        Approvals.WorkItem org = mp.approvals().workItems().stream()
                .filter(i -> i.candidates().contains("IT department")).findFirst().orElseThrow();
        mp.approvals().claim(org, "it-member");
        mp.approvals().approve(org, "it-member");
        assertThat(mp.approvals().workItems().get(0).stageNumber()).isEqualTo(2);

        mp.time().advance("P6D");
        mp.time().runTriggerScanner();
        Approvals.WorkItem item = mp.approvals().workItems().get(0);
        assertThat(item.escalationLevel()).isEqualTo(1);
        assertThat(item.assignees()).contains("sec-officer");

        Run last = mp.approvals().approve(item, "sec-officer");
        assertThat(mp.approvals().cases().get(0).outcome()).isEqualTo("approve");
        assertThat(mp.user("e1001").getAssignment().stream().map(a -> a.getTargetRef().getOid())).contains(ROLE);
        assertThat(last.mapping("privileged-description").newValues()).containsExactly("privileged IT-100");
    }
}
```

The differences that matter: the proposed version has no `@BeforeAll`, no OIDs, no prism types, no `Run`, no line
branch, no identity strings; the "hire" vocabulary is the project's; every assertion is about something an integrator
can see in the GUI or in the directory.

---

## 5. Migration / rewrite plan

Rewrite the surface, keep the engine. Phases ordered by the owner's complaints (deployment loading first, because it
reuses the most and unblocks real-config trials).

**Phase 1 - Deployment loader and manifest (on the existing runtime).**
Manifest parser; type-ordered raw import with reference resolution; overlays and properties; `targets:` driving
`MirrorResourceXml`/`RealResourceXml` and initial data; `mail:`/`files:` from the manifest; baseline snapshot after load;
`ResetMode` removed. Deliverable: the PoC tests' fixtures loaded through a manifest instead of `@BeforeAll`; one real
customer configuration loaded end to end (the strongest validation available: a real deployment has hundreds of
objects, filter references, sequences, lookup tables and encrypted values the fixtures do not).
Reuse: `TesterRuntime`, `PgmemServer`, `Harness*`, `LineSupport`, `ConfigFiles`/`Xml` (parsing, rewriting),
`MirrorResourceXml`, `RealResourceXml`, `MailRedirect`, `TaskSchedules`, `Baseline`; `importObjectRaw` already exists,
so the raw baseline import is mostly ordering and reference resolution on top of it.
Risk: raw import of objects the PoC always model-imported may surface differences (filter references in inducements,
`resolutionTime=run`, autoassign); the real-config trial finds them.

**Phase 2 - Scenario API.**
`Scenario`, target/source handles, `User`/`Account`/`Case`/`WorkItem`/`Mail`/`ReportOutput` outcome objects with
assertion helpers, `as(user)`, `request()`, `time()`, `expectFailure`. Drivers stop returning `Run`; the recorder keeps
recording per driver call behind `why()`; failure messages append the trace. Port the PoC's scenario tests
(`HrToLdap*`, `Activation*`, `LiveSync*`, `Approval*`, `Certification*`, `Report*`, `MailNotification*`) to the new API
as the tool's examples; keep the recorder/identity tests on the internal API.
Reuse: `ApprovalsImpl`, `CertificationImpl`, `ReportsImpl`, `DashboardsImpl`, `AccessImpl`, `ChangeImpl`, `TimeImpl`,
`MirrorTargetImpl`/`CsvTargetImpl`/`LdapTargetImpl`, `Mail`, `WrittenFile`, `Recorder`, `EvaluatorHook`,
`FocusResolver` (for `why()` per user).
Risk: the verb set grows back into a god object; counter it with a rule that every verb must appear in one of the
section-2 scenario types.

**Phase 3 - Outcome snapshots and the upgrade check.**
Snapshot writer per step; record/verify modes; diff report; accepted file at outcome granularity; expression-level
golden as `-Dsnapshot.level=evaluations`. Re-run the tool's own 4.8 -> 4.9 -> 4.10 chain on outcomes and see how
many of the ~75 accepted differences disappear (expected: most).
Reuse: `Normalizer`, `GoldenComparison` (diff and accepted-file logic), `Golden` (mode handling).

**Phase 4 - Static checks as a CLI and pre-flight; packaging.**
`midpoint-config-check-<line>` shaded jar; the loader runs schema + upgrade validation on the config dir before the
first scenario and prints findings; starter POM with line profiles; publish artifacts.
Reuse: `tester/static` as is.

**Phase 5 (optional) - YAML scenario runner.** Only after phases 1-3 have been used on real projects.

**What is deleted:** `MidPoint` interface and `MidPointImpl` (replaced), `Selection`/`Identity`/`Location` from the
public package (moved to `...tester.why`), `ResetMode`, `ResourceOptions` (manifest), `Coverage`/`ExpressionPlaces`/
`PlaceMatrix` from the user artifact, `docs/expression-places*.md` from the README's feature list.

**Risks across phases**

- Raw import semantics differ from the model import the PoC verified everything against; some fixtures assume
  full-processing import. Mitigation: phase 1 real-config trial, both modes available.
- Outcome assertions can hide real regressions in intermediate behaviour (a mapping that now errors but whose target
  keeps the old value through tolerance). Mitigation: the snapshot includes operation result status and errors of
  every run; `why()` on demand.
- Keeping 4.0/4.4 doubles the surface for every phase.
- The scenario API tempts the author to put HR semantics into the tool. Mitigation: the helper pattern in section 4.

## Open questions for the owner

1. **Baseline import: raw (as `ninja import`) or full-processing model import?** Recommendation: raw, with model import
   available as a driver.
2. **Which lines does the rewrite carry?** 4.8/4.9/4.10 only (one shared base, 61-79 lines per line), or also 4.0/4.4
   (a second base, ~1,200 lines per line, JDK 11 toolchain)? Recommendation: 4.8+ in the rewrite; keep the PoC tagged for 4.0/4.4 until
   a project needs them.
3. **Does expression-level golden stay as an opt-in mode, or is the recorder reduced to `why()` only?**
   Recommendation: opt-in mode, since the machinery exists; drop it later if nobody turns it on.
4. **JVM agent: opt-in via manifest with `-javaagent` in the generated POM (recommended), or keep dynamic attach as
   the default?**
5. **Java only, or Java + Kotlin DSL from the start?** Recommendation: Java API shaped for Kotlin use; no separate DSL in
   phase 2.
6. **Encrypted values in deployed XML outside connector and mail configuration: overlay files, a `properties:`
   substitution, or a tool-supplied keystore?** Recommendation: overlay + properties; no keystore handling.
