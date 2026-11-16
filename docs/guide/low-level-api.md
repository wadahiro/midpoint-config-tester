# The low-level API (`t.midpoint()`)

The sections below describe the low-level API (`com.github.wadahiro.midpoint.tester`: `MidPoint`, `Run`,
`Evaluation`, `TargetSystem`...), the engine under `MidPointConfigTester`. A test reaches it with `t.midpoint()` (below
`mp`): to read an object, drive a call the `MidPointConfigTester` API has no verb for, import a resource with options
of its own, or select the evaluations of a run.

## Resets

Every test method starts from the configuration as loaded: what a test imported or changed is gone after it. A reset restores the database snapshot, midPoint's caches, the target systems, the configuration index and midPoint's
clock, and suspends background tasks still running after 10 seconds. It runs no task: Quartz is held until all of it is
restored, and every recurring task goes back to the schedule it had at the snapshot (its next run one interval after
its last run); recurring tasks held after `t.clock().advance` fire on their own again. Each test method starts logged in as
`administrator`, with no notifications.

## Drivers

All drivers run as the logged-in user (the administrator unless noted), return a `Run` (or an `Answer<T>` / result
object holding one) and throw `OperationFailed` when midPoint reports an error. A call that runs a task
(`runTask`, `reconcile`, `importAccounts`) fails also when the task's run ended with an error, e.g. a reconciliation
that failed on one account: the task's result is in the run's, and the message names the last object the task failed
on and why (midPoint's result only counts the failures; the task's statistics keep the last one).

