# Redesign: production evaluation, recorded per expression

Status: old layers deleted. The tester covers midPoint 4.0, 4.4, 4.8, 4.9 and 4.10 (golden results verified along
4.0 -> 4.4 -> 4.8 -> 4.9 -> 4.10, and 4.8 -> 4.10); the `expression/` and `clockwork/` layers were ported (step 6c)
and deleted (step 6d); 4.9 was added in step 6e; step 6f narrowed the accepted differences. This
document keeps the history and the decisions; the expression layer it refers to is in the history (last at `65aa3a1`).
Since phase 4 of the next API ([next-design.md](next-design.md)) the PoC tests named here, the cross-line golden chain,
the coverage report and the expression-places matrix are removed too; they are in the history before `1e39281`,
`b91f4fb` and `b3cb422`.

## Why

The expression layer re-implements how production binds variables per context and per line. Every divergence found so
far (outbound `thisObject`/`source`, inducement path variables, inbound focus, empty pre-focus, report `locale`) was in
that re-implementation, some gaps cannot be closed (`assignmentPath`, live objects, model-impl evaluators), and its
fixtures passed while being wrong for real midPoint (approval rule in an inducement never fires on 4.8; a timed action
without a stage duration fails; `automaticallyCompleted` referring to `stageDefinition` fails on every line because the stage
is pre-evaluated). Real midPoint costs 4.5-7 s per JVM start and 15-30 ms per reset, 0.1-0.2 s per test.

## Principle

Real midPoint computes; the tester drives it through production entry points and observes every expression
evaluation. The only line-specific code is the harness (Spring wiring, SQL, reset) and a thin adapter for renamed APIs.

## Experiments (branches kept locally: `exp/e1-recorder`, `exp/e2-context`, `exp/e3-preview`)

- E1: every evaluation is capturable on 4.0-4.10 (variables, sources, condition, output triple, error) at ~0.09 ms
  each; keys by location match across lines. Caveats: nested expressions (filters, `checkExpression` in `generate`)
  inherit or lose the enclosing mapping's description depending on the line, so parsing descriptions misattributes them;
  the same `<value><targetRef/></value>` gives two fingerprints on 4.4+; password generation is random; association
  processing order can flip.
- E2: cases-impl, workflow-impl, certification-impl plus `ctx-cases.xml`, `ctx-workflow.xml`, `ctx-certification.xml`
  add ~0.3-0.9 s to the start, nothing to the reset. Approvals (preview via `partialProcessing.approvals=PROCESS` and
  `getHookPreviewResults(ApprovalSchemaExecutionInformationType)`, execution, `CaseService.completeWorkItem`),
  certification (`createCampaign`, `openNextStage`), reports (report export task, `ReportTestUtil`) all work. Needs:
  archetypes 023-025, 507, 511, 520 from initial objects; an owner on every task; waiting for asynchronous work.
- E3: on 4.8 `previewChanges` projects all waves, writes nothing to targets, does not advance sequences (simulation
  `SAFE`); createOnDemand gives null OIDs; resource searches still create discovery shadows and an audit record;
  scripted model hooks and policy vetoes do not run. With inbounds, preview reads full shadows and can differ from
  execution. 4.0/4.4 preview advances sequences and really creates on demand.

## Decisions

1. **Execution is the default driver**, preview an explicit option (approval previews, quick output checks). The DB is
   reset per test method, so execution leaves nothing behind.
2. **Recorder at mapping level.** Wrap mapping evaluation (and the other expression entry points) with a thread-local
   stack so a nested evaluation is attributed to its enclosing mapping without parsing descriptions. Each record keeps
   structured values; normalization is a separate step used by golden comparison and display.
3. **Expression identity is structural**: the owner object (role, resource, template) and the element path in it,
   each element told apart by a midPoint discriminator (name, attribute ref, kind/intent, inducement target or
   construction, stage number...) instead of its position. Tests select by it, the owner and the mapping name; never by
   line number (lines change with every edit of a file). File and line are kept for display only.
4. **Golden results are set-based per expression**: old = zero+minus and new = zero+plus, generated OIDs replaced by
   names, line-specific class names folded, random values masked, evaluation counts reported apart from outputs.
5. **Fixtures must be valid in real midPoint**; the expression-layer fixtures are not reused blindly.
6. **Line adapter** for API drift (work item completion, certification stage opening, task owner, preview options).
7. **Static checks** (Groovy compile, schema, upgrade validator, lint) are a module without a runtime (step 5).

## Layout

