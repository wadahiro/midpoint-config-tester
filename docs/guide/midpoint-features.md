# Approvals, reports, activation, scripts and the audit log

## Approvals, certification, reports, dashboards and time

```java
// approvals
Approvals.Preview p = mp.approvals().preview(delta);              // partialProcessing approvals=process; nothing stored
p.single().stage(1).expectedApprovers();                          // ["cc-owner", "IT department"]
p.run().select().within("stage", "number", "1").element("approverExpression");
mp.approvals().request(delta);                                    // also request(requester, delta)
List<Approvals.WorkItem> items = mp.approvals().workItems();      // assignees, candidates, additionalInformation
Run last = mp.approvals().approve(items.get(0), "cc-owner");      // reject, complete(item, user, approve, comment), delegate
last.mapping("privileged-description").task();                   // the approved change, executed in a task of this call
mp.approvals().cases();                                           // state, stage, outcome, work items
mp.approvals().forward(item, "cc-owner", "it-member");           // the GUI's Forward: replaces the assignees
mp.approvals().claim(orgItem, "it-member");                       // a candidate takes it; release(orgItem, "it-member")
mp.approvals().cancel(mp.approvals().cases().get(0));             // closed without an outcome, nothing executed

// time: midPoint's clock override, then the scanners as tasks
t.clock().advance("P6D");
t.clock().runTriggerScanner();                                    // deadlines, timed actions, escalation
t.clock().runValidityScanner();                                   // activation dates
t.clock().runDue();                                               // recurring tasks due by the clock

// certification
String c = mp.certification().createCampaign("role-assignments").oid();
mp.certification().openNextStage(c).select().element("reviewerExpression");
Certification.WorkItem w = mp.certification().campaign(c).caseOf("kate", "Auditor").workItems().get(0);
mp.certification().decide(w, "sec-officer", "revoke", null);     // closeCurrentStage, startRemediation, closeCampaign
mp.certification().reiterateCampaign(c);                          // a closed campaign: unanswered cases, iteration 2
t.clock().advance("P5D").runTriggerScanner();                    // a stage's timedActions: escalate, delegate

// reports: an export task as the GUI's "Run report"
Reports.Output out = mp.reports().export("users-by-cost-center", Map.of("costCenter", "HR-200"));
out.lines(); out.run().select().within("column", "name", "fullName");
mp.reports().export("users-of-cost-centers", Map.of("costCenter", List.of("HR-200", "IT-100")));  // several values

// report import: an import task as the GUI's "Import report", on CSV lines (or a file)
Reports.Import in = mp.reports().importFrom("import-users", List.of(
        "\"Name\";\"Full name\";\"Cost center\"",                     // skipped: columns go by view order
        "\"jsmith\";\"Jane Smith\";\"it-300\""));
in.status(); in.created(); in.modified();                         // PARTIAL_ERROR when some rows failed
in.run().select().within("column", "name", "costCenter");         // .../column[costCenter]/import/expression

// dashboards: DashboardService, as the dashboard page
Dashboards.Widget widget = mp.dashboards().widget("admin-dashboard", "employees");
widget.number(); widget.color(); widget.run().select().path("variation").element("condition");
```

**Time.** `t.clock()` moves midPoint's clock and the JVM's wall clock (`new Date()`, `Instant.now()`, Groovy's
`System.currentTimeMillis()`...), not Quartz, `System.nanoTime()` or the database. See
[Activation and validity](#activation-and-validity). The moved clock runs on; `t.clock().stopAt(instant)` (or a
`LocalDateTime` in the deployment's time zone, `tester.timeZone`: `t.clock().zone()`, as `today()` and
`advanceTo(LocalDate)`) stops it there, to the millisecond, until the test ends, for a time window
checked at its bounds (a script that compares `LocalTime.now()` with 22:00): `t.clock().stopAt(day.atTime(21, 59, 59,
999_000_000))`, then `advance(Duration.ofMillis(1))` to 22:00:00.000.

Users who complete work items or decide need the authorization to: the baseline has midPoint's Approver and Reviewer
roles to assign them. The acting user is given by name; the call runs logged in as that user, in a task owned by them.

**Approvals of an object change** (an object rule, e.g. a policy rule in the inducement of a role the object has,
with a `modification` constraint and an approval action) work the same: `preview`, `request`,
the cases and work items. Their expressions get `object` as it was before the change and no `target`. A
`processSpecification` with `deltaFrom` takes only those items into the case; the rest of the change is a case of its
own (`Changes that do not require approval`, in `cases()`), executed together with the approved part when the
approvals end. Its `approvalDisplayName` names the case, before the action's, which is then not evaluated.