| Driver | midPoint entry point |
|---|---|
| `addObject(object)`, `addObjectXml(xml)` | an ADD through the Clockwork; `run.oid()` is the new OID |
| `modify(type, nameOrOid, path, values...)`, `modifyAdd(...)`, `modifyDelete(...)` | replace / add / delete item values (plain strings accepted for polystrings) |
| `change(type, nameOrOid)` | a builder: `.replace/.add/.delete(path, values)`, `.assign/.unassign(target[, relation])`, `.options(ModelExecuteOptions)`, then `.execute()`, `.tryExecute()` or `.preview()` |
| `assign(type, focus, target[, relation])`, `unassign(...)` | assignments by target name or OID |
| `recompute(type, nameOrOid)`, `deleteObject(type, nameOrOid)` | recompute; delete with deprovisioning as configured |
| `merge(type, left, right, mergeConfiguration)` | `ModelService.mergeObjects` (the GUI's merge) with the system configuration's `mergeConfiguration` of that name: the right object is merged into the left one and deleted |
| `execute(delta[s][, options])` | `ModelService.executeChanges` (e.g. `ModelExecuteOptions.create().reconcile()`, raw) |
| `tryExecute(...)`, `attempt(() -> anyDriver)` | the same without throwing: `run.isSuccess()`, `run.status()`, `run.failure()` |
| `importAccount(resource, accountName)` | imports one account as the GUI's import action: inbounds, correlation, synchronization reactions, then what the focus change evaluates |
| `preview(delta[s])` | `ModelInteractionService.previewChanges`; the model context is in `run.modelContext()` |
| `waitForTasks()`, `runTask(nameOrOid)` | wait for background work; run a task now (once) and wait for it |
| `reconcile(resource[, kind, intent])`, `importAccounts(resource)` | a single-run reconciliation / import task of the resource's object type (default `account/default`) as the GUI starts one (an activity), run and waited for; a later call replaces and re-runs it |
| `liveSync(resource[, kind, intent])` | a single-run live synchronization task of the resource's object type, created and run by the first call (which only fetches the token, as in production), run again from its token by later calls; fails when the task's run fails (see [Live synchronization](#live-synchronization)) |
| `find(type, nameOrOid)`, `user(name)` | read the repository |
| `shadow(focus, resource[, kind, intent])`, `situation(focus, resource)` | the shadow a focus (user by name or OID) links on a resource, as the repository has it: `situation()`, `kind()`, `intent()`, `linked()`, `dead()`, `synchronizationTimestamp()`, `asObject()`; null when it links none there |
| `bean(type)`, `harness()` | escape hatches: a Spring bean of the running midPoint; the line's `AbstractModelIntegrationTest` instance |

Paths given by name (`"activation/administrativeStatus"`) are matched against the object definition; values are real
values, with strings accepted for polystrings and enumerations.

**Background work.** A driver call returns only when midPoint has no background work left: no task thread running,
no single-run task runnable now, no recurring task due now. Evaluations of tasks the call started (approved change
execution, certification and report tasks, an imported recurring task's first run) are in its run, with
`Evaluation.task()` set to the task's name. A call fails after `-Dtester.taskTimeout` seconds (60). A recurring task
that runs between driver calls is not recorded.

**Tasks run as in production.** A runnable task runs when it is imported, and the import waits for it: a single-run
task once, a recurring task that never ran at once (midPoint schedules its first run for now), then on its schedule.
A task added or changed through the Clockwork (`addObject`, `execute`, `change`, `modify`) runs the same way: Quartz
is held while the change writes it, so its run starts after the change. `mp.runTask(name)` waits for what is left of earlier background work, then runs the task exactly once ("Run now"; a
recurring task's schedule then continues from this run). Quartz fires on the real time, so within a test a recurring
task does not reach its next run by itself: move midPoint's clock and run what is due by then. Once the test has moved
the clock, recurring tasks no longer fire on their own until the reset, also those added or resumed later (one
imported after `advance` does not run in its import; it never ran, so `runDueTasks` runs it): only `runDueTasks` and
`runTask` start them, and what a run contains depends only on the test code. Before the first `advance` they fire on
the real time as in production (a short interval, or a per-minute cron, then runs between driver calls).

```java
t.clock().advance("PT1H").runDue();      // each recurring task whose next run has come, once (by midPoint's clock)
```

A cron task counts from when its trigger was made, by midPoint's clock, and from the test's start: by default the real
time, so a cron time of the day between the real time and where the test moves the clock is due (a test that moves to
tomorrow 1:00 runs today's 23:50 job when it runs at 23:00, not at 23:55). `t.clock().startAt(dateTime)` starts the test
at a fixed time instead (the clock stopped there): what is due then does not depend on when the test runs.

```java
LocalDate day = t.clock().today().plusDays(1);
t.clock().startAt(day.atTime(12, 0));                 // tomorrow at noon, whenever the test runs
t.clock().stopAt(day.atTime(23, 51)).runDue();       // the 23:50 job has run, once
```

### Scenarios: a source reconciled into a target

A scenario drives the tasks as deployed and changes the source between their runs; every run holds what the task
evaluated (`Evaluation.task()` is the task's name). The same scenario runs on every line, on mirrors and on the real
connectors:

```java
@BeforeAll
static void configure(MidPoint mp) {
    mp.importObject(new File(DIR, "object-template-user.xml"));
    mp.importObject(new File(DIR, "system-configuration.xml"));
    mp.importResource(new File(DIR, "resource-hr.xml"), ResourceOptions.defaults().realConnector());   // CSV
    mp.importResource(new File(DIR, "resource-ldap.xml"), ResourceOptions.defaults().realConnector()); // LDAP
    mp.importObject(new File(DIR, "roles.xml"));
    mp.importObject(new File(DIR, "task-reconcile-hr.xml"));   // runs once: HR is empty
    mp.ldap("LDAP").loadLdif(PEOPLE_AND_GROUPS);
}

@Test
void hrDrivesTheDirectoryThroughReconciliation(MidPoint mp) {
    TargetSystem hr = mp.target("HR"), ldap = mp.target("LDAP");
    hr.addAccount("1001", Map.of("firstName", "John", "lastName", " DOE ", "department", "Sales"));
    hr.addAccount("1002", Map.of("firstName", "Kate", "lastName", "Smith", "department", "IT"));
    mp.step("hire");
    Run hire = mp.runTask("Reconcile HR");                     // unmatched -> addFocus, template, LDAP account
    assertThat(mp.situation("e1001", "HR")).isEqualTo(SynchronizationSituationType.LINKED);
    assertThat(ldap.groupValues(SALES, "member")).contains(JOHN);
    // one task run evaluated each mapping for both users: forFocus selects one user's
    assertThat(hire.forFocus("e1001").mapping("hr-familyName").newValues()).containsExactly("Doe");
    assertThat(hire.forFocus("e1002").mapping("hr-familyName").newValues()).containsExactly("Smith");
    assertThat(hire.forFocus("e1001").outbound("LDAP", "ri:cn").newValues()).containsExactly("John Doe");
    assertThat(hire.mapping("hr-familyName").task()).isEqualTo("Reconcile HR");

    hr.modifyAccount("1001", "lastName", "Brown");
    hr.modifyAccount("1001", "department", "IT");
    mp.step("change");
    Run change = mp.runTask("Reconcile HR");                   // linked -> synchronize: role, ou and group change
    Evaluation cn = change.forFocus("e1001").mapping("ldap-cn");
    assertThat(cn.newValues()).containsExactly("John Brown");
    assertThat(cn.oldValues()).containsExactly("John Doe");

    hr.deleteAccount("1002");
    mp.step("leave");
    Run leave = mp.runTask("Reconcile HR");                    // deleted -> deleteFocus: the LDAP account goes
    assertThat(mp.user("e1002")).isNull();
    assertThat(leave.forFocus("e1002").evaluations()).isNotEmpty();  // a deleted user is still the focus
}
```

Without a deployed task, `mp.importAccounts("HR")` and `mp.reconcile("HR")` start one. The scenario's task is
recurring (hourly, as deployed): it runs once when imported and once with each `runTask`.

### Live synchronization

The same scenario driven by live synchronization (on the real connectors and on mirrors alike): a deployed live
synchronization task runs with `runTask`; without one, `mp.liveSync("HR")` creates a single-run task and runs it. As in
production, a task's first run only fetches the token: what the source holds then is no change, only what changes
after it is.

```java
hr.addAccount("1000", Map.of("firstName", "Ann", "lastName", "Early", "department", "Sales"));
Run deployed = mp.importObject(new File(DIR, "task-livesync-hr.xml"));  // the first run: the token only
assertThat(deployed.evaluations()).isEmpty();

hr.addAccount("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
Run hire = mp.runTask("Live sync HR");              // one change: unmatched -> addFocus, the LDAP account
assertThat(hire.forFocus("e1001").mapping("hr-familyName").task()).isEqualTo("Live sync HR");
assertThat(mp.user("e1000")).isNull();             // not changed since the token: not seen

mp.liveSync("LDAP");                               // the first call: the token only
ldap.modifyAccount(JOHN, "telephoneNumber", "+1 555 0100");
Run synced = mp.liveSync("LDAP");                  // the same task from its token: the inbound sets the phone
assertThat(synced.forFocus("e1001").inbound("LDAP", "ri:telephoneNumber").newValues()).containsExactly("+1 555 0100");
```

- Each run of the task is a run of its call, with `Evaluation.task()` set; a run without changes evaluates nothing.
- The token is kept where midPoint keeps it (the task's activity state) and restored
  with the database; the sources restore their side of it (the CSV connector's copies, the LDAP change log, the
  mirror's content at each token).
- A source shows midPoint's own changes too: a directory live synchronization after midPoint created accounts sees
  them (linked: synchronized).
- `mp.liveSync` reads the task's result after the run: a run that fails (midPoint then suspends the task) fails the
  call; the next call cannot run a suspended task.
- The query of a live synchronization work definition is not applied by midPoint (only resource, object class, kind
  and intent), so its expressions are never evaluated.

**Steps.** `mp.step("approve")` names the driver calls that follow (until the next step or the end of the test),
instead of the driver's label.

## Selecting evaluations

A `Run` holds every evaluation of one driver call, in the order they started (`run.evaluations()`; `topLevel()` for
those not nested in another).

```java
run.mapping("mail");                              // the mapping with this <name>
run.in("LDAP").mapping("mail");                   // narrowed by owner (name or OID)
run.inbound("HR", "ri:fullname");                 // also inbound(resource, kind, intent, attribute)
run.outbound("LDAP", "ri:mail");                  // also outbound(resource, kind, intent, attribute)
run.templateMapping("User template", "fullName"); // by name, else by target path
run.construction("Employee", "ri:title");         // outbound of a role's construction
run.mappings("mail");                             // every evaluation of every mapping with that name
run.select().identity("role:Mailer/inducement[construction=LDAP/account/default]/construction/attribute[ri:title]/outbound[title]").one();
run.select().within("role:Mailer/inducement[focusMappings=title]").mappings().one();  // this place and what is in it
run.select().within("stage", "number", "1").element("approverExpression"); // structure in midPoint's terms
run.select().attribute("ri:title").objectType("account", "default").inbounds().byIdentity();
run.select().owner("User template").evaluator("script").path("filter");
```

`Selection` filters: `focus`, `owner`, `identity`, `within(identity)`, `within(element, keyChild, value)`, `element`, `path`,
`attribute`, `objectType`, `inbounds`, `outbounds`, `mappings([name])`, `expressions`, `evaluator`, `part`
(`condition`, `expression`), `inTasks`, `failed`, `file`, `where(predicate)`. They end with `one()`, `single()`,
`first()`, `last()`, `list()`, `stream()` or `byIdentity()`.

**What an `Evaluation` holds**:

- `newValues()` (zero + plus) and `oldValues()` (zero + minus), `zeroSet()`, `plusSet()`, `minusSet()`, `hasOutput()`;
  polystrings as their `orig`.
- `conditionOld()`, `conditionNew()`, `error()`.
- `variables()`, `variable(name)` (fails if midPoint did not give that variable), `hasVariable(name)`, `sources()`
  (old and new values per source). These are the live objects midPoint gave, not copies.
- `isMapping()`, `mappingName()`, `target()`, `evaluator()`, `part()`, `expression()` and `condition()` of a mapping,
  `parent()`, `nested()`, `allNested()`: a filter's script is nested in the `assignmentTargetSearch` that is nested in its
  mapping.
- `owner()`, `identity()`, `location()` (file, line, element path, how it was found), `task()`, `hook()`, `focus()`
  (the focus object the evaluation is about, see below).
- `toString()` is the normalized text.

### One focus among several

A reconciliation or an import task evaluates the same mappings for every account it processes, and `run.mapping(name)`
gives the last evaluation of any of them. `run.forFocus(nameOrOid)` is the run seen for one focus object (by name or
OID): every selector above works on it, with the same ambiguity rules, and its messages name the focus.
`Selection.focus(nameOrOid)` filters the same way; `run.focuses()` lists the focus objects of a run.

```java
Run run = mp.runTask("Reconcile HR");
run.forFocus("e1001").mapping("hr-familyName");             // John's, whatever order the task processed them in
run.forFocus(johnOid).inbound("HR", "ri:lastName");         // by OID
run.forFocus("e1002").in("Dept IT").mapping("it-group");    // Kate's role construction
run.select().focus("e1001").inbounds().byIdentity();
```

`Evaluation.focus()` (type, OID, name) is decided for the outermost evaluation and inherited by everything nested in
it (a search filter's expression belongs to the focus of its mapping):

1. the object of its `focus` variable (of a delta: the object after the change, else the one before, so a user being
   deleted is still that user) when it has a name or an OID; the missing one of the two is looked up when the run
   ends;
2. else the owner of its `shadow` (`projection`, `account`) when the run ends: inbounds and correlation of an account
   imported or reconciled before its user exists belong to the user the account got linked to;
3. else none (an expression without focus or shadow, an account no user was created or linked for): such evaluations
   are in no `forFocus` view.

`forFocus` fails when no evaluation is about that focus, listing the focus objects of the run. In a view of a focus, the hints of an ambiguity error select within it (`select with run.forFocus("e1001").in("HR").mapping("hr-familyName")`). The rule gives the same
result on every line although the variables differ: 4.8 gives a correlation or reaction condition an empty pre-focus,
4.4 no `focus` (see [Line notes](lines.md#line-notes)); both go by the shadow's owner.

**Shadows.** `mp.shadow(focus, resource[, kind, intent])` reads the shadow the focus links on the resource from the
repository (no target system read): a live one before dead ones (kept linked as `related`), failing when several live
ones match without kind and intent. `mp.situation(focus, resource)` is its `situation()`. The five situations
(`LINKED`, `UNLINKED`, `UNMATCHED`, `DISPUTED`, `DELETED`), kind, intent, `dead` and the synchronization timestamps are
the same on every line; `shadow.asObject()` gives the rest (e.g. `getCorrelation()`, 4.8 and later).

### Identity, not line numbers

`Evaluation.identity()` names the evaluated element by its owner object and its path from the owner's root, each
element told apart by what midPoint tells it apart by:

```
resource:HR/schemaHandling/objectType[account/default]/attribute[ri:fullname]/inbound[hr-familyName]
resource:HR/schemaHandling/objectType[account/default]/correlation/correlators/filter/ownerFilter/equal/expression
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

Discriminators: `name`; the `ref` of attributes, associations and template items; kind/intent of resource object
types; `number=` of approval and certification stages; `identifier=` of widgets; `target=` of an unnamed mapping; the
`target=` (by name), `construction=` (resource/kind/intent) or `policyRule=` of an assignment or inducement;
`situation=` of a reaction. Siblings that these do not tell apart (no discriminator, or the same one) are told apart
by what else they have, used only among them: an inducement's `order=` (1 when not given; after its target:
`inducement[target=Employee, order=2]`), its `focusMappings=` (the mappings' names, or target paths of unnamed ones) or
`targetFilter=` (type and filtered paths: `org:costCenter`); a lifecycle transition's `targetState=`; a widget data
field's `fieldType=`; a check expression's `failureMessage=`; a dashboard variation's `condition=` (the condition's name
or description); a message argument's position (`argumentExpression[argument=1]`: its position is what it is); then a
display `label=`, a `description=` or a `documentation=` of any element (whitespace collapsed, cut after 40
characters). An element that no sibling shares its discriminator (or lack of one) with needs none. Only where nothing
tells siblings apart does an element get its position (`#2`, `identity.hasOrdinal()`): such an identity changes when
siblings are added or reordered, so give such elements a name or a description.

Line numbers change with every edit of a file, and the same mapping has different lines in the files of two lines'
formats, so they are not part of the identity and no selector takes them; `location()` keeps file and line for
display. How the place is found (`location().resolvedBy()`): `origin` (where midPoint says the mapping comes from),
`name` (the owner's only mapping with that name), or `fingerprint` (the canonical form of the expression, within the
nearest located enclosing evaluation; identical expressions are told apart by a hook's name, the dashboard widget the
driver evaluates, the name of the notifier whose filter is evaluated and midPoint's description of a populate
expression, and the ones left are listed in
`location().alternatives()`). A mapping whose owner is no indexed object (a user's own assignment given as text) is not
taken for an identical unnamed mapping elsewhere. An evaluation not found in the indexed files keeps its owner and
midPoint's position (`identity().isLocated()` false).

### Ambiguity

Selectors that return one `Evaluation` group the matches by identity. Several evaluations of one place (projector
waves, several assignment paths) give the last one, the final state. Two or more places fail with an `AssertionError`
that lists them and how to select each:

```
Mapping 'mail' in add user:...(jdoe) is ambiguous: 2 different places were evaluated:
  resource:CRM/schemaHandling/objectType[account/default]/attribute[ri:mail]/outbound[mail] (1 evaluation)
      select with run.in("CRM").mapping("mail")
  resource:LDAP/schemaHandling/objectType[account/default]/attribute[ri:mail]/outbound[mail] (1 evaluation)
      select with run.in("LDAP").mapping("mail")
All their evaluations: run.mappings("mail")
```

When both places are in one object the hint is `run.select().identity("...").one()`. midPoint accepts two mappings
with the same name in one object; indexing such a file logs a warning to `target/tester.log`, and the static checks
report it.