- `tester/core`: line-neutral sources (not a Maven module), compiled into each line module (as the old layers' `shared/`):
  public API (`MidPoint`, `@MidPointTest`, `Run`, `Evaluation`, `Selection`, `Identity`, `Location`,
  `Normalizer`, `OperationFailed`, `TargetSystem`), runtime (pgmem, snapshot reset, mirror connector, SQL loader,
  baseline), the recorder's model and stack (`internal.Recorder`, `Record`, `EvaluatorHook`), the configuration
  index (`internal.ConfigIndex`, on `scan.ConfigScanner`), golden results (`internal.Golden`, `GoldenResults`,
  `GoldenComparison`) and coverage (`internal.Coverage`).
- `tester/core/src/test`: the tests, shared by every line module (fixtures found through `tester.fixtures`, set by
  the surefire configuration of `tester/pom.xml`; `Fixtures`, `Lines.atLeast(mp, "4.10")` for pinned differences).
  Fixtures are written in the newest format; `fixtures-<line>/` holds variants for that line and older ones
  (`Fixtures.of(mp, file)`, `Fixtures.dir(mp, name)`), e.g. resources without `delineation` for 4.4 and 4.0.
- `tester/base-4.0`: parent of the Java 11 lines 4.0 and 4.4 (JDK 11 toolchain): the sources both share
  (`ApprovalsImpl`, `CertificationImpl` on `WorkflowService`/`CertificationManager`, `ScriptHook40`,
  `RecordingTransport`). `tester/midpoint-4.4` and `tester/midpoint-4.0` add their dependencies, harness, Spring
  context, recorder hook, drivers, SQL and baseline (see step 6b).
- `tester/base-4.8`: parent of the 4.8+ lines (native repository): harness (`Harness48`), Spring context
  (`tester/ctx-tester.xml`: model-intest imports plus `ctx-cases`, `ctx-workflow`, `ctx-certification`, `ctx-report`),
  recorder hooks (`RecorderInstaller`, `Recorder48`, `RecordingMappingFactory`, `RecordingMappingImpl`), drivers
  (`Drivers48`, `ApprovalsImpl`, `CertificationImpl`...), `Line48` (configuration, schema scripts, baseline names).
- `tester/midpoint-<line>` (4.8, 4.10): the line's `LineSupport`, SQL (`config/sql/native` of the release) and
  baseline objects (`config/initial-objects` of the release: 021, 023-025, 041, 042, 507, 511, 520, 521, 530, 531).
  `LineSupport` is the whole line surface the shared code calls: `LINE`, `CONFIG_FILE`, `SCHEMA_SCRIPTS`, `BASELINE`,
  `config`, `startHarness`, `drivers`, `serializeRealValue`, and the adapters for API drift: `outputDefinition`
  (4.8 `toMutable()`, 4.10 `mutator()`), `mappingTargetName` (4.8 `getMappingQName`, 4.10 `getTargetItemName`),
  `clearRepositoryCaches` (see 4.10 findings).
- `tester/static`: static checks (step 5), its own parent: `core/` line-neutral sources and tests (package
  `...tester.check`), `midpoint-4.8/` the line's dependencies and `StaticLine`.
- Package `com.github.wadahiro.midpoint.tester`; one exception, see hook points. The classes that came from the old
  layers' `common/` keep their packages in `tester/core`: `...midpoint.scan` (`ConfigScanner`, `EvaluatorFingerprint`)
  and `...midpoint.common.MidPointLine` (static checks); see step 6d.

## Runtime (step 1, done)

One midPoint per JVM (4.5 s start), shared by all `@MidPointTest` classes. Reset as in the old Clockwork layer
(`ResetMode`): to the baseline before each class; after each method (default `PER_METHOD`) to the state after the
class's `@BeforeAll`, captured at the first `beforeEach`; `PER_CLASS` keeps changes across methods. A reset restores the
database snapshot, midPoint's caches, the mirror targets and the configuration index. Every task the drivers create is
owned by the administrator (cases and certification need an owner).

## Recorder (step 2, done)

Hook points (4.8):

- **Mappings**: `RecorderInstaller` (a `BeanFactoryPostProcessor`) changes the class of the `mappingFactory` bean
  (ctx-model.xml) to `RecordingMappingFactory`; its builders build `RecordingMappingImpl`, a `MappingImpl` whose
  `evaluate` reports start and end. Every data mapping goes through `MappingFactory.createMappingBuilder()` (also via
  `ModelBeans.mappingFactory`). `RecordingMappingImpl` sits in midPoint's package `...model.common.mapping` because
  `MappingImpl`'s constructor and the mapping's parts (`mappingConfigItem`, `variables`, `sources`) are package-private.
  Not covered: metadata mappings (built through a private method; their expressions are still recorded), and
  `MappingImpl.clone()` (only used after evaluation).
- **Expressions**: the evaluator factories of each `ExpressionFactory` are wrapped by JDK proxies (`EvaluatorHook`,
  derived from the old layers' `EvaluatorCoverage`); every evaluator run, nested ones and non-mapping entry points (correlation, conditions,
  sync reactions, policies, approvers, filters, functions) included, reports context, output triple or error.
- Both push onto a thread-local stack (`Recorder`): an evaluation's parent is what this thread is evaluating, so a
  filter's script is nested in the `assignmentTargetSearch` that is nested in its mapping. Nothing is parsed from
  descriptions. Only evaluations during a driver call are recorded (on any thread); a driver call lasts until the
background work it started is done (see step 3b).

Record contents: variables and sources as midPoint gave them (live objects, not copies; the unnamed default variable is
left out), condition old/new (mapping fields; for a mapping's expression, midPoint's skip flags), output triple copied
at the end (midPoint keeps changing it), error, parent and nested evaluations. A mapping's output is its final triple,
not the evaluator's.

Location (`Location`, resolved lazily when a test asks; file and line, for display and as the base of the identity):

1. `origin`: the mapping's `ConfigurationItemOrigin` gives the owner (template, role, resource; for `InDelta` the object
   the delta is for). The position comes from the `MappingType` value's place in the owner as loaded (element names
   with positions among siblings, matched in the file); for copies, from the origin path (followed as far as it goes,
   then the mapping below it by ID and name: midPoint's path of a focus mapping lacks the `mapping` step, a condition's
   is its assignment's) or the copy's own container IDs (resource object types and association types, whose origin is
   approximate), else the one value of the owner equivalent to it (`MappingPlacement`). A named mapping must have that
   name at the position. Per line and kind: docs/line-anatomy.md, "Mapping placement".
2. `name`: the owner's only mapping with that `<name>` (within the nearest located enclosing evaluation if several).
3. `fingerprint`: the expression's canonical form (`ConfigScanner`), within the nearest located enclosing evaluation,
   else anywhere; identical expressions are then told apart by the hook's name (`HookRun`), the element the driver
   evaluates in (`Recorder.inScope`: the dashboard widget), the notifier whose filter is evaluated
   (`Recorder.inNotifierScope`: its element and name) and midPoint's description of a search's populate expression;
   the rest are listed as `alternatives()`. A mapping whose owner no file has is located elsewhere only by
   its name (an unnamed one stays unlocated), and expressions in it are not located elsewhere. Used for nested and
   non-mapping expressions.

Files are indexed when given to `importObject(s)`/`importResource` (XML text: written to `<workDir>/tester-xml/`;
resources as imported, with the mirror connector, to `<workDir>/tester-resources/`; `<workDir>` is the module's `target`).

**Identity** (`Evaluation.identity()`, `Identity`): derived from the located element by walking from the owner's root
down to it in the indexed file. Each element gets a discriminator where midPoint has one: `name`; `ref` of attributes,
associations, template items; kind/intent of a resource object type; `number` of approval and certification stages;
`identifier` of a widget; an unnamed mapping's target path; an assignment's or inducement's target (by name), else its
construction (resource name/kind/intent) or policy rule; a reaction's situation. Siblings these do not tell apart get a
discriminator used only among them (`ConfigFiles.secondaryKeys`): an inducement's order (a qualifier after its
target: `inducement[target=Employee, order=2]`), focus mappings or target filter; a transition's target state; a data
field's type; a check expression's failure message; a variation's condition (name or description); a message
argument's position (`argument=1`); then display label, description, documentation (free text collapsed and cut after
40 characters). An element no sibling shares its discriminator (or lack of one) with needs none. Only where nothing
tells siblings apart does an element get its ordinal (`#2`), and the identity says so (`hasOrdinal()`); on the tester's
fixtures none does (19 before these discriminators). Container IDs are not used: they are not in most files. An expression located at its mapping
(midPoint's default `asIs`) is the mapping's identity plus its part. Unlocated evaluations keep owner and midPoint's
position (`isLocated()` false). Examples (all from the tests):

```
resource:HR/schemaHandling/objectType[account/default]/attribute[ri:fullname]/inbound[hr-familyName]
resource:HR/schemaHandling/objectType[account/default]/correlation/correlators/filter/ownerFilter/equal/expression
resource:CRM/schemaHandling/objectType[account/default]/attribute[icfs:name]/outbound
objectTemplate:User template/mapping[assign-department-role]/expression/assignmentTargetSearch/filter/equal/expression
role:Employee/inducement[construction=HR/account/default]/construction/attribute[ri:title]/outbound[employee-title]
role:Mailer/inducement[focusMappings=description]/focusMappings/mapping[target=description]
role:Privileged Access/assignment[policyRule=approve-privileged-access]/policyRule[approve-privileged-access]/policyActions/approval/approvalSchema/stage[number=1]/approverExpression[description=the IT cost center owner]
accessCertificationDefinition:role-assignments/stageDefinition[number=1]/reviewerSpecification/reviewerExpression
systemConfiguration:SystemConfiguration/modelHooks/change/hook[after-user-change]
systemConfiguration:SystemConfiguration/notificationConfiguration/handler/simpleUserNotifier[welcome]/subjectExpression
report:users-by-cost-center/objectCollection/view/column[fullName]/export/expression
dashboard:admin-dashboard/widget[identifier=employees]/presentation/variation[condition=fully staffed]/condition
```

**Selection.** Selectors that return one `Evaluation` group what matches by identity: several evaluations of one place
(projector waves, assignment paths) give the last; two or more places fail with an `AssertionError` that lists them
and how to select each one. This applies to `run.mapping(name)`, `Selection.mapping(name)`, `Selection.one()` and the
selectors in midPoint terms:

```java
run.mapping("mail");                              // fails if CRM and LDAP both have an outbound named 'mail'
run.in("LDAP").mapping("mail");                   // narrowed by owner (name or OID)
run.outbound("LDAP", "ri:mail");                  // also outbound(resource, kind, intent, attr); inbound(...) alike
run.templateMapping("User template", "fullName"); // by name, else by target path
run.construction("Employee", "ri:title");         // outbound of a role's construction
run.select().identity("role:Mailer/inducement[construction=LDAP/account/default]/construction/attribute[ri:title]/outbound[title]").one();
run.select().within("role:Mailer/inducement[focusMappings=title]").mappings().one();   // this place and what is in it
run.select().attribute("ri:title").objectType("account", "default").inbounds().byIdentity();
run.mappings("mail");                             // every evaluation of every mapping with that name
```

The message of an ambiguous selection:

```
Mapping 'mail' in add user:...(jdoe) is ambiguous: 2 different places were evaluated:
  resource:CRM/schemaHandling/objectType[account/default]/attribute[ri:mail]/outbound[mail] (1 evaluation)
      select with run.in("CRM").mapping("mail")
  resource:LDAP/schemaHandling/objectType[account/default]/attribute[ri:mail]/outbound[mail] (1 evaluation)
      select with run.in("LDAP").mapping("mail")
All their evaluations: run.mappings("mail")
```

When both are in one object, the hint is `run.select().identity("...").one()`. midPoint accepts two mappings with the
same name in one object; registering such a file logs a warning (`target/tester.log`), and `ConfigIndex.duplicateMappingNames()` lists them.

API:

```java
@MidPointTest
class HrTest {
    @BeforeAll static void config(MidPoint mp) {
        mp.importResource(new File(DIR, "resource-hr.xml"));
        mp.importObject(new File(DIR, "object-template-user.xml"));
    }

    @Test void familyName(MidPoint mp) {
        mp.target("HR").addAccount("1001", Map.of("fullname", " John  DOE "));
        Run run = mp.importAccount("HR", "1001");
        Evaluation m = run.mapping("hr-familyName");           // last evaluation; run.mappings(name) for all
        assertThat(m.newValues()).containsExactly("Doe");      // zero + plus; oldValues() = zero + minus
        assertThat(m.identity()).hasToString(                  // owner and structure, no line numbers
                "resource:HR/schemaHandling/objectType[account/default]/attribute[ri:fullname]/inbound[hr-familyName]");
        assertThat(m.expression().variable("focus")).isNotNull();
        Selection filters = run.select().owner("User template").evaluator("script").path("filter");
    }
}
```

**Per focus.** A task run evaluates the same places for every account it processes. `Evaluation.focus()` is the focus
of the outermost evaluation (its `focus` variable with a name or OID, else the owner of its shadow when the run ends),
inherited by what is nested; `run.forFocus(nameOrOid)` is a `Run` of that focus's evaluations on which every selector
and ambiguity rule above works, `Selection.focus(nameOrOid)` the filter. Golden results keep their own per-subject key
(the `focus` variable only). `mp.shadow(focus, resource[, kind, intent])` and `mp.situation(focus, resource)` read the
linked shadow's synchronization state from the repository.

Drivers of step 2 (all return a `Run`; midPoint errors throw `OperationFailed` carrying the run): `importObject(s)`,
`importObjectXml`, `importObjectRaw`, `importResource`, `addObject(Xml)`, `execute`, `modify` (replace), `recompute`,
`importAccount`, `preview` (`Run.modelContext()`); readers `object`, `user`; escape hatches `bean`, `harness`.
`Normalizer` gives the stable text (generated OIDs as `gen:<name>`, sets old/new, `generate` output masked, the
identity; file and line for display) that `toString()` shows and golden files use.

Findings: midPoint also evaluates a template script for the old state of an added user (sources empty: `"null null"`);
the correlation expression gets `correlationContext` and `correlatorState`; preview on 4.8 leaves repository and target
untouched and gives the same mapping outputs as execution for an outbound change.

## Synchronous drivers (step 3a)

All return a `Run` (or an `Answer<T>`/`ValueCheck` holding one) and throw `OperationFailed` unless noted.

```java
mp.importObjects(dir);                                  // one run for all files, one result
mp.assign(UserType.class, "jdoe", "Sales", SchemaConstants.ORG_MANAGER);   // unassign(...) alike
mp.change(UserType.class, "jdoe").add("organization", "Sales").delete(UserType.F_LOCALITY, "Tokyo")
        .assign("Auditor").options(ModelExecuteOptions.create().reconcile()).execute();  // tryExecute(), preview()
mp.modifyAdd(...); mp.modifyDelete(...); mp.deleteObject(UserType.class, "jdoe");
mp.execute(delta, ModelExecuteOptions.create().raw());
Run r = mp.tryExecute(delta);                           // r.isSuccess(), r.status(), r.failure()
Run r = mp.attempt(() -> mp.addObjectXml(xml));         // any driver, without throwing
run.hooks("forbid-test-users");                         // HookRun: name, state, focusType/Name, evaluation()
run.notifications(); mp.notifications();                // dummy transport messages: per run / since the test began
run.mails(); mp.mails();                                // mails received by the in-memory SMTP server, same scopes
mp.as("reader").search(UserType.class, query);          // also get, isAuthorized, execute; denial is an answer
mp.validateValue("Password policy", "secret1");         // ValueCheck: isValid(), failed(), run(); generateValue
mp.script("custom.execute('initials', [name: n])").variable("n", "John Doe").evaluate();
mp.bulkAction(xml);                                     // BulkActionOutput: console(), data()
```

- Paths by name are matched against the object definition (extension items untested); strings are converted for
  polystrings and enumerations. `unassign` deletes the matching assignment values (with their IDs).
- Scripts midPoint runs without an `ExpressionFactory` (model hooks, `execute-script`, report scripts) are recorded by
  `ScriptHook48`, which wraps the language evaluators of the `ScriptExpressionFactory`; a run inside an already recorded
  `<script>` expression is not recorded twice. A hook is recognized by the description `ClockworkHookHelper` gives its
  script (the script bean is no container value, so its parent cannot be followed); state and focus are taken when it
  starts (the model context is live). Its location comes from the canonical form, as for other expressions.
- Notifications are enabled (model tests disable them); `dummy` is a recording `DummyTransport`, cleared before each
  test method. Notifier filters and expressions go through the `ExpressionFactory` and are recorded.
- Mail transports run as configured, except their servers: an imported system configuration's mail servers point at an
  in-memory SMTP server (GreenMail) without TLS or authentication (`MailRedirect`). Its delivery handler keeps each
  SMTP transaction with its envelope (bcc = envelope minus To and Cc); `Mail` is parsed from what was received.
- `as(user)` logs the user in for one call through `ModelService`/`SecurityEnforcer` and the administrator back in.
- `script(...)` is the only driver whose variables come from the test: it evaluates a `<script>` expression through the
  production `ExpressionFactory` (built-in and repository function libraries, full profile). Use it for library
  functions and snippets, not to stand in for configuration that midPoint should evaluate.
- Line-specific calls live behind `internal.LineDrivers` (`Drivers48`).

Findings: a hook without a state runs four times per add (primary, secondary, secondary without focus, final); 4.8
turns a recipient expression's string into a `RecipientExpressionResultType`; midPoint appends a subscription notice to
notification bodies; Groovy scripts have no default import of `UserType`.

Open: lifecycle state models (no driver beyond plain changes yet). Asynchronous work (approvals, certification,
reports, task threads) is done in step 3b.

## Asynchronous work and services (step 3b)

**Waiting.** Every driver call ends only when midPoint has no background work: no task thread running (a
`TaskListener` counts handler threads and marks them on the `Recorder`) and no Quartz job executing (the listener's
thread end comes before the job executor writes the task's final state; a reset into that open transaction failed with
pgmem's "still in a transaction" and left the worker blocked), no single-run task runnable now, no recurring
task due now (never run, or Quartz's next fire within 1 s). The task's evaluations are therefore in the run of the call
that started it, marked with `Evaluation.task()` (task name; null on the driver's thread). A call fails with
`OperationFailed` after `tester.taskTimeout` seconds (60). The reset waits up to 10 s, suspends what is left, puts
Quartz in standby, restores the database, then `TaskManager.synchronizeTasks` (Quartz keeps jobs in memory) and
`Clock.resetOverride()`, re-arms the triggers (`TaskSchedules.rearm`: extra one-shot triggers dropped, a recurring
task's standard trigger starting one interval, or at the next cron time, after its restored last run), restores the
targets and only then starts Quartz again. Without this a reset ran tasks: `synchronizeTasks` re-creates the standard
trigger of a ready task whenever it is missing or looks different, built to start now, so the task ran inside the
reset (against the restored database but the previous test's targets) and on into the next test's driver calls; 4.0
and 4.4 re-create it on every synchronization for a task without a handler URI (4.4's activity tasks:
`QuartzUtil.triggerDataMapsDiffer` takes two missing handler URIs as different; `!Objects.equals` from 4.8). That was
the "recurring task runs twice in `runTask`" of the scenario on 4.4. An import holds Quartz too, until the import has
ended: on 4.0 a runnable task fired during its own import lost the work state its first run had created (the import
wrote the task after it) and was suspended. A driver change that adds, modifies or deletes a task (`addObject`,
`execute`, `change`, `modify`, `deleteObject`: `MidPointImpl.executeChanges`) holds Quartz the same way until
`executeChanges` returns (`TaskRunsTest.aTaskAddedThroughTheClockworkRunsOnceAfterTheChange`, every line; 4.0 did not
lose a run there in six adds without the hold, the hold is for the same window as the import's). `mp.waitForTasks()` is a driver that only waits; `mp.runTask(name)` waits
for earlier background work, schedules the task now and waits for exactly one more run of it (`Recorder.taskRuns`,
counted by the task listener's `onTaskStart`). Imported tasks run as in production (a recurring task that never ran
at once, in the import). Quartz fires on the real time: `mp.time().runDueTasks()` runs the recurring tasks due by
midPoint's clock (the last run start, taken in real time, converted with the clock offset of that run). From the
test's first `advance` until the reset, the regular runs of loosely bound recurring tasks are held: a Quartz trigger
listener vetoes their standard trigger (`TaskSchedules`), except the firing "Run now" re-creates for an interval task
(MID-1410), so they run only through `runDueTasks` and `runTask`. A pause would not do: `TaskSynchronizer` resumes a
paused standard trigger of a ready task, and a paused trigger means a suspended task to `due` and `rearm`. Evaluations
of a recurring task that runs between driver calls (only before the first `advance`) are not recorded. Expected but not covered: worker
threads of multi-threaded tasks are recorded without their task's name.

**Report scripts.** Columns and subreports run their scripts straight on the `ScriptExpressionFactory`
(`ReportServiceImpl.evaluateScript`), recorded by `ScriptHook48` (step 3a). The report service sets
`objectVariableMode` on the bean before running it, so the fingerprint leaves it out unless a configuration file has
it; then report scripts are located like other expressions.

**Structural selection** (see Identity above): besides `identity(...)`/`within(identity)`, `Selection.within(element,
keyChild, value)` keeps evaluations whose configuration element is inside `<element>` with `<keyChild>value</keyChild>`
(`ConfigIndex.enclosingKey`), and `element(name)` those whose element is `<name>`; `inTasks()` those that ran in a
task.

```java
Approvals.Preview p = mp.approvals().preview(delta);              // partialProcessing approvals=process
p.single().stage(1).expectedApprovers();                          // ["cc-owner", "IT department"]
p.run().select().within("policyRule", "name", "approve-privileged-access")
        .within("stage", "number", "1").element("approverExpression");
mp.approvals().request(delta);                                    // cases(), workItems(): assignees, candidates, info
Run last = mp.approvals().approve(mp.approvals().workItems().get(0), "cc-owner");  // reject, complete, delegate
last.mapping("privileged-description").task();                    // "Execution of ...": the approved change

mp.time().advance("P6D");                                         // midPoint's Clock override, cumulative
mp.time().runTriggerScanner();                                    // escalation; runValidityScanner()

String c = mp.certification().createCampaign("role-assignments").oid();
mp.certification().openNextStage(c).select().element("reviewerExpression");
mp.certification().decide(mp.certification().campaign(c).caseOf("kate", "Auditor").workItems().get(0),
        "sec-officer", "revoke", null);                           // closeCurrentStage, startRemediation, closeCampaign

Reports.Output out = mp.reports().export("users-by-cost-center", Map.of("costCenter", "HR-200"));
out.lines(); out.run().select().within("column", "name", "fullName");

Dashboards.Widget w = mp.dashboards().widget("admin-dashboard", "employees");   // DashboardService
w.number(); w.color(); w.run().select().path("variation").element("condition");
```

Users who complete work items or decide need authorizations: the baseline adds the Approver and Reviewer roles (041,
042) and the archetypes of approval execution, validity and trigger scanner tasks (521, 530, 531). Approval and
certification drivers take the acting user by name (`runAs`: logged in, task owned by them).

Findings: the approved change is executed within the call of the last approval (archetype 521, which the execution
task refers to, is not required but is part of the baseline as in production); escalation runs the `escalate` approver expression once per open work item; the export ends with a "No active subscription"
line; `DashboardService` applies variations in turn and a false one restores the default display, so only the last
variation counts; a validity scan drops the role of an expired assignment but does not evaluate its mappings, so a
strong mapping's value stays; a mapping of a removed assignment (remediation) still produces its value.

Not done: distributed (worker) report export attribution. Done later: work item claim/release/forward and case
cancellation (`WorkflowService` / `CaseService`), certification reiteration (`reiterateCampaign`), object change
approvals with a `processSpecification` (`ObjectApprovalTest`); certification delegation and escalation exist only as
timed actions (trigger scanner, `CertificationStagesTest`), report import (`ReportImportTest`).

## midPoint 4.10 (step 6a)

`tester/midpoint-4.10` is `base-4.8` plus its `LineSupport`, SQL and baseline: every test runs on both lines (61).
API differences handled: `MutablePrismPropertyDefinition` is gone (`mutator()`), the mapping's target name accessor and
config item type changed (`RecordingMappingImpl.recordedOrigin()` keeps only the origin), `CertificationManager` has
no `openNextStage` (the drivers use `AccessCertificationService`, as the GUI does, on both lines).

Line differences, pinned in the shared tests (`Lines.atLeast(mp, "4.10")`):

- Certification (`CertificationTest`): 4.10's `openNextStage` starts a certification task
  (`createNextStageTask`); the driver call waits for it, so the reviewer expressions are in the run, with
  `Evaluation.task()` set (null on 4.8). Closing a stage goes through a task too.
- Preview (`RecorderTest.previewThenExecute`): 4.10's `previewChanges` is `executeChanges` in `SIMULATED_PRODUCTION`
  mode, collecting the final model context; on both lines nothing is written and no simulation result is kept.
- Inbound variables (`RecorderTest.inboundMapping`): `focus` is the `UserType` bean on 4.10 (`MappedSourceItem`), a
  `PrismObject` on 4.8; 4.10 adds `association` and `object`.
- Dashboards (`ReportTest.dashboardVariation`): 4.10 keeps a true variation's display; 4.8 lets a later false one set
  it back.
- Coverage (`CoverageTest`): `associationConstruction` has an evaluator on 4.10 (not evaluable on 4.8).

Findings: 4.10 resource mappings are copies of single attribute definitions (4.8 copied whole object types), located
now by the container ID of the copy's top value; 4.10's `ExtItemCache.initialize` does not clear its new
`nameToExtItem` map, so after a database reset stale entries made the repository drop `icfs:name` from shadows
("Expected primary identification"); `LineSupport.clearRepositoryCaches` clears it. In production the database is
never rolled back, so this concerns the tester only. Associations of the 4.10 model (`associationType`) are tested
by `AssociationTypeTest` (step 6c).

Before step 6c, on the fixtures, 4.10 covered the same 66 places of 73; the 2 `associationConstruction` places are evaluable but not
covered (5 not covered, 0 not evaluable).

## Golden results and coverage (step 4)

Purpose: record the evaluations of a test suite on line A, verify them on line B, and read what the upgrade changes.
`MidPointExtension` hands the runs of every test method (and of the class's `@BeforeAll`, key `@BeforeAll`) to both,
before the reset (identities and OID names need the test's configuration index).

**Content** (`GoldenResults`): per test, per run (see run keys below), per place (`Identity`):

- `out`: the final outcomes: per focus object (the `focus` variable) the outcome of its last evaluation, as a set over
  the focus objects (a task or report evaluates one place for many objects in an order that is not stable);
  evaluations without a focus all count. A nested place (an expression in a filter, a function's argument) keeps per
  focus the outcomes of all its evaluations within the last evaluation of its root (the outermost enclosing
  evaluation, usually the mapping): a search evaluates its filter once per value in an order that is not stable
  (step 6f). An outcome is condition old->new, then `new=` (zero + plus), `removed=`
  (old - new, when not empty) and, after ` | old=`, the old values (zero + minus) when they differ from the new ones;
  or `error <root cause simple class>: <first message line>`, or `no output`.
- `all`: the other outcomes (intermediate projector waves; the template's evaluation for the old state).
- `count`: the number of evaluations; `src`: distinct source values; `vars`: the names of the variables. Variable
  values are left out: whole objects under several aliases whose short text changes per wave and per line (they made
  the first draft three times larger and noisy); a missing variable is what breaks a script, and what a script makes
  of the values is in its output.
- `flags`: `ordinal` (identity depends on sibling order), `unlocated` (not found in the files), `baseline` (in one
  of midPoint's own objects of the runtime baseline as the baseline has it: owner OID of a baseline object, not
  located, and no registered file has an object with that OID; `GoldenResults.isBaseline`).
- `failed <message>` under a test that failed when recorded (its results stop there).
- A mapping's own `expression`/`condition` evaluations are not separate places (the mapping has their outcome);
  nested expressions (filters, functions) are.
- Normalization (`Normalizer`): generated OIDs as `gen:<name>`, `generate` output and protected strings masked, class
  names without package and `Impl`, `ScriptN` in Groovy messages folded, dates without a zero fraction, references as
  `ref Type:name(oid)`, other containers and schema beans (except assignments) as one-line JSON; a schema bean the
  JSON serializer does not know (4.8+'s `RecipientExpressionResultType`) by its non-empty properties in parentheses,
  so that its content is compared (not cut to `{...}` as line-dependent text).

**Format**: one text file per test class (`<golden.dir>/<fully qualified class name>.golden`), one fact per line, sorted, indented by
test / run / place; newlines in values escaped. Chosen over JSON/YAML because the values are already normalized text
(`"Doe"` would become `"\"Doe\""`) and a plain `diff` of two files is then the list of changes.

```
line 4.8

test inboundMapping
  run 1 import account jdoe
    place resource:HR/schemaHandling/objectType[account/default]/attribute[ri:fullname]/inbound[hr-familyName]
      count 1
      out condition true->true new=["Doe"]
      src input = [" John  DOE "] -> [" John  DOE "]
      vars account, configuration, focus, operation, projection, resource, shadow, user
```

**Modes**: `-Dgolden.mode=record|verify` (default off), `-Dgolden.dir` (default `src/test/golden`). Recording replaces
the tests that ran and keeps the others. Both modes write the actual results to
`target/tester/golden/<fully qualified class name>.golden`; verifying writes `<fully qualified class name>.diff.md` when something differs.

**Policy** (`GoldenComparison`, `-Dgolden.failOn`, default `changed,missing,new`): a changed final outcome set, a
place no longer evaluated and a newly evaluated place fail the test (`AssertionError` from `afterEach` with the test's section
of the report; `@BeforeAll` differences fail the class in `afterAll`). Count and input (`vars`, `src`) changes and a
test missing in a golden file are notes in the report; a class without a golden file fails (after its tests: a wrong
`golden.dir` must not pass); `golden.failOn=count,...` makes them failing, `none` reports only.

The report after changing `new=["Doe"]` to `new=["DOE"]` in the golden file (as CI does):

```
### RecorderTest.templateMapping - FAILED (1 failing, 0 notes)

Run 1: `import account jdoe`

- **changed** `resource:HR/schemaHandling/objectType[account/default]/attribute[ri:fullname]/inbound[hr-familyName]`
  - golden: `condition true->true new=["DOE"]`
  - now: `condition true->true new=["Doe"]`
```

Notes follow the failing differences of a run, as `note: count` (`evaluations: 2 -> 3`) or `note: inputs`
(`vars -removed +added`, source lines).

**Coverage** (`Coverage`): `ConfigIndex.places()` lists every expression element (`ConfigScanner`'s definition) of
the files with the identity the recorder gives its evaluations (same index code, so one place has one identity on
both sides). After the last test: `target/tester/coverage.md` for every file given to `importObject(s)` /
`importResource` during the run (XML given as text is left out), and with `-Dcoverage.dir=<dir>`
`coverage-<dir name>.md` for every XML file under it. Sections: not covered; covered (by which tests); covered among
identical expressions (an expression outside a located mapping is located by its canonical form, and among identical
ones neither midPoint nor the driver says which ran: all are reported so, none as covered; hooks, dashboard widgets,
populate expressions and the filters of named notifiers are told apart); not evaluable by midPoint of this line
(first evaluator element has no factory in the `ExpressionFactory`, or nested in such); duplicates (one identity in
several files); evaluated but not listed (scan and recorder disagree; empty on the fixtures). Identical expressions in
different mappings are now told apart (`CoverageTest`: the same script in two mappings, one covered, one not; the same
mapping in a role that is not assigned; two hooks with the same script; a condition of a user's own assignment and the
identical condition of a role). A section lists the located mappings midPoint did not place itself (`name`,
`fingerprint`): on 4.8+ only the mappings nested in an evaluator of an association type.

Identical expressions, places in coverage.md (fixtures given to the runtime) before -> after these changes: covered
among identical 6 -> 2 (4.0), 8 -> 2 (4.4+): the hooks, the ratio dashboard's data fields and the search template's
filter and populate expressions are attributed exactly; left: the two notifiers with the same `expressionFilter`
(`ExpressionFilterHelper` passes only an approximate origin, the evaluation context none). Since then the tester
replaces the `expressionFilterHelper` bean with `ScopedExpressionFilterHelper` (`processEvent` of the line's
signature), which evaluates the filters of a notifier in the scope of its element and name
(`Recorder.inNotifierScope`, `ConfigIndex.narrow`): named notifiers with identical filters are attributed exactly
(`MailNotificationTest.identicalFiltersOfTwoNotifiersAreToldApart`), unnamed ones stay covered among identical.

On the fixtures (`-Dcoverage.dir=<repo>/tester/core/src/test/resources/fixtures`), 4.8, after step 6c: 162 places in 50
files, 145 covered, 6 covered among identical, 5 not covered, 6 not evaluable (`associationConstruction`, 4.9+, the
4.10 association evaluators of `provisioning/resource-ldap-association-types.xml`, and scripts nested in them), 4
identities in several files, 19 with an ordinal. (Before step 6c: 73 places in 24 files, 66 covered.)

CI (`tester-golden`): record, verify in a second Maven run with coverage, then a changed golden value must fail
verification with the expected report.

### Golden results across lines (step 6a)

Recording on one line and verifying on another is the upgrade check. What differs between lines without changing what
the configuration computes is a note, not a failure:

- **Final outcomes.** Only `out` is compared (`-Dgolden.outcomes=all` compares `all` too): intermediate projector waves
  differ between lines.
- **Effective result.** Outcome sets that differ only in the old values or the old condition (the same new and removed
  values, the same new condition) are `note: old`: 4.10 gives the inbound mapping of a just created account no old
  value, 4.8 the created one. A value that only one line removes is a change.
- **Old state** (`option new-state` in the accepted file, step 6f). Sets equal once every outcome is cut to what it
  leaves in the new state (`GoldenResults.newState`: the new condition and the new values or the error; only the
  condition when it is false) are `note: old state`. For 4.0 -> 4.4: 4.0 keeps a mapping's evaluation against the
  operation's old state last, 4.4+ evaluate once more against the changed object, so the removed values differ.
- **Moved places** (`option moved <golden identity> -> <identity>`, step 6f). A place whose identity changed with the
  configuration format (4.4 -> 4.8: correlation, confirmation, classification and reactions move from
  `<synchronization>` into the object type) is compared at its new identity, flagged `moved from ...`.
- **Line-dependent text.** Sets equal once error messages are cut to their class and container JSON to `{...}` are
  `note: text`.
- **Run keys.** Runs are matched by key, not by number: the step name given with `mp.step("approve")` (until the next
  step or the end of the test), else the driver label, `#2`... when repeated. Labels are line-neutral: objects by
  name, never by OID (generated OIDs, case and work item container IDs, timestamps and counters differ between lines
  and runs). Deltas as `modify user:jdoe [locality]` (change type, object, item paths; the name looked up before the
  call), options by name (`options [raw]`), `add user:jdoe`, `import user:jdoe` for an XML text; approvals as
  `approve <work item name> stage 2 as cc-owner`; certification as `create campaign role-assignments`,
  `open stage 1 of role-assignments` (the campaign by its definition: its own name has a counter),
  `decide revoke on kate/Auditor stage 1 as sec-officer` (case = object/target names), `close stage 1 of ...`. `CertificationTest`, `ApprovalTest`, `ChangeTest` and `GoldenTest` pin them on
  every line. Runs whose keys match on
  neither side are paired by number (`note: run`), the rest is missing or new.
- **midPoint's own objects.** Differences at `baseline` places are notes listed last (`-Dgolden.baseline=compare`
  lets them fail). A place is `baseline` only when what midPoint evaluated is the baseline's own content: the owner has
  the OID of a baseline object (`Baseline.oids()`), the place was not located (the baseline objects are added to the
  repository directly and never registered with `ConfigIndex`, so a located place is in a file the test imported), and
  no file the test registered (or XML it gave as text) has an object with that OID (`ConfigIndex.hasObject`, objects
  only: `isKnownOid` also matches references such as `ownerRef` to the administrator). Integrators re-import initial
  objects, above all their system configuration (OID ...0001) with notifiers, global policy rules, model hooks and
  lifecycle models: those places are compared even where the index cannot locate them. Decided when the test's
  results are taken, before the runtime's reset, while the index is the test's. An expression a test adds to a
  baseline object it did not import (by a modification) stays `baseline`. Formerly the owner OID alone decided,
  which left every expression of the tests' system configurations out of the comparison.
  `GoldenTest.ownSystemConfigurationIsComparedBaselineArchetypeIsANote` pins both sides (a global policy rule of the
  test's system configuration fails on change; the handler mapping of the Approval-related task archetype, 4.4+, is a
  note). `unlocated` places are the tests' own `mp.script(...)` snippets and bulk actions, compared as usual.
- **Failed tests.** A test that failed is marked in the golden file; verifying against it, or a test that fails now,
  turns its differences into notes (the test's own failure is what to read).
- **Accepted differences** (`-Dgolden.accepted=<file>`): one per line, `<kind> | <class>.<test> | <run key> |
  <identity>  # reason` (a comment is a `#` at the line start or after a blank, followed by a blank, so `[#3]` in
  identities and `#2` in run keys are fine), `*` as a wildcard; a matching difference is
  `note: ... - accepted: <reason>`. An entry for a test also matches each invocation of a repeated test. Since step
  6f every entry of the tester's files names one test (no `*` in `<class>.<test>`), and options (`option ...`) change
  how a pair is compared. A real line
  difference is pinned in a test (`Lines.atLeast`) and, if the golden results show it, accepted there with the test's
  name as the reason. `tester/core/src/test/golden/accepted-<a>-<b>.txt` hold them for 4.0 -> 4.4, 4.4 -> 4.8 and
  4.8 -> 4.10 (step 6c), 4.8 -> 4.9 and 4.9 -> 4.10 (step 6e). `AcceptedDifferencesTest` (no midPoint needed) checks
  that every reason names an existing `<Class>.<method>`; the 4.0 -> 4.4 entries that had none got their pins then
  (`SynchronizationTest.costCenterAssignsTheRoleAutomatically`: attribute outbounds of a deleted account;
  `AccessTest.isAuthorizedAndExecuteAsUser`: the template of a refused change;
  `EntitlementTest.associationFromLinkTakesOnlyTheGroupOfEachRole`: the inbound's new membership; the last filter
  evaluations in `SearchTest.assignmentTargetSearchFollowsTheCostCenter` and
  `EntitlementTest.associationTargetSearchFindsTheGroupComputedFromTheUser`). Before step 6c, 4.8 -> 4.10 gave 0 failing differences and 32 notes (30 `inputs`:
  `+association +object` of inbounds; 2 `old`).

Sample of the 4.8 -> 4.10 report:

```
### RecorderTest.normalizedText - passed (0 failing, 2 notes)

Run 1: `add user:carol`

- note: old `resource:HR/schemaHandling/objectType[account/default]/attribute[icfs:name]/inbound[hr-name]`
  - golden: `condition true->true new=["carol"]`
  - now: `condition true->true new=["carol"] | old=[]`
- note: inputs `resource:HR/schemaHandling/objectType[account/default]/attribute[icfs:name]/inbound[hr-name]`
  - golden: `vars `
  - now: `vars +association +object`
```

CI (`tester-cross-line`; since step 6d the whole chain, since 6e with 4.9): record on 4.0, 4.4, 4.8 and 4.9, verify
each next line and 4.8 -> 4.10 with the accepted files; each pair's report is copied to `target/golden-diff/<a>-<b>/`
(4.10 is verified twice into the same target directory), shown in the job summary and uploaded as the artifact
`golden-diff-chain`; then a changed golden value must fail on 4.10 and pass once accepted. Locally,
`.claude/skills/add-midpoint-line/scripts/golden-pair.sh <a> <b>` runs one pair the same way. `golden-chain.sh` (same
directory) runs several pairs with each line once where it can: the results a verify run writes under
`<workDir>/tester/golden` are the line's own (the same code path as a recording), so they serve as the recording of
the pairs that start from that line (CI since the speed work: 6 suite runs instead of 9). Still
open: ordinal identities shift when another line's initial objects differ (none in the tests).

## Static checks (step 5)

`StaticCheck` checks a directory of configuration XML against one line without a runtime (no Spring, no database;
~3 s, most of it initializing prism). Every finding has the identity the tester gives evaluations of the place: the
index code is shared, not copied. `ConfigIndex` was split: `ConfigFiles` (tester/core, runtime-free: elements,
`Identity`, places, duplicate names) and `ConfigIndex extends ConfigFiles` (locating records). The static module
compiles only `Identity`, `ConfigFiles`, `scan/` and `MidPointLine` from the tester's sources (compiler includes: a runtime
dependency of `ConfigFiles` breaks its build).

| Check | Finds | Severity |
|---|---|---|
| `COMPILE` | each Groovy place compiled on the line's classpath (model-impl, notifications, report, workflow, certification; no GUI, connectors or custom jars): missing or removed classes, syntax. Missing methods and FQNs used as values only fail at run time | error |
| `SCHEMA` | prism parse of each file, strict (an import without `compatMode` fails) and in compatibility mode (its warnings; not parsed at all: `unreadable`) | error / warning |
| `UPGRADE` | ninja verify's `ObjectUpgradeValidator` per object (4.4+; on 4.0 skipped, with the reason in the report), after numbering container values as the repository does; each item path located in the file | CRITICAL error, NECESSARY warning, else by status |
| `LINT` | evaluator elements not in the line's `c:expressionEvaluator` substitution group; mappings sharing a name in one object; Groovy reading the JVM's clock (`jdk-time`: `new Date()`, `System.currentTimeMillis()`, `now()` of `java.time`, `Calendar.getInstance()`, `basic.fromNow`) rather than midPoint's `Clock` (the tester's `mp.time()` moves both; a clock override of midPoint alone does not); elements told apart by position (`[#n]`, one note per element, with advice) | error / warning / warning / note |

```java
StaticReport r = StaticCheck.of(Path.of("config")).schemaDir(Path.of("config/schema")).run();
r.write(Path.of("target/static-check.md"));
r.assertNoErrors(Check.COMPILE);        // AssertionError: one line per failing place
assertThat(r.errors()).isEmpty();       // findings(check, severity), compileErrors(), places()
```

```
| ERROR | groovy | role:Legacy/inducement[construction=.../account/default]/construction/attribute[ri:dn]/outbound[legacy-dn]/expression | role-legacy.xml:13 | unable to resolve class com.evolveum.midpoint.schema.ResourceShadowDiscriminator (script line 2, column 33) |
| ERROR | CRITICAL | task:Recompute users/recurrence | task-legacy.xml:8 | CRITICAL recurrence: removed |
| NOTE | ordinal | role:Mailer/inducement[#1] | roles-ordinal.xml | <inducement> has nothing that tells it apart from its siblings (...): give it a <description>, or keep the order of its siblings |
```

A line plugs in as `tester/static/midpoint-<line>` with its BOM and a `StaticLine` class (prism context with
extension schemas, evaluator elements, upgrade validator or null); modules: 4.0, 4.4 (JDK 11 toolchain, as the
tester), 4.8, 4.9, 4.10. `-Dstatic.dir=<dir>` (`-Dstatic.schemaDir`) runs the checks on your configuration
(`target/static-check/<dir>.md`, fails on compile errors; the parent's own directory property is `static.home`, so
that `-Dstatic.dir` does not replace it). `StaticCheckTest` runs on every line and pins what differs:

| | 4.0 | 4.4 | 4.8 | 4.9 | 4.10 |
|---|---|---|---|---|---|
| `StaticLine` | no validator: `upgradeFindings` null, `UPGRADE` skipped (`StaticReport.skipped`) | as 4.8 | | validator without prism context, `ValidationItem` a record | as 4.9 |
| Groovy syntax error | `expecting ')', found '{'` (2.5) | `Unexpected input: '{'` | as 4.4 | as 4.4 | as 4.4 |
| Removed class (`ResourceShadowDiscriminator`) | compiles; 4.8's `SpringApplicationContextHolder` does not | as 4.0 | fails | fails | fails |
| Upgrade items of a 4.0 task | | `category`, `recurrence`: `deprecated` | `removed` | `removed` | `removed` |
| Prism messages | qualified names | qualified | qualified | local names (`Item descripton`, `query filter equal`) | as 4.9 |
| Evaluators | 14 | 14 | 14 | + `shadowOwnerReferenceSearch`, `associationConstruction`, `associationSynchronization` | + `filter` |

On the tester's fixtures, read with the line's `fixtures-<line>` variants as the tester reads them (a copy under
`target/static-check` where a variant applies): 162 places (4.0: 161, its Directory has no reaction condition), 19 with
an ordinal (as the coverage report), nothing fails to compile on any line. Before 4.9, `associationConstruction`
and `associationType` (both 4.9+) are strict parse errors and their evaluators lint errors; 4.9 and 4.10 have no
error. On
4.0 the files of the tests gated to 4.4+ fail strict parsing too (a `widgetData` dashboard, object collection reports,
an activity task). Findings: the fixture's search filter (`q:equal` in a `targetRef`) fails prism even in
compatibility mode on every line ("Unsupported query filter"), whatever its evaluator, also with a plain `q:value`;
the lint names the unknown evaluator where the line lacks it.

## midPoint 4.4 and 4.0 (step 6b)

Every shared test runs on 4.0 and 4.4 too (61; on 4.0 the 4 report and dashboard tests are skipped by an
assumption). Run with the JDK 11 toolchain (`mvn -t toolchains.xml -pl tester/midpoint-4.0 -am test`).

- **4.4**: native repository (`repo-sqale`, type `sqale`, `config/sql/native-new` of 4.4.11); `workflow-impl`
  holds the case engine (no `cases-impl`, no `ctx-cases.xml`); `ctx-report.xml` as on 4.8. Mapping hook as on 4.8
  (`RecordingMappingFactory` builds `RecordingMappingImpl`, package-private constructor of `MappingImpl`). Drivers:
  accounts are imported by searching the shadow and `importFromResource` (no import request builder), scanners and
  report exports are tasks with an activity switched to the background (no `ModelInteractionService.submit`), the
  export's file is that of the report's newest `ReportDataType`, the notification transport is registered at the
  `NotificationManager`.
- **4.0**: old Hibernate repository on PostgreSQL 16 (`postgresql-4.0-all.sql`, as the old Clockwork layer), plus
  `workflow-impl` and `certification-impl`. `MappingImpl` cannot be subclassed (private constructor, final builder):
  the hook is the model's `mappingEvaluator` bean (`RecordingMappingEvaluator`, every data mapping of the model goes
  through `MappingEvaluator.evaluateMapping`); variables, sources and output path are read by reflection, the
  condition result is computed from the condition triple and masks as `MappingImpl` does; a mapping's
  `condition`/`expression` are told by the description the mapping gives them (no local context description).
  Drivers: scanners are tasks with the scanner handler URIs; value validation has no limitation results, so a failed
  check is named by its message; reports are not supported (no object collection reports on 4.0), dashboards are not
  tested (no `widgetData` source); a baseline object starting with a comment is stripped of it (4.0's parser).
- **Identity without `ConfigurationItemOrigin`** (`MappingPlacement`, line-neutral): the owner is the mapping's
  origin object; the position is the mapping value's own place when it is still part of that object (templates,
  roles), else the place of the only value in the origin object that is prism-equivalent to the copy (resource
  object types, constructions; container IDs included). Two equivalent mappings in one object give no place, and the
  index falls back to the name and the canonical form, as on other lines. On the tests' configuration every mapping
  evaluation is located by `origin` on 4.0 and 4.4 (17 of 17 places of the HR/CRM/LDAP/Mailer scenario), and the
  golden results of all tests have the same 69 identities on 4.4 and 4.8 except the correlation filter, which the
  4.4 format places in `synchronization/objectSynchronization[account/default]` (new discriminator).

Line differences, pinned in the shared tests (`Lines.atLeast`):

- `ApprovalTest`: 4.4 lists the org (candidates) before the cost center owner in stage 1 (preview and work items);
  4.0, 4.8 and 4.10 follow the order of the approver expressions.
- `RecorderTest.mappingWithError`, `GoldenTest`: 4.0 evaluates a template mapping for the old state of an added user
  and keeps that result: the final outcome is `new=["John Doe"] removed=["null null"] | old=["null null"]`, and a
  failing script fails there first (`no number for null`).
- `ChangeTest.importObjectsIsOneRun`: 4.0 runs a hook without state three times in `secondary`, each with the focus
  (4.4+: twice, the second without focus).
- `RecorderTest.correlationExpression`: the 4.4 format has no correlation context variables.
- `HookAndNotificationTest`: a recipient expression's string stays a string before 4.8.
- `ReportTest`: 4.4 has no `resultHandling` of subreports (one row per user, the org column joins the list) and no
  subscription footer.
- `ScriptTest.validateValue`: 4.0 names a failed check by its failure message.

Findings: a bare `new AssignmentType()` has no definition on 4.0 and fails in an approval preview; the change
builder now applies it. On 4.0 a recurring task fired by Quartz while its own import was still writing it was not run
as expected (seen as "fired but not run"; with TRACE logging: its first run lost the work state it had created and
the task was suspended); imports now hold Quartz until they end, and `Harness40` no longer has a 2 s exception for
such a task. 4.0's provisioning resource cache needs clearing after a
reset (as in the old Clockwork layer).

Cross-line golden results (`-Dgolden.mode=verify`): 4.4 -> 4.8 fails only where the fixtures differ (the
correlation place, 5 tests; the report's subreport columns, 2 tests); 4.0 -> 4.4 fails on the template outcomes of
added users (old-state result, 13 tests). These and the differences of the tests ported in step 6c are accepted in
`accepted-4.0-4.4.txt` and `accepted-4.4-4.8.txt` (see step 6c).

## Porting the old layers (step 6c)

Everything the `expression/` and `clockwork/` tests verify was checked against real midPoint before the layers were deleted (step 6d). The
Clockwork layer drove real midPoint already: its tests moved over with their fixtures. The expression layer's tests
were read for the production behaviour they encode, which is now driven through production entry points; tests of the
hand-assembled engine itself (presets, stubs, its API) are not ported.

New test classes: `LdapTest`, `MembershipTest`, `ResourceSchemaTest`, `EntitlementTest`, `AssociationTypeTest`,
`ResetIntegrityTest`, `ResourceRedefinitionTest`, `ScenarioPerClassTest`, `SequenceTest`, `internal.MirrorResourceXmlTest`
(from `clockwork/`); `SynchronizationTest`, `LifecycleTest`, `VariablesTest`, `SampleConfigTest`, `SearchTest`,
`ApprovalDetailsTest` (from `expression/`); additions to `ReportTest`, `ScriptTest`, `HookAndNotificationTest`,
`AccessTest`. Fixtures: `fixtures/{provisioning,sequence,sync,lifecycle,variables,sample,search}`, new files in
`fixtures/async`; line variants in `fixtures-4.4/{sync,sample,async}` and `fixtures-4.0/{sync,provisioning}`.
The 4.8-format resources (`sync/resource-directory.xml`) have older-format variants; resources that only test
mappings (`variables/`, `sample/`, `search/`, `provisioning/`) use `objectType/objectClass` and `<synchronization>`,
deprecated since 4.6 but read by every line, so one file serves all four.

Summary (197 old test methods, 59 in `clockwork/`, 138 in `expression/`): 41 covered by tests that existed before, 138
ported, 18 not ported: 8 tested only the old layer (its presets, stubs and API), 10 encode production behaviour that the
tester cannot drive yet (validTo and readable passwords of the mirror, an extension schema, several values of a report
parameter, calls without a logged-in user) or that is an error message of a misconfiguration (listed with reasons
below). Classes that are only partly ported have one row per part.

Findings where the old layers' expectations were wrong or incomplete for real midPoint:

- Inbound after a change in the source (`SampleConfigTest.inboundAfterAChangeInHr`): the expression layer gave the
  inbound plus `Sato` and minus `Tanaka`. An import reads the account as it is: midPoint does not know the old value
  (no synchronization delta), so 4.8+ give the new value as unchanged (zero set) and 4.0 as added; nothing is removed by
  the inbound, the user's value is replaced all the same.
- Inbound `focus` (`VariablesTest.inboundSeesTheCurrentFocus`): the first evaluation sees the current focus as the
  expression layer said, but midPoint evaluates the inbound again in the next projector run, where the other inbounds'
  results are there, and that later value is stored.
- Assignment and inducement conditions (`SampleConfigTest.inducementConditionTurnsOnWhenTheCostCenterMovesToIt`,
  `VariablesTest`): such a condition is a mapping to a boolean; its result is the output (old/new values), while the
  mapping's own condition is always true. midPoint evaluates it twice (absolute and relative, `ConditionEvaluator`).
- Report column path `#` on 4.4 (`ReportTest.identifierPath`): not an empty column, the whole export fails
  ("Unexpected path segment: # in #").
- A `widgetData` dashboard widget without a data field (`ReportTest.dashboardObjectAndStoredDataWidgets`): the expression
  layer showed its stored data and applied its variation; `DashboardServiceImpl` gives no number and evaluates no
  variation unless the presentation has data fields.
- `automaticallyCompleted` referring to `stageDefinition` fails on every line ("Why" said 4.8+ before step 6c checked it)
  (`ApprovalDetailsTest.automaticallyCompletedHasNoStageDefinition`); the short `skip` on 4.0 does not fail but creates a
  case that completes when its stage opens (`skipInItsShortForm`).
- `populate` vs `populateObject` (`SearchTest.createOnDemandCreatesTheOrg`): the expression layer refused createOnDemand;
  in midPoint `populateObject` fills the created object (`populate` fills the assignment value).
- A function evaluator runs whether or not its sources have values: for a user without names the library function got
  null arguments and failed the add (`SearchTest`: the fixture's function mappings got a condition).
- Task query variables (`SearchTest.taskQuerySeesTheConfiguration`): besides `configuration`, `focus`, `projection`,
  `shadow` and `resource` are declared (null), not absent.

Line differences found while porting (pinned in the tests):

- 4.10's import resolves reference filters with an expression and fails ("filter matches no object",
  `ModelImplUtils.resolveRef`); 4.8 and older skip them. `resolutionTime` `run` avoids it
  (`VariablesTest.importOfATargetFilterWithAnExpression`; the role fixtures with such filters have it).
- 4.10 puts the removed assignment of an `assignmentTargetSearch` twice in the minus set, as computed and as stored with
  its ID (`SearchTest.assignmentTargetSearchFollowsTheCostCenter`).
- Before 4.8, the import of a single account that the synchronization condition excludes fails (4.4 "No object class
  found for the shadow", 4.0 "No objectclass specified"); 4.8 imports it without classification
  (`SynchronizationTest.serviceAccountsAreNotSynchronized`).
- 4.0 does not confirm a single correlation candidate; 4.4+ do (`SynchronizationTest.singleCandidateMustBeConfirmed`).

Tester changes: `TargetSystem.setEnabled` (an account disabled on the target system); golden results of
`@RepeatedTest`s are keyed per repetition (they overwrote each other); the mirror's `MirrorResourceXmlTest` moved into
the tester. Not covered by the mirror at the time: validTo and readable passwords (both since, see below).

**The behaviours that could not be driven (after step 6c).** Of the 10 production behaviours the summary lists as not
ported, 9 are covered now, with these tester changes:

- Passwords: the mirror returns them as the real connector does (native `password/readable`, else the CSV connector's
  `passwordAttribute`, the LDAP connector's `passwordReadStrategy`; `MirrorClass.PasswordReading`), checked against the
  real connectors (`PasswordReadingRealConnectorTest`, `LdapPasswordTest`); `TargetSystem.setPassword`. validTo came
  with the mirror's validity dates (`PasswordAndValidityTest`).
- Extension schema: the shared tests run with `tester/core/src/test/resources/schema` (`tester.extensionSchemaDir` in
  `tester/pom.xml`); a reset between methods that write extension items works on every line (`ExtensionTest`).
- Reports: a `Collection` parameter value is several values; a failed export reports the first message of the task's
  result tree (it was empty).
- Correlation cases need the correlation case archetype: 027 is in the 4.8+ baselines.
- The two misconfigurations are asserted with production's messages (`MisconfigurationTest`).

Not driven: the 4.4 NPE of an authorization filter without a principal, which production does not reach (see the
inventory). New line differences: report parameters with several values (4.4 fails), the confirmation error text, the
unique characters of a value policy without `minUniqueChars` (checked against `minLength` from 4.9), the 4.6+
correlation (skipped before 4.8); see `docs/line-anatomy.md`.

Accepted cross-line differences (`tester/core/src/test/golden/accepted-<a>-<b>.txt`, each with its reason):

- 4.0 -> 4.4: 4.0 keeps a template mapping's old-state evaluation last (final outcomes with the old value removed);
  4.0 evaluates the outbounds of an account it deletes and the template of a change it then refuses; filter and
  association-inbound evaluation order; synchronization (no reaction conditions, no confirmation of a single
  candidate, the system configuration import), pinned in `SynchronizationTest`.
- 4.4 -> 4.8: the 4.8 format of the resource fixtures (synchronization places move into `schemaHandling`); reports
  without `resultHandling` and the probe report (pinned in `ReportTest`); the Spring holder script and the missing
  function library (pinned); the association inbound's unstable group order.
- 4.8 -> 4.10: association outbound values of the 4.10 model (`ShadowAssociationValueType`); `locale` in report
  columns (pinned); the association inbound's unstable group order.

### Inventory

C = covered by a test that existed before this step, P = ported in this step, N = not ported. New tests are in
`tester/core/src/test` (class.method); "all lines" means the test runs and passes on 4.0, 4.4, 4.8 and 4.10.

**clockwork/** (Clockwork layer: real midPoint already, so its expectations held; fixtures copied to
`fixtures/provisioning`, `fixtures/sequence`, the SCIM variant of 4.0 to `fixtures-4.0/provisioning`)

| Old test | What it checks | New test |
|---|---|---|
| AssociationTypeTest.associationConstruction... | 4.10 `associationType`: `associationConstruction` + `associationFromLink` fill the groups of the user's roles | P `AssociationTypeTest.associationConstructionMakesTheUserAMemberOfTheGroupsOfItsRoles` (4.10) |
| AssociationTypeTest.associationSynchronization... | 4.10 association inbound (`shadowOwnerReferenceSearch`) assigns the role owning the group | P `AssociationTypeTest.associationSynchronizationAssignsTheRoleThatOwnsTheGroup` (4.10) |
| AssociationTypeTest.linesBefore410RejectTheDefinition | 4.8 rejects `associationType`, 4.0/4.4 `delineation` | P same name (all lines; renamed `linesBefore49RejectTheDefinition` when 4.9 was added) |
| BehaviourTest.correlationLinksTheExistingUser | correlation + `unlinked -> link` | P `SynchronizationTest.correlationLinksTheConfirmedOwner` |
| BehaviourTest.unmatchedAccountCreatesAUser | `unmatched -> addFocus` | P `SynchronizationTest.unmatchedStaffIsAdded` |
| BehaviourTest.collidingNamesGetAnIterationToken | iteration token on name collisions | P `SynchronizationTest.collidingNamesGetAnIterationToken` (with the two-digit token and reserved token of the expression layer) |
| BehaviourTest.costCenterAssignsTheRoleAutomatically | autoassign by cost center | P `SynchronizationTest.costCenterAssignsTheRoleAutomatically` (with existence/activation mappings: the account stays, disabled) |
| BehaviourTest.initialPasswordFollowsTheValuePolicy | `generate` with a value policy | P `SynchronizationTest.initialPasswordFollowsTheValuePolicy` |
| BehaviourTest.usersWithoutCostCenterAreRejected | `objectState` constraint + `enforcement` | C `ChangeTest.policyRuleConstraintAndTryExecute` (same mechanism, other condition) |
| BehaviourTest.fullProcessingImportOfTheSystemConfiguration... | 4.0 fails a full-processing import of the system configuration with an autoassign role | P `SynchronizationTest.fullProcessingImportOfTheSystemConfigurationRunsTheAutoassignMappings` |
| BehaviourTest.lifecycleFollowsTheStateModel | lifecycle transitions and entry action | P `LifecycleTest.lifecycleFollowsTheStateModel` |
| EntitlementTest (6) | legacy associations: meta-role groups, imported role, `associationFromLink`, `associationTargetSearch`, association inbound | P `EntitlementTest` (same 6 methods, all lines) |
| HrOnboardingTest.importCreatesUserWithTemplateAndRole | inbound, template, role, construction | C `RecorderTest.inboundMapping`, `templateMapping`, `outboundOfRoleConstruction` |
| HrOnboardingTest.localityIsProvisionedUpperCase | outbound on modify | C `RecorderTest.previewThenExecute` |
| HrOnboardingTest.everyMethodStartsFromTheClassState | reset per method | C `RuntimeTest.startsFromTheClassState` |
| internal.MirrorResourceXmlTest (11) | the mirror's resource transform (no midPoint) | P `internal.MirrorResourceXmlTest` (verbatim, package renamed) |
| LdapGroupMembershipTest (6) | LDAP by DN, membership by `associationTargetSearch`, rename in place, object classes, schema/capabilities | P `LdapTest` (same 6 methods) |
| MembershipTest (7) | mirror options: computed `memberOf`/`groups`, referential integrity, case-insensitive DNs, SCIM ids | P `MembershipTest` (same 7 methods; the 4.0 `scimId` rename is the fixture variant) |
| ModelHookTest.hooksRunInTheirStateForTheirFocusType | hook state/focus filtering, disabled hook | C `HookAndNotificationTest.hooksRunInTheirStateForTheirFocus` |
| ModelHookTest.aHookWithoutStateRunsAfterEveryState | 4.0 vs 4.4+ hook run counts | C `ChangeTest.importObjectsIsOneRun` |
| ModelHookTest.anExceptionInAHookFailsTheOperation | hook exception | C `HookAndNotificationTest.anExceptionInAHookFailsTheOperation` |
| ModelHookTest.hooksSeeOtherFocusTypes | hook for roles | C `ChangeTest.importObjectsIsOneRun` |
| ResetIntegrityTest | caches survive no reset (10 repetitions) | P `ResetIntegrityTest` (each repetition has its own golden key now) |
| ResourceRedefinitionTest | same resource OID redefined after reset (4.0 resource cache) | P `ResourceRedefinitionTest` |
| ResourceSchemaTest (3) | stored legacy schema, no schema + options, activation/password capabilities | P `ResourceSchemaTest` (same 3 methods, on the tester's HR) |
| ScenarioPerClassTest (4) | `PER_CLASS` reset | P `ScenarioPerClassTest` (no tester test used `PER_CLASS` before) |
| SequenceTest (2) | `sequentialValue`: one value per Clockwork run, kept on recompute | P `SequenceTest` |

**expression/** (hand-assembled engine; its fixtures were not reused blindly: they had no connector, an approval rule
in an inducement, filters with expressions that 4.10 cannot import...)

| Old test | What it checks | New test |
|---|---|---|
| ApprovalCertificationTest.approverExpressionsAddToTheStaticApprovers | approver expressions, static approvers | C `ApprovalTest.preview`, `approveThroughAllStages` |
| ApprovalCertificationTest.automaticallyCompletedWhenTheStageOpens | auto completion | C `ApprovalTest.previewAutomaticallyCompleted` |
| ApprovalCertificationTest.beforeTheCaseStartsTheShortFormSkipsSince44 | short `skip` before the case: 4.0 full URI only | P `ApprovalDetailsTest.skipInItsShortForm` (4.0 creates the case and completes it) |
| ApprovalCertificationTest.beforeTheCaseStartsThereIsNoStageDefinition | `automaticallyCompleted` has no `stageDefinition` | P `ApprovalDetailsTest.automaticallyCompletedHasNoStageDefinition` (all lines) |
| ApprovalCertificationTest.timedActionApproversSeeTheWorkItem | escalation approvers | C `ApprovalTest.escalateAfterTheDeadline` |
| ApprovalCertificationTest.additionalInformationFromStringsAndInformation | additional information | C `ApprovalTest.approveThroughAllStages`, `selfRequest` |
| ApprovalCertificationTest.additionalInformationHasNoStageDefinition | no `stageDefinition` there | P `ApprovalDetailsTest.additionalInformationHasNoStageDefinition` |
| ApprovalCertificationTest.approvalDisplayNameOfAnAssignment | display name | C `ApprovalTest.preview`; P key/arguments in `ApprovalDetailsTest.displayNameWithTheObjectDisplayInformation` |
| ApprovalCertificationTest.approvalDisplayNameOfAnObject | object approval (no target) | C `ObjectApprovalTest.preview` (added after step 6c: the `processSpecification`'s `approvalDisplayName` of an object change; no `target`) |
| ApprovalCertificationTest.approvalActionConditionSeesTheTargetAndTheFocus | approval action condition | C `ApprovalTest.preview` (condition with target); the object-rule case N as above |
| ApprovalCertificationTest.objectDisplayInformation... (2) | 4.0 name vs 4.4+ descriptive name | P `ApprovalDetailsTest.displayNameWithTheObjectDisplayInformation` |
| ApprovalCertificationTest.reviewerExpressionSeesTheCase | reviewer expression | C `CertificationTest.campaign` |
| ApprovalCertificationTest.itemSelectionSeesTheAssignmentAndTheUser | item selection | C `CertificationTest.campaign`; the org case (no `user`) N: needs a campaign over orgs |
| ConfigScanTest (5) | Groovy compile of each place, places/paths, `-Dscan.dir` | C `tester/static` `StaticCheckTest` (`compileErrorsPerPlace`, `testerFixtures`, `configurationOfStaticDir`); 4.8 only, the per-line `SpringApplicationContextHolder` compile failure is checked at run time by `SampleConfigTest.organizationalUnitThroughASpringBean` |
| ConfigurationExpressionsTest.iterationTokenIsTwoDigits, reservedTokenIsSkipped | token expression, pre-iteration condition | P `SynchronizationTest.collidingNamesGetAnIterationToken` |
| ConfigurationExpressionsTest.serviceAccountsAreNotSynchronized | synchronization condition | P `SynchronizationTest.serviceAccountsAreNotSynchronized` |
| ConfigurationExpressionsTest.ownerIsFoundByEmployeeNumber, candidateIsConfirmedByLastName | correlation, confirmation | P `SynchronizationTest.correlationLinksTheConfirmedOwner` |
| ConfigurationExpressionsTest.singleCandidateIs... (2) | 4.0 does not confirm a single candidate | P `SynchronizationTest.singleCandidateMustBeConfirmed` |
| ConfigurationExpressionsTest.confirmationMustReturnOneValue | a confirmation returning null fails | P `MisconfigurationTest.confirmationMustReturnOneValue` (all lines; the message differs per line, the cause is "Expression returned no value"; 4.0 reports it in the import's result only) |
| ConfigurationExpressionsTest.reactionConditionsExistSince44 | reaction conditions | P `SynchronizationTest.unmatchedContractorIsAddedOnlyWithoutReactionCondition` |
| ConfigurationExpressionsTest.passwordMustNotContainTheUserName | value policy check with `object` | P `ScriptTest.validateValueOfAnObject` |
| ConfigurationExpressionsTest.costCenterIsRequired | policy constraint expression | C `ChangeTest.policyRuleConstraintAndTryExecute` |
| ConfigurationExpressionsTest.welcomeMailOnlyForNewUsers | notifier filter, recipient, subject | C `HookAndNotificationTest.notificationsPerRunAndPerTest` |
| ConfigurationExpressionsTest.filterHasNoTransportName | no `transportName` in a notifier filter | P assertion in `HookAndNotificationTest.notificationsPerRunAndPerTest` |
| EvaluatorFingerprintTest | scanner fingerprint = prism's form | C `CoverageTest.placesByIdentity` and the coverage report ("evaluated but not listed" is empty) |
| FilterExpressionTest.inInducementsFocusIsTheRole | targetRef filter: `focus` is the role | P `SearchTest.filtersOfARolesInducements`, `VariablesTest.targetFilterOfAnInducement` |
| FilterExpressionTest.inAssignmentsFocusIsTheUser | assignment targetRef filter | C `RecorderTest.nestedFilterExpression` (template assignment search on the user) |
| FilterExpressionTest.inConstructionsFocusIsTheUser | construction resourceRef filter | P `SearchTest.filtersOfARolesInducements` |
| FilterExpressionTest.authorizationsSeeOnlyTheSubject | authorization filter variables | P assertions in `AccessTest.searchAsUser` |
| FilterExpressionTest.authorizationsWithoutSubjectFailOn44 | 4.4 NPE without a principal | N: not reachable in production (an anonymous request has only `ROLE_ANONYMOUS`, `runPrivileged` only the unrestricted authorization: no filter is evaluated without a principal); authorizations without a logged-in user are those of a task's owner: P `AccessTest.taskSearchesWithItsOwnersAuthorizations` (all lines) |
| FilterExpressionTest.taskQueriesSeeOnlyTheConfiguration | task query filter | P `SearchTest.taskQuerySeesTheConfiguration` (4.4+; 4.0's task format not ported) |
| FilterExpressionTest.collectionsHaveNoVariables | collection filter without `focus` | P `MisconfigurationTest.collectionFilterHasNoFocus` (a dashboard widget on the collection; all lines, the same message) |
| FunctionLibraryAndBulkActionTest.scriptsCallTheLibraryInTheStore | library from a script | C `ScriptTest.scriptWithLibraries` |
| FunctionLibraryAndBulkActionTest.mappingsCallTheLibraryToo | library from a mapping | P `SearchTest.functionEvaluatorAndScriptCallTheLibrary` |
| FunctionLibraryAndBulkActionTest.theFunctionEvaluatorCallsTheLibraryWithItsArguments | function evaluator, zero set | P `SearchTest.functionEvaluatorAndScriptCallTheLibrary` |
| FunctionLibraryAndBulkActionTest.theFunctionEvaluatorNeedsTheLibrary... (2) | missing library: 4.0/4.4 not found, 4.8+ configuration error | P `SearchTest.functionOfAMissingLibrary` |
| FunctionLibraryAndBulkActionTest.bulkActionScriptOfTheTask | `execute-script` of a task | C `ScriptTest.bulkAction` (the bulk action itself; the 4.0 task wrapper not ported) |
| LifecycleTest (3) | transitions, purge, no `focus` in conditions | P `LifecycleTest.lifecycleFollowsTheStateModel` |
| NewFormatSynchronizationTest.serviceAccountsAreNotClassified | classification condition | P `SynchronizationTest.serviceAccountsAreNotSynchronized` (4.8 format) |
| NewFormatSynchronizationTest.onlyStaffIsAddedWhenUnmatched | reaction condition (4.8 format) | P `SynchronizationTest.unmatchedContractorIsAddedOnlyWithoutReactionCondition` |
| NewFormatSynchronizationTest.filterCorrelatorFindsEmployeesOnly | filter correlator with a focus archetype | P `CorrelationTest.correlatorsSearchOnlyUsersOfTheFocusArchetype` (4.8+, on an `items` correlator: midPoint adds the archetype to the candidate query of both kinds) |
| NewFormatSynchronizationTest.singleCandidateIsConfirmed | filter correlator confirmation | P `SynchronizationTest.singleCandidateMustBeConfirmed` |
| NewFormatSynchronizationTest.itemsCorrelatorMatchesThePreFocus | `items` correlator | P `CorrelationTest.aCandidateBelowTheDefiniteThresholdOpensACase` (normalized names, confidence, a correlation case), `aCorrelatorWithoutValuesForItsItemsDoesNotApply` (4.8+; a fixture of its own, `correlation/`) |
| NewFormatSynchronizationTest.correlatorsAreFoundByName | old API: correlator lookup | N: old layer only |
| ParameterizedOnlyTest | recording of parameterized tests (old layer); blank last name | N: old layer only; the blank value is P in `SampleConfigTest.inboundLastNameIsNormalized` |
| ProjectionAndRoleMappingsTest.accountIsDisabledWhenNoLongerLegal | `legal` in an activation outbound | P `SynchronizationTest.costCenterAssignsTheRoleAutomatically` |
| ProjectionAndRoleMappingsTest.accountFollowsTheUsersStatusWhileLegal | administrative status outbound | P `SynchronizationTest.accountFollowsTheUsersStatus` |
| ProjectionAndRoleMappingsTest.accountIsKeptWhileTheUserExists | existence mapping | P `SynchronizationTest.costCenterAssignsTheRoleAutomatically` |
| ProjectionAndRoleMappingsTest.validToIsCopied | validTo outbound | C `PasswordAndValidityTest.accountIsValidUntilADayAfterTheUser` (the mirror got validity dates after step 6c) |
| ProjectionAndRoleMappingsTest.passwordIsCopied | password outbound | P `ResourceSchemaTest.activationAndPasswordFromSchemaHandling` |
| ProjectionAndRoleMappingsTest.directoryMayDisableButNotEnable | activation inbound with a condition | P `SynchronizationTest.directoryMayDisableButNotEnable` (new `TargetSystem.setEnabled`) |
| ProjectionAndRoleMappingsTest.initialPasswordComesFromTheResource | password inbound | P `PasswordReadingTest.initialPasswordIsTheHrAccounts` (all lines, on the mirror and the real CSV connector: the mirror returns passwords as the real connector does) |
| ProjectionAndRoleMappingsTest.constructionAttributeUsesTheRole | construction attribute with `source` | P `SynchronizationTest.costCenterAssignsTheRoleAutomatically` (ri:description) |
| ProjectionAndRoleMappingsTest.constructionHas(No)Iteration... (2) | `iteration` in a construction: 4.0 none | P `VariablesTest.constructionInAnInducement` |
| ProjectionAndRoleMappingsTest.focusMappingOfTheInducement | focus mapping of a role | P `SynchronizationTest.costCenterAssignsTheRoleAutomatically` (organization) |
| ProjectionAndRoleMappingsTest.autoassignedWhenTheCostCenterMovesToIt | autoassign mapping | P `SynchronizationTest.costCenterAssignsTheRoleAutomatically` |
| ProportionalExpressionTest (3) | proportional evaluator, no domain | P `ReportTest.dashboardProportionalNumbers` |
| SampleConfigTest.inboundLastNameIsNormalized (3 cases) | inbound normalization, PolyString conversion | P `SampleConfigTest.inboundLastNameIsNormalized` |
| SampleConfigTest.inboundChangeProducesPlusAndMinus | old/new values of an inbound | P `SampleConfigTest.inboundAfterAChangeInHr` (expectation changed, see findings) |
| SampleConfigTest.outboundMailUsesParentOrgDomain, outboundMailOnRename | outbound with `midpoint.getObject`, rename | P `SampleConfigTest.outboundMailFromTheParentOrg` |
| SampleConfigTest.unstubbedMidpointCallFailsLoudly, stubbedMidpointCall, springBeanCanBeReplacedByMock | stubbing of the old engine | N: old layer only (real midPoint has the real functions and beans) |
| SampleConfigTest.inboundTargetWithFocusVariable, templateWithUserVariablePaths | `$focus`/`$user` paths | P `SampleConfigTest.inboundLastNameIsNormalized`, `templateFullNameIsFamilyNameFirst` (fixture paths) |
| SampleConfigTest.templateFullNameIsFamilyNameFirst | template mapping | P same name |
| SampleConfigTest.templateOrgUnitViaSpringRepositoryBean | Spring bean from a template | P `SampleConfigTest.organizationalUnitThroughASpringBean` (all lines, holder package per line) |
| SampleConfigTest.inducementConditionTurnsOnWhenCostCenterChangesToIt | inducement condition old/new | P `SampleConfigTest.inducementConditionTurnsOnWhenTheCostCenterMovesToIt` |
| SampleConfigTest.bulkActionScriptWithSpringBean | script with a Spring bean | P `SampleConfigTest.bulkActionScriptWithASpringBean` (all lines) |
| SchemaCheckTest (2) | prism/ninja schema check per line | C `tester/static` `StaticCheckTest.schema`, `upgradeItemsAtTheirElements` (4.8 only) |
| ScriptCoverageTest (4) | coverage of the old layer | C `CoverageTest.placesByIdentity`; "not evaluable" places in the coverage report |
| SearchExpressionTest.assignsTheOrgOfTheCostCenter, movingToAnotherCostCenterReplacesTheAssignment, noOrgNoAssignment | assignmentTargetSearch | P `SearchTest.assignmentTargetSearchFollowsTheCostCenter` |
| SearchExpressionTest.referenceSearchWithAScriptInTheFilter | referenceSearch | P `SearchTest.referenceSearchWithAScriptInTheFilter` |
| SearchExpressionTest.createOnDemandNeedsTheModel | old engine could not create | P the real behaviour: `SearchTest.createOnDemandCreatesTheOrg` |
| SpringHolder40Test, SpringHolder44Test (2 each) | the 4.0/4.4 holder package | P `SampleConfigTest.organizationalUnitThroughASpringBean`, `bulkActionScriptWithASpringBean` |
| VariableBindingsTest.outboundSource... / outboundHasNoSourceOn40 | resource outbound variables per line | P `VariablesTest.resourceOutbound` |
| VariableBindingsTest.outboundOfARoleIsRefused | old API guard | N: old layer only |
| VariableBindingsTest.constructionThisObjectIsTheRole, constructionPathVariablesOfAnInducement | construction variables | P `VariablesTest.constructionInAnInducement` (C `RecorderTest.outboundOfRoleConstruction`) |
| VariableBindingsTest.focusMappingPathVariablesOfAnInducement | focus mapping path variables | P `VariablesTest.focusMappingInAnInducement` |
| VariableBindingsTest.inducementConditionPathVariables | inducement condition variables | P `VariablesTest.conditionOfAnInducement` |
| VariableBindingsTest.focusAssignmentIsMadeWhenTheFocusHasNone | old engine preset | N: old layer only (a construction is always reached through an assignment) |
| VariableBindingsTest.magicAssignmentMergesTheExtensions | `assignment` merges extensions | P `ExtensionTest.assignmentVariableMergesTheExtensionsAlongThePath` (all lines; the shared tests run with an extension schema) |
| VariableBindingsTest.assignmentConditionOfTheFocusOwnAssignment | own assignment condition | P `VariablesTest.conditionOfTheFocusOwnAssignment` |
| VariableBindingsTest.inducementTargetFilterPathVariables | targetRef filter variables | P `VariablesTest.targetFilterOfAnInducement` |
| VariableBindingsTest.templateDeclaresAssignmentPath | template `assignmentPath` | P `VariablesTest.templateMapping` |
| VariableBindingsTest.inboundFocusIsTheCurrentOne | inbound `focus` | P `VariablesTest.inboundSeesTheCurrentFocus` (see findings) |
| VariableBindingsTest.reactionCondition... (2), correlation... (2) | `focus` without an owner: null before 4.8, empty pre-focus since | P `SynchronizationTest.unmatchedStaffIsAdded` |
| DashboardWidgetTest.numberMessageOfACollection | number message | C `ReportTest.dashboardWidget` |
| DashboardWidgetTest.numberMessageOfAProportionalValue | proportional message | P `ReportTest.dashboardProportionalNumbers` |
| DashboardWidgetTest.theLastVariationDecides | variations per line | C `ReportTest.dashboardVariation` |
| DashboardWidgetTest.objectAndStoredData | object and stored-data widgets | P `ReportTest.dashboardObjectAndStoredDataWidgets` (expectation changed, see findings) |
| DashboardWidgetTest.variablesDependOnTheSourceType | object widget has no `input` | P `ReportTest.dashboardObjectAndStoredDataWidgets` |
| ReportTest.objectsAreTheEmployeesOfTheCostCenter | parameter filter | C `ReportTest.exportWithParameter` |
| ReportTest.oneRowPerRecord, oneRowPerOrganizationSince48, subreportsRemoveRowsSince48 | rows, resultHandling | C `ReportTest.export` |
| ReportTest.conditionRemovesRows | condition | C `ReportTest.export` |
| ReportTest.singleColumnsAndSubreports | columns and subreports | C `ReportTest.export` |
| ReportTest.columnsHave(No)Locale... (2) | `locale` 4.10 only | P `ReportTest.exportVariablesPerLine` |
| ReportTest.reportFunctionsMustBeGiven | old engine fake | N: old layer only |
| ReportTest.conditionHasNoReportFunctions | no `report` in the condition | P `ReportTest.exportVariablesPerLine` |
| ReportTest.subreportsSeeEarlierSubreportsSince48, subreportsAreIndependentOn44 | chained vs independent subreports | P `ReportTest.exportVariablesPerLine` |
| ReportTest.emptySubreportIsNullSince48, ...EmptyCollectionOn44 | empty subreport | P `ReportTest.exportVariablesPerLine` |
| ReportTest.identifierColumnSince48, identifierColumnIsEmptyOn44 | `#` path | P `ReportTest.exportVariablesPerLine`, `identifierPath` (expectation changed, see findings) |
| ReportTest.severalParameterValuesFailOn44 | several parameter values | P `ReportTest.severalParameterValues` (the drivers give a `Collection` as several values; 4.4 fails with "Attempt to get single value from item ... with multiple values", not the old layer's "several values") |

## Deleting the old layers (step 6d)

`expression/` and `clockwork/` are deleted. Of `common/`, which both layers and the tester compiled in, the tester
used `ConfigScanner` and `EvaluatorFingerprint` (configuration index, expression hooks) and the static checks
`MidPointLine`; these moved to `tester/core/src/main/java` with their packages, so neither the line modules nor the
static module's compiler includes changed. `EvaluatorCoverage` and `ScriptCoverage` (the old coverage, replaced by
`EvaluatorHook` and `internal.Coverage`) are deleted. The root POM lists only `tester` and drops the old layers'
properties (`expression.dir`, `clockwork.dir`, `common.dir`, and `golden.dir`/`coverage.dir`/`scan.dir`/`golden.mode`
defaults pointing into them; the tester reads `-D` system properties with its own defaults).

CI (`.github/workflows/ci.yml`): `test` builds everything from the root (the four tester lines, 4.0 and 4.4 on JDK 11
through the toolchain, and the static checks) on JDK 17 and 21; `tester-golden` records, verifies and tampers on 4.8;
`tester-cross-line` records on 4.0, 4.4 and 4.8 and verifies 4.0 -> 4.4, 4.4 -> 4.8, 4.8 -> 4.10 with
`accepted-<a>-<b>.txt`, then checks that an unaccepted difference fails and an accepted one passes. The old layers'
`golden-roundtrip` job is gone.

## midPoint 4.9 (step 6e)

`tester/midpoint-4.9` (4.9.9) is `base-4.8` plus a copy of 4.10's `LineSupport` (only `LINE` differs), the release's
SQL (`config/sql/native`) and baseline objects (in 4.9 under `repo/system-init/src/main/resources/initial-objects`;
`config/initial-objects` holds only a README). The API probe shows 4.9 equal to 4.10 at every place the tester calls:
`getTargetItemName`, prism `mutator()` / `newPropertyDefinition`, `AccessCertificationService.openNextStage(campaign)`,
`ObjectUpgradeValidator()` with a record `ValidationItem`, and `ExtItemCache.nameToExtItem` (so 4.9 needs the same
cache clearing as 4.10). The static module `tester/static/midpoint-4.9` is 4.10's `StaticLine` likewise.

Most of what step 6a found on 4.10 is already 4.9: the shared tests' `atLeast(mp, "4.10")` gates moved to "4.9" where
the 4.9 sources and the test agree:

- Certification (`CertificationTest.campaign`): `ModelController.openNextStage` -> `createNextStageTask`.
- Inbound variables (`RecorderTest.inboundMapping`): `MappedSourceItem`, the `UserType` bean, `association`, `object`.
- Association types (`AssociationTypeTest`): `schemaHandling/associationType` reads and runs; `associationConstruction`
  is evaluable (`CoverageTest`). The class was gated to 4.10 ("4.9 not checked"); `linesBefore410RejectTheDefinition`
  is now `linesBefore49RejectTheDefinition`.
- `assignmentTargetSearch` minus set twice (`SearchTest.assignmentTargetSearchFollowsTheCostCenter`).
- Import of a filter with an expression fails (`VariablesTest`; `ModelImplUtils.resolveObjectsFromRef`).
- Preview (`previewChanges` is `executeChanges` in simulation, `ModelInteractionServiceImpl`): as 4.10, not gated.
- Static checks (`StaticCheckTest`): prism's local names, the 4.9 association evaluators; `<filter>` stays 4.10+.
- New pin: the value of a legacy association outbound is `ShadowAssociationValueType` on 4.9+
  (`EntitlementTest.associationFromLinkAddsTheMemberToTheGroupOfTheRole`); the accepted entry of 4.8 -> 4.10 named no
  test before.

4.9 sides with 4.8 at two places: report columns get no default locale (`ReportTest.exportVariablesPerLine`), and a
later false dashboard variation resets the display (`ReportTest.dashboardVariation`).

Golden chain: 4.8 -> 4.9 differs only in the association outbound values (`accepted-4.8-4.9.txt`), plus the unstable
order of the association inbound's filter evaluations (seen once in three runs); 4.9 -> 4.10 only in the `locale`
report column and that order (`accepted-4.9-4.10.txt`). Notes: `inputs` (`+association +object`), `old`, `text`.
4.8 -> 4.10 stays in CI: it compares the two ends of moving from the 4.8 LTS to 4.10 (configuration migrated
through 4.9 as the guides require; the pair is a comparison of results, not an upgrade path).

## Narrower accepted differences (step 6f)

The accepted files had grown wildcard entries: 4.0 -> 4.4 accepted the template's old-state outcome, the outbounds
of a deleted account and the reaction condition in every test (`*` as the test), 4.4 -> 4.8 the fixture format in
every test, and every pair from 4.4 on accepted the association inbound's filter as "unstable order". A real
regression at such a place would have been a note. Two behaviours moved into the comparison, the rest into entries
that name one test each.

**Unstable order** (`GoldenResults`). `EntitlementTest.associationFromLinkTakesOnlyTheGroupOfEachRole`: in the second
assignment's run, the association inbound is evaluated once (4.4+) and runs its `assignmentTargetSearch` filter once
per group (`developers`, `testers`), in an order that varies between runs. The final outcome per focus was the last
evaluation, so `jdoe`'s filter outcome was `new=["developers"]` or `new=["testers"]` (count 2, the other one under
`all`). The order of a place's evaluations matters between projector waves (old state, then new state), not between
the evaluations of one nested place within one evaluation of its mapping. The final outcomes of a place are now, per
focus, the outcomes of its evaluations within the last evaluation of their root (the outermost enclosing evaluation,
`Evaluation.parent()` up to null), as a set; a top-level place (a mapping) is its own root, so it keeps its last
evaluation. The filter's final outcomes are `developers` and `testers` on 4.4 to 4.10 (pinned in that test with
`GoldenResults.of`), and its entries left 4.4 -> 4.8, 4.8 -> 4.9, 4.9 -> 4.10 and 4.8 -> 4.10. Each pair was recorded
and verified three times without them.

The same rule changed two other nested places, both deterministic: the `associationTargetSearch` filter of
`EntitlementTest.associationTargetSearchFindsTheGroupComputedFromTheUser` is now the same on 4.0 and 4.4 (both
evaluate it for the old and the new cost center in one evaluation; its entry is gone), and the template's
`assignmentTargetSearch` filter (`SearchTest.assignmentTargetSearchFollowsTheCostCenter`) shows that 4.0's last
evaluation of the mapping evaluates it for the old cost center too, 4.4+ for the new one only (pinned there, one entry).

**Old state** (`option new-state`). 4.0 evaluates a mapping against the operation's old state and keeps that
evaluation last: an added user's `fullName` ends with `new=["John Doe"] removed=["null null"] | old=["null null"]`,
4.4+ with `new=["John Doe"]`; a condition that turns false ends with `condition true->false new=[] removed=[...]`, 4.4+
with `condition false->false no output`. The new values and the new condition are the same; only what is compared with
the old state differs. With the option, outcome sets equal by `GoldenResults.newState` are the note `old state`
(`GoldenTest.newStateOptionComparesWhatTheOutcomesLeave`). It holds for the whole pair: in 4.0 -> 4.4 a value only one
line removes is never a failure. That was already the effect of the `*` entry it replaces, which also matched a new
value that differs; the option does not. Accepted `changed` differences on 4.0 -> 4.4 went from 41 to 6, and 37
differences are `old state` notes.

**Moved places** (`option moved`). 4.4 -> 4.8 accepted the correlation, confirmation, sync condition and reaction
condition as missing in the `<synchronization>` of `fixtures-4.4` and new in the object type of `fixtures/`. With five
`moved` options these places are compared: all have the same outcomes, except the classification of an account
midPoint created itself, which 4.8 does not evaluate again at an import (4.4 evaluates the synchronization condition
at every import; pinned in `SynchronizationTest.directoryMayDisableButNotEnable`, one entry). Accepted `missing`/`new`
on 4.4 -> 4.8 went from 75 to 11.

**Entries.** `AcceptedDifferencesTest.everyEntryNamesItsClassAndTest` rejects a `*` in `<class>.<test>`; option
reasons must name a pinning test too. An entry also matches the invocations of a repeated test
(`ResourceRedefinitionTest.sameOidDifferentDefinitionAfterReset repetition 1 of 4`), and `#2` in a run key is no
longer read as a comment. The association outbound values of 4.9+ are one entry per test (15 tests).

| Pair | Entries (with `*` test) before | after (+ options) | Accepted failing kinds before | after | Notes before | after |
|---|---|---|---|---|---|---|
| 4.0 -> 4.4 | 17 (4) | 19 (0) + `new-state` | 77 | 42 (+37 `old state`) | 1477 | 1479 |
| 4.4 -> 4.8 | 10 (5) | 7 (0) + 5 `moved` | 83 | 18 | 160 | 129 |
| 4.8 -> 4.9 | 2 (1) | 15 (0) | 28 | 27 | 225 | 224 |
| 4.9 -> 4.10 | 2 (1) | 2 (0) | 2 | 2 | 42 | 42 |
| 4.8 -> 4.10 | 3 (2) | 17 (0) | 29 | 29 | 244 | 244 |

The note totals hardly move by design: most are `count` (4.0 -> 4.4: 610) and `inputs` (518), which differ between
lines in every test and are left as they are; 4.4 -> 4.8 has more `inputs` and `count` notes because the moved places
are compared now. What changed is what a note can hide: an accepted entry now covers one test, and the old-state and
format differences are compared instead of accepted.

## Files scripts read and write (step 6g)

Integrators' bulk actions, task scripts and hooks read input files and write output files under directories the
configuration names (`/opt/midpoint/var/import/hr.csv`), often through Java utilities (custom jars, Commons IO or CSV)
that build the path from a string and do the I/O themselves; reports write their exports. Tests have to provide the
inputs and assert the outputs without the real directories, isolated per test.

**Chosen: a Java agent instrumenting the constructors of `java.io.File` and `sun.nio.fs.UnixPath`** (`FileAgent`,
Byte Buddy 1.15.4, inlined `Advice` on exit). A `File`'s `path` field, or a `UnixPath`'s `path` bytes, under a mapped
directory is replaced by the physical path. Every `java.io` stream, reader, writer and `RandomAccessFile` constructs a
`File` from a name (JDK 11 and 21: `FileOutputStream(String)` is `this(new File(name))`, `FileWriter(String)` is
`new FileOutputStream(name)`...), and every `java.nio.file` operation works on a `UnixPath`, which `Path.of`,
`Paths.get`, `File.toPath`, `resolve`, `getParent`... all construct; so every code path of the JVM sees the mapped
directory, with no wrapper objects and no identity change (`path.getFileSystem() == FileSystems.getDefault()` still
holds, so `Path.toFile()` works). The advice calls `FileRemap`, a class injected into the bootstrap class path (JDK
classes cannot see the class path) whose table is plain strings and bytes: it never constructs a `File` or `Path`
itself (no recursion), costs a volatile read when nothing is mapped, and rewrites only absolute paths under a mapped
directory, never one under the tester's own `tester-files`. The agent is attached by `ByteBuddyAgent.install()`: the
instrumentation of a `-javaagent:byte-buddy-agent.jar` if given (the 4.8+ modules do, through
`dependency:properties`), else self-attachment, which works on JDK 11 (the 4.0 and 4.4 modules test it) and 17/21
(21 warns). `FileAgent.probe` checks both classes after installing; failing that, `mp.files().map` fails with the
reason. The fields used (`File.path`, `UnixPath.path`, `UnixPath.stringValue`) are the same in JDK 11 and 21 (javap).

Options, ranked:

1. **The agent above (chosen).** Covers Groovy and Java alike, the test's own code too. Limits: native code, child
   processes, `File`/`Path` objects constructed before the mapping, non-Unix `Path`s (Windows: `java.io` only), and
   code that prints or stores the physical path.
2. **A delegating default `FileSystemProvider`** (`-Djava.nio.file.spi.DefaultFileSystemProvider`, JDK 9+). It must be
   set on the JVM command line and must wrap `FileSystem` and `Path` (operations dispatch through
   `path.getFileSystem().provider()`, and `Path.toFile()` compares the file system with the default one), about 60
   methods; it does not cover `java.io.File`, so the agent would be needed anyway. Instrumenting the `UnixPath`
   constructor gives the same coverage of NIO without a startup flag or wrappers.
3. **A global Groovy AST transformation** (the first implementation of this step: `META-INF/services` transformation
   rewriting `new File`, `Paths.get`, `Path.of` and the opening constructors in every compiled script). Line-neutral and
   dependency-free, but blind to everything a script delegates to Java code, which is common; removed in favour of 1
   (one mechanism, no double rewriting).
4. **Configuration-level redirection.** midPoint writes report exports to `new File(midpoint.home, "export/")`
   (`ReportUtils.getExportDir()`; `ReportJasperCreateTaskHandler` on 4.0; no setting for another directory), and the
   tester sets `midpoint.home` to `<workDir>/midpoint-home`: watching that directory is enough. The CSV and mail
   support rewrite `filePath`, `redirectToFile` and `logToFile` at import the same way. Hard-coded paths in scripts and
   libraries are not configuration, so this complements 1.
5. **An in-memory NIO file system** (Jimfs, memoryfilesystem): only its own `Path`s; `java.io.File` cannot be backed by
   it (`toFile()` is unsupported). No JVM library comparable to Python's pyfakefs was found.

**Mapped directories.** `mp.files().map("/opt/midpoint/var")` maps the directory to
`<workDir>/tester-files/opt/midpoint/var` (`FileArea`, which publishes the table to `FileRemap`); the innermost wins,
matching is per path segment, a directory holding `<workDir>` cannot be mapped. `FileArea` handles logical paths as
strings: a `File` or `Path` of one would be redirected. Physical directories rather than an in-memory store, so that
native tools and a failed test's leftovers can be inspected.

**What a run wrote.** `MidPointImpl.runAs` reads the content of the mapped directories and of the export directory
before and after the driver call; created and changed files are `run.filesWritten()` (by logical path), deleted ones
`run.filesDeleted()`.

**Reset.** `FileArea.capture()`/`restore()` are part of `TesterRuntime.State`, like the target systems: the mapped
directories and every directory and file under `tester-files` and the export directory return to the snapshot after
`@BeforeAll` (input files written there stay; outputs, later mappings and report exports go), and to none at the
baseline.

**Unmapped paths are left alone.** Failing on any path outside mapped directories would catch a forgotten mapping, but
it would also break the JVM's own file access (class path, `midpoint.home`, the temporary directory) and scripts that
read real files on purpose. The tester only changes mapped directories; the README says to map every directory the
configuration uses.

## Build order (4.8)

1. Runtime: harness, pgmem, reset, mirror connector, Spring context with cases/workflow/certification/report.
2. Recorder: mapping-level attribution, structured records, `Run`/`Evaluation` selection API.
3. Drivers: import objects, add/modify/assign/recompute, import accounts, preview, approvals (preview, cases, work
   items), certification, reports, notifications, authorization searches, bulk actions and ad-hoc scripts on the
   production `ScriptExpressionFactory`.
4. Golden and coverage on the records, keyed by identity (not by file and line), evaluation counts apart.
5. Static checks module (done).
6. Port the sample tests, then the other lines (4.10 done, 6a; 4.4 and 4.0 done, 6b); port what the old layers test
   (6c, done); delete `expression/` and `clockwork/` (6d, done).