**Approval names and timed actions.** Without an `approvalDisplayName` (of the process specification, then of the
action), a case is named by the short message of the constraint that triggered (`presentation/shortMessage`; the
`longMessage` is never evaluated). A process specification's display name gets `object` after the change, the
approver expressions before it. The timed actions of an approval stage (`delegate`,
`escalate`, `complete`, `notify`) run when their trigger is due: move the clock and run the trigger scanner. Their
conditions are not implemented by midPoint: never evaluated, the actions run regardless.

**Certification stages, iterations and timed actions.** Each stage's `reviewerExpression` runs when that stage opens,
for the cases still under review (with the default outcome strategy a case revoked in a stage is not reviewed in the
next one). `reiterateCampaign` on a closed campaign puts the cases without a response into the next iteration; the
next `openNextStage` evaluates their reviewers again, and the driver labels of that iteration name it (`open stage 1
of role-assignments iteration 2`). Certification work items are delegated and escalated only by a stage's
`timedActions` (midPoint has no service call for it): move the clock and run the trigger scanner. A stage's deadline
is rounded to the end of its day (`deadlineRounding` `day`), and the stage closes at its deadline in the same scan,
so a timed action is easiest to test before the deadline (`time/value` negative).

**Report parameters with several values.** A `Collection` as a parameter value gives the parameter several values,
as a report task's `reportParam` may hold them (the GUI gives one). 4.8+ pass them to the expressions as a list (a
script filter returning it matches any of them; a `path` expression of the parameter fails on the list: "Unexpected
variable value"); 4.4 fails the export ("Attempt to get single value from item ... with multiple values"). A failed export task throws `OperationFailed` with the first message of the
task's result tree.

**Report import** (`importFrom`) needs a report with `behavior/direction` `import`. `importFrom(report, lines,
username)` imports as that user, as when the user imports in the GUI: the import task is theirs, so the import
script's `actor` is the user and their authorizations apply. The lines are written to
`<midPoint home>/import/`, a `ReportDataType` points to the file, and the classic report import task runs
(`ReportManager.importReport` on 4.4; the same activity submitted on 4.8+). midPoint then:

- with an `objectCollection`: makes one object of the view's type per row and imports it with
  `ModelService.importObject` and `behavior/importOptions` (e.g. `overwrite`). The header row is skipped: columns are
  taken by position in the view (`previousColumn` order, the collection's `defaultView` columns first), not by label. A
  column's `import/expression` gets the cell as `input` (a list when it holds the multi-value delimiter `,`); a null
  result leaves the item out;
- without one: runs `behavior/importScript` (a bulk action) per row, with one variable per header name.

A failing row does not stop the import: `status()` is then `PARTIAL_ERROR`. The driver throws `OperationFailed` only
when the task fails as a whole (e.g. a report whose direction is not `import`), unlike `export`, which throws on any
status but success. `created()` and `modified()` are the focus objects the import added or changed. The CSV format is
the report's `fileFormat` (default: `;`, `"`, a header row).

## Activation and validity

A resource provisions the status of its accounts only through the activation section of its object type
(`schemaHandling/objectType/activation`), and only with an activation capability (native, or simulated):

- **Status** (`administrativeStatus/outbound`). An empty mapping is `asIs` of the user's `effectiveStatus` (the
  administrative status with validity applied). With native `validFrom` and `validTo` capabilities its input is the
  user's `administrativeStatus` instead: the target system ends the account by its dates, which the
  `validFrom`/`validTo` outbounds send. Without an
  `administrativeStatus` outbound midPoint does not manage the status: a disabled user keeps an enabled account.
- **Existence** (`existence/outbound`). Without one an account exists while it is legal (a valid assignment gives it)
  and is deleted otherwise. *Disable instead of delete*: a weak `$focusExists` existence mapping and a **strong** status
  mapping `legal ? input : DISABLED`. It must be strong: when the validity scanner recomputes a user whose assignment
  ended, `legal` is false before and after, so DISABLED is in the zero set, which a normal mapping does not apply. *Delayed delete*: an existence mapping returning
  `false` with `timeFrom` = `$shadow/activation/disableTimestamp` + an offset and a condition on the shadow's
  `disableReason` (`#deprovision`); midPoint sets a recompute trigger on the shadow for that time, and the trigger
  scanner deletes the account.
- **Simulated status** (`capabilities/configured/activation/status`: `attribute`, `enableValue`, `disableValue`), e.g.
  a CSV column or an LDAP attribute. midPoint keeps the attribute out of the shadow (`ignoreAttribute`, default true):
  assert on the target system, `TargetSystem.enabled(account)` reads it as midPoint does, on a mirror and on the real
  CSV and LDAP connectors.

Time passes with midPoint's clock; the scanners do what midPoint's recurring tasks would:

```java
Instant end = t.clock().now().plus(Duration.ofDays(1));          // dates relative to midPoint's clock
mp.addObjectXml("<user ...><name>bob</name>...<activation><validTo>" + end + "</validTo></activation></user>");
t.clock().advance("P2D");                     // nothing recomputes the user yet: effectiveStatus is still ENABLED
t.clock().runValidityScanner();               // users and assignments whose validFrom / validTo passed, recomputed
mp.user("bob").getActivation().getEffectiveStatus();             // DISABLED, validityStatus AFTER
mp.target("App").enabled("bob");                                 // false
t.clock().advance("P31D").runTriggerScanner(); // triggers due: the recompute trigger of a delayed delete
```

The validity scanner handles the dates of users and of their assignments (an assignment of a role that ended takes the
role's accounts away as each resource deprovisions: deleted, or disabled); the trigger scanner handles what a time
constraint of a mapping (`timeFrom`, `timeTo`) waits for. All of the above (native and simulated status, user and
assignment validity, disable instead of delete, delayed delete) runs on mirrors and on the real CSV and LDAP
connectors, with the same results on every line but one note (see [Line notes](lines.md#line-notes)).

**What moves with `t.clock()`.** `advance` moves midPoint's `Clock` (its test override) and, by the same offset, the
JVM's wall clock as code reads it, so a script sees the advanced time however it reads it; the reset after each test
sets both back to the real time. With the tester's Java agent (the one of [Files scripts read and
write](files.md#files-scripts-read-and-write), attached or given with `-javaagent`), on every line and JDK (11, 17, 21):

| Moves | Does not move |
|---|---|
| midPoint's `Clock`: the validity and trigger scanners, time constraints of mappings, `basic.currentDateTime()`, the timestamps midPoint writes from it (`disableTimestamp`, `archiveTimestamp`, triggers, metadata) | `System.nanoTime()` (monotonic: the tester's own waits use it, so a driver call after `advance("P30D")` does not time out) |
| `new Date()`, `Calendar.getInstance()`, `new GregorianCalendar()` | `System.currentTimeMillis()` called from **Java** code (a native method of a class loaded before any agent: it cannot be redefined). Custom Java libraries, and midPoint's own code where it does not use its `Clock`: the audit's timestamps (the audit service fills them from it, or from the lightweight identifier's, also real; on every line) and the lightweight identifiers of tasks and operations |
| `java.time`'s system clock: `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`, `ZonedDateTime.now()`, `Clock.systemUTC()`... | Quartz (it keeps the real time, see below) |
| Groovy's `System.currentTimeMillis()` (`System`'s meta class: dynamic Groovy, not `@CompileStatic`) | the database (`now()` in PostgreSQL; midPoint writes its timestamps from its `Clock`), connectors' and other processes' time |
| `basic.fromNow(...)` and `XmlTypeConverter.createXMLGregorianCalendar()` (prism's `System.currentTimeMillis()` calls, replaced) | |

At start the tester checks every source on this JVM; if one is not shifted (or there is no agent), it says why, and
only midPoint's `Clock` moves.

So after `advance` an audit record carries the real time while the clock says the shifted one: compare audit
timestamps with each other, or put the rows a test reads with the time it needs (a database the configuration
reads). midPoint 4.0's lightweight identifiers fail when the real time moves back by a millisecond (an NTP
adjustment of the host); the tester gives them the allowance of 4.4+ (a step back of up to 10 seconds).

**Quartz keeps the real time.** midPoint's scheduler goes on firing on the real time, so from the first `advance`
until the reset the tester holds the regular runs of loosely bound recurring tasks: a task with a short interval does
not run by itself in the middle of `runValidityScanner()` or between driver calls; `t.clock().runDue()` runs once each
task due by midPoint's clock, and `mp.runTask` still runs a task exactly once. Tightly bound tasks (a thread that sleeps between runs) are not held. In production the real time passes for
everything at once; that difference is the point of the tester's clock. Log lines written after an advance carry the shifted time.

**Quartz keeps its jobs in memory.** The tester starts midPoint with the in-memory job store, midPoint's default on
one node; a cluster (`taskManager.clustered`) keeps them in the database by default (the JDBC job store). After a reset
every recurring task is back on the schedule of the restored task. What a test cannot see of a deployment on the JDBC
store:
the misfire threshold (60 s; the due tasks apply none, a test moves the clock further), the runs a node that was down
missed (they misfire when it starts), and the runs another node of the cluster takes.

Scripts may read time either way. Only where midPoint's clock moves alone (midPoint's own clock override, outside the
tester, or a JVM without the agent) does a script on the JVM's clock disagree with the scanners: the static check
notes such reads (`LINT`, rule `jdk-time`, a warning); `basic.currentDateTime()` and
`basic.addDuration(basic.currentDateTime(), 'P30D')` (every line has both) always follow midPoint's `Clock`.

## Scripts, value policies and bulk actions

`mp.script(code)` is **the only driver whose variables are given by the test**. It evaluates a `<script>` expression
through midPoint's production `ExpressionFactory`, with the real `midpoint`, `basic` and `log` libraries, every custom
function library in the repository and the full expression profile. Use it for library functions and snippets, not to
stand in for a mapping, a hook or a policy: drive midPoint so that it evaluates those with the variables it binds.

```java
Answer<List<Object>> out = mp.script("custom.execute('initials', [name: who])")
        .variable("who", "John Doe")                 // also variable(name, value, type), language(uri), returns(QName)
        .evaluate();
assertThat(out.value()).containsExactly("JD");
Evaluation function = out.run().select().owner("custom").single();   // the library function, nested

ValueCheck check = mp.validateValue("Password policy", "secret1");  // checkExpressions evaluated and recorded
check.isValid(); check.failed(); check.limitations();
Answer<String> generated = mp.generateValue("Password policy");     // random value; evaluations recorded

Answer<BulkActionOutput> bulk = mp.bulkAction(executeScriptXml);    // console(), data(); execute-script scripts recorded
```

A library function is a function of its variables, so a property over many values is cheap to state (no state
changes, nothing to restore): with `io.kotest:kotest-property-jvm` added as a test dependency, `checkAll` gives it
values the examples do not have (empty, blanks, letters outside ASCII), and shrinks a failing one to the smallest.

```kotlin
"initials are upper case for any two-word name" {
    checkAll(Arb.string(1..10, Codepoint.az()), Arb.string(1..10, Codepoint.az())) { first, last ->
        t.midpoint().script("custom.execute('initials', [name: who])").variable("who", "$first $last").evaluate()
            .value() shouldBe listOf("${first[0]}${last[0]}".uppercase())
    }
}
```

Scripts midPoint runs without an `ExpressionFactory` (model hooks, `execute-script` actions, report columns and
subreports) are recorded too, and located by their canonical form. The script of an `execute-script` action is the
`value` of its `script` parameter, read as the `<script>` midPoint makes of it: its identity is
`.../action/parameter[script]`.

## Logs

`t.logs()` is what the configuration's scripts logged in the test through `log` (`log.info`, `log.warn`,
`log.error`: INFO and up, with their arguments filled in and the exception logged with them), narrowed like mails:

```java
t.logs().info().containing("welcome mail").exactly(1);
t.logs().errors().none();
assertThat(t.logs().messages()).containsExactly("welcome mail for jdoe");
```

Scripts' `log` is midPoint's `com.evolveum.midpoint.expression` logger; a custom library's loggers are captured too
when the manifest names them (`logs: { capture: [com.example.idm] }`). The capture starts again before every test
(midPoint resets logback when it applies the system configuration's logging); those loggers then also write INFO to
`target/tester.log`.

## Audit log

midPoint audits to its repository database, as in production (the native repository's audit; the database is in
memory): the deployment's own searches of the audit log (audit reports, dashboards, scripts)
work. `t.audit()` is what midPoint wrote to it in the test, `t.audit(() -> ...)` what it wrote while the code ran
(and the work the code waited for): the request and execution records of each change, with the target, initiator,
channel, task, outcome and deltas, narrowed like logs. The fields are those of a row of the audit table
(`ma_audit_event`), by name rather than by number: what an audit report, or another system that reads the table,
would see.

```java
AuditRecord deletion = t.audit().ofType("DELETE_OBJECT").execution().targetType(UserType.class).target("jdoe").single();
assertThat(deletion.outcome()).isEqualTo("FATAL_ERROR");   // the account could not be deleted; the user is gone
t.audit(() -> t.task("Delete users").run()).ofType("DELETE_OBJECT").execution().exactly(2);
t.audit().ofType("MODIFY_OBJECT").initiator("jdoe").none();
```

The audit table goes back with the rest of the database before every test.

Another system that reads midPoint's database over JDBC (a reporting tool that queries the audit table) sees the
line's own tables and codes (the event type and stage as names in `ma_audit_event`). `t.repositoryDatabase()` runs that system's query on midPoint's database, so the test fails
when a midPoint upgrade changes what it reads (read it only: a row written there bypasses midPoint):

```java
List<Map<String, Object>> rows = t.repositoryDatabase().rows(
        "SELECT targetName, outcome FROM ma_audit_event WHERE eventType = 'DELETE_OBJECT' AND eventStage = 'EXECUTION'"
                + " AND targetOid = ?::uuid", oid);
```
