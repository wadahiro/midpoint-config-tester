# Writing tests with JUnit

A test is a JUnit 5 class annotated `@MidPointConfigTest`, its test methods taking a `MidPointConfigTester` (the API
in `com.github.wadahiro.midpoint.configtester`). The same API runs in Kotlin as Kotest specs:
[Writing tests with Kotest](writing-tests-kotest.md). The API is the same on every line; the examples are on 4.8, the
latest LTS line. A test class names the manifest ([Setting up a config repository](setup.md)); every test starts from
the configuration as loaded.

```java
@MidPointConfigTest("src/test/resources/midpoint-config-tester.yaml")
class HrLifecycleTest {

    @Test
    void joinerMoverLeaver(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
        t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").isEnabled().hasAccountOn("LDAP");
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com")
                .hasAttribute("ou", "Sales").memberOf("cn=sales,ou=groups,dc=example,dc=com").isEnabled();
        t.mails().to("e1001@example.com").withSubject("Welcome").exactly(1);

        t.source("HR").modify("1001", "department", "IT");
        t.task("Reconcile HR").run();
        t.user("e1001").hasAssignment("Dept IT").hasNoAssignment("Dept Sales");

        t.source("HR").delete("1001");
        t.task("Reconcile HR").run();
        t.user("e1001").doesNotExist();
    }

    @Test
    void futureJoiner(MidPointConfigTester t) {
        LocalDate start = t.clock().today().plusDays(10);
        t.source("HR").add("1002", Map.of("firstName", "Kate", "lastName", "Smith", "department", "IT",
                "startDate", start.toString()));
        t.task("Reconcile HR").run();
        t.user("e1002").isDisabled().hasNoAccountOn("LDAP");

        t.clock().advanceTo(start).advance("PT1H").runDue();     // validity and trigger scanners, due tasks
        t.user("e1002").isEnabled().hasAssignment("Dept IT").hasAccountOn("LDAP");
    }
}
```

Requests, approvals, certification, reports and refusals, by name:

```java
t.actor("jack").request().assign("Privileged Access");             // as jack, through his End user role
Case request = t.cases().forUser("jack").forRole("Privileged Access").single();
request.isOpen().atStage(1);
t.workItems().assignedTo("role-owner").single().approve(t.actor("role-owner"), "ok");
WorkItem officer = t.workItems().assignedTo("sec-officer").single();   // each Security officer has one
WorkItem analyst = t.workItems().assignedTo("sec-analyst").single();
officer.approve(t.actor("sec-officer"));                           // also reject, delegate, forward, claim, release
analyst.isCancelled();                                             // firstDecides: closed without a decision
request.isApproved();
t.user("jack").hasAssignment("Privileged Access");

t.expectFailure(() -> t.user("ann").assign("Auditor")).isPolicyViolation();   // excluded by Accountant

Campaign c = t.certification("Role assignments").start();
c.reviews().forUser("jack").forRole("Auditor").single().revoke(t.actor("sec-officer"), "left the audit");
c.closeStage();
c.remediate();
t.user("jack").hasNoAssignment("Auditor");

ReportOutput out = t.report("Users by cost center").run(Map.of("costCenter", "IT"));
out.hasRows(2).hasRowWith("User", "jack");
```

## Three rules, and the shapes of the calls

1. **A verb returns what midPoint did.** `assign`, `recompute`, `run`, `runDue`, `approve`, `closeStage`... return
   the `Effects` of their call ([What a block did](effects.md#what-a-block-did)). Claim what midPoint derived (accounts,
   attributes, mails, other objects), not what the test passed in: `assign("Auditor")` followed by
   `hasAssignment("Auditor")` proves nothing about the configuration.
2. **A state midPoint would not make now is written with `t.raw()`** ([Starting states midPoint would not
   make](#starting-states-midpoint-would-not-make-traw)): a value an earlier version left, an account linked but gone.
3. **In a Kotest spec, a container whose body changed midPoint checks it in its first test, a `precondition`**
   ([Writing tests with Kotest](writing-tests-kotest.md)).

| Call | What it is | Returns, fails with |
|---|---|---|
| `has*`, `is*`, `exists`, `doesNotExist`, `memberOf`, `hasMember` | a claim about midPoint now | its subject; `AssertionError` |
| `values`, `value`, `asObject`, `state`, `tag`, `names`, `members` | a read, for AssertJ | the value |
| `add`, `modify`, `delete`, `assign`, `unassign`, `recompute`, `run`, `runDue`, `approve`, `revoke`, `closeStage`... | a verb: midPoint works | `Effects` |
| `t.source(...)`/`t.target(...)` `add`/`modify`/`delete`, `t.raw()...`, the clock's `advance`/`stopAt` | a write past midPoint | its subject |
| `to`, `withSubject`, `of`, `ofType`, `assignedTo`, `forUser`; `projections`, `accounts`, `entitlements`, `intent`, `tag`, `named` | a selection only | ends in `exactly`, `none`, `single` |
| `effects.verifyAll(c -> ...)` | a verification: the checks claim, then nothing else may have happened | `Effects`; `AssertionError` |
| `effects.claims()` | the same in steps: claims nothing until `verifyAll()` | `Claims` |

A verb whose result the test cannot name beforehand returns what midPoint made, not `Effects`:
`t.certification(name).start()` its `Campaign`, `t.report(name).run()` its `ReportOutput` (an export task runs: it
is no read), and `t.addUser/addRole/addOrg/addService(...)` the focus midPoint named, by its OID. Their effects:
`effectsOf { }`.

A verb is a statement of its own: `t.task("x").resume()` and `t.task("x").run()` are two lines. Nothing chains onto
an `Effects` but its claims; in Kotlin, `.run()` or `.apply()` on an `Effects` is the standard library's.

The exceptions say whose mistake it is: `AssertionError`, midPoint is not as the test claims;
`IllegalArgumentException`, the test names what the configuration does not have (a resource, an item, an attribute);
`IllegalStateException`, the tester could not make the state the test asked for (a raw write the repository refused or
did not keep), or a spec breaks a rule of its structure (a missing precondition).

## Users, roles, orgs and services

`t.user(nameOrOid)`, `t.role(...)`, `t.org(...)` and `t.service(...)` (or the generated `Users.X`, `Roles.X`, `Orgs.X`,
`Services.X`) are focuses alike: `exists`, `hasItem`, `values`, `hasAssignment` (an assignment of a role, an org, a
service or an archetype with the default relation, as `assign` makes it; another relation:
`hasAssignment("IT", SchemaConstants.ORG_MANAGER)`), `isEnabled`, `asObject`, `why`, and the verbs `assign` (with a
relation too: `assign("IT", SchemaConstants.ORG_MANAGER)`; with a validity, the assignment's `activation`:
`assign("Contractor", a -> a.validFrom(start).validTo(end))`, a date the start of that day in the server's time
zone; the same in `modify(m -> m.assign(...))` and an actor's `request().assign(...)`), `unassign`, `recompute`. A focus is taken by name or by
OID, wherever a name goes (`assign(oid)`, `parent(oid)`); `name()` is midPoint's name of it now. A role, org or service has its `members()`
(`roleMembershipRef` with the default relation: not its managers or owners), an org its `parents()`.

A test adds, modifies and deletes a focus through the model, as an administrator in the GUI, and gets what midPoint
did:

```java
t.user("jdoe").add(u -> u.givenName("John").familyName("Doe").assign(Roles.SALES)
        .assign("IT", SchemaConstants.ORG_MANAGER).set(UserExtension.SITE, "Prague")
        .validTo(Instant.parse("2030-04-01T00:00:00Z")).password("Secret.123"));
t.org("apollo").add(o -> o.parent("Projects").assign("Project"));   // the Project metarole gives it its LDAP group
t.role("Directory admin").add();                                     // the name only: the templates do the rest
t.user("jdoe").modify(m -> m.set("telephoneNumber", "+1 555").addValues("extension/tags", "vip")
        .removeValues("extension/tags", "new").assign("Auditor").unassign(Roles.SALES));   // one delta
t.user("jdoe").modify(m -> m.set("costCenter", "IT").reconcile());   // reconcile(): alone, the focus's reconciliation
t.user("jdoe").delete();
String oid = t.user("jdoe").oid();

Org project = t.addOrg(o -> o.set("identifier", "099").parent("Projects"));   // the org template names it
project.hasItem("name", "P-099").hasEntitlementOn("LDAP");
t.user("jack").assign(project.oid());
```

`t.org(name).add(...)` is for the name the test gives and midPoint keeps. A focus whose name an object template
computes, or changes, is added as an administrator saves it in the GUI with the name left empty:
`t.addOrg(...)` (`addUser`, `addRole`, `addService`), with a name the template replaces as `set("name", ...)` and a
fixed OID as `oid(...)` if the test wants one. It returns the focus by the OID midPoint gave it, which is how the test
then takes it; a name the template replaced finds nothing.

`add` sets items by path (`extension/hrId`, as `hasItem` names them) or by the generated name, assignments with a
relation, `lifecycleState`, `disabled()`, `validFrom`/`validTo`, an `oid`, a user's names and `password`, an org's
`parent`; `object(o -> ...)` edits the object for the rest (an inducement, an authorization). In Kotlin the spec is
`it`: `t.user("jdoe").add { it.givenName("John") }`. A name that is taken fails, `t.expectFailure(...)
.isAlreadyExists()`; `t.callAs(Caller.user("jack"), () -> t.role("x").add())` adds as another user. `modify` takes
`set`, `addValues`, `removeValues`, `assign`, `unassign`, `password` and `reconcile()`, in one delta.

A value is read as its item's type, by the tester, the same on every line: `"5"` or `5` for an int, `true`, `false`,
`1` or `0` for a boolean, an `Instant`, `ZonedDateTime`, `LocalDate` (the start of the day) or an ISO text with an
offset for a date, `{ns}local` for a QName, an enumeration by its XML value (midPoint's and the extension schema's);
a value the item cannot take is the test's mistake (`IllegalArgumentException`: `extension/vip (xsd:boolean): not a
value: yes`). `hasItem` reads its expected values the same way, so `hasItem("extension/headcount", "5")` and a date
with an offset compare as midPoint stores them. A structured item of the extension schema (a named `complexType`
annotated `a:container`; midPoint reads no other kind) takes `Struct`s, its fields by local name, nested:

```java
t.user("jdoe").add(u -> u.set("extension/address",
        Struct.of("street", "Main", "city", "Prague", "phone", Struct.of("kind", "mobile", "number", "123"))));
t.user("jdoe").modify(m -> m.addValues("extension/address", Struct.of("street", "Side", "city", "Brno")));
t.user("jdoe").modify(m -> m.removeValues("extension/address", Struct.of("street", "Side", "city", "Brno")));
t.user("jdoe").modify(m -> m.set("extension/home/city", "Brno"));              // a field of a single-valued one
t.user("jdoe").hasItem("extension/address", Struct.of("street", "Main", "city", "Prague",
        "phone", Struct.of("kind", "mobile", "number", "123")));                // a whole value
t.user("jdoe").hasItem("extension/address/city", "Prague");                     // a field: UserExtension.ADDRESS.child("city")
Struct.xml("<home><street>Home</street></home>")                                // as XML, parsed by the item's definition
```

A value removed is the one with exactly those fields; a field of a multi-valued structure is not set alone (set the
whole value). A failure shows a value as `(street Main, city Prague, phone (kind mobile, number 123))`, its fields in
the order of the definition.

What a focus links on a resource is a projection, keyed as midPoint keys one: kind, intent and tag.
`projections(resource)` selects them all, `accounts(resource)` and `entitlements(resource)` those of the kind; a
selection narrows (`intent`, `tag`, `named`) and ends in `single()`, `exactly(n)` or `none()`, which check the
repository: the links and their live shadows. A projection is in two places, `shadow()` in the repository and
`onTarget()` on the target system, read as its shadow's kind and object class say: an `Account`, a `Group` (with
its `members()`, as the resource's associations store them) or a `TargetObject` of its class. `hasAccountOn` and
`hasEntitlementOn`, with an intent or without, claim both places. A failure lists every shadow the focus links on
the resource and why the selection left it out:

```java
t.user("jdoe").hasAccountOn("Directory", "admin").hasNoAccountOn("Directory", "service");
t.user("jdoe").accounts("Directory").intent("admin").single().onTarget().hasAttribute("description", "admin");
t.user("jdoe").accounts("Directory").intent("default").tag("contractor").none();   // accounts of one intent, by tag
t.role("Sales").hasEntitlementOn("Directory");
t.role("Sales").entitlement("Directory").onTarget().hasMember("uid=jdoe,ou=people,dc=example,dc=com");
t.target("Directory").group("cn=sales,ou=groups,dc=example,dc=com").hasMember("uid=jdoe,ou=people,dc=example,dc=com");
// User jdoe: expected one account on Directory, but links 2 (narrow them: intent, tag or named); linked on
// Directory: jdoe (account/default), jdoe-admin (account/admin), sales (entitlement/group; not an account)
```

## Tasks

A task the deployment ships suspended is resumed with
`t.task(name).resume()` (`mp.resumeTask`), as an operator does. The tester holds the recurring tasks (loosely bound
ones, the usual kind): they run only by `t.task(name).run()` or `t.clock().runDue()`, so a resume does not start one; the due tasks then run it when midPoint would. A task suspended after it was runnable and then resumed is due at once for a run it missed meanwhile when
`misfireAction` is `executeImmediately` (the default), at its next regular time with `reschedule`; a task deployed
suspended starts its schedule on the resume (an interval task at once, a cron task at its next cron time). Single-run and tightly bound tasks are not
held: a suspended single-run task runs on the resume, within the call; so does a tightly bound one, whose thread then sleeps until its
next run: the driver calls do not wait for it, and the reset after the test suspends it. Both
follow midPoint's clock only, the time a test sets: to run the task whatever its schedule, a test calls
`t.task(name).run()` after the resume; `t.clock().runDue()` says whether midPoint would start it by then, so a test that
uses it fixes the clock (`t.clock().startAt`, `stopAt`, `advanceTo`), or the due tasks on the real time may cross a cron
time. A run of a task still suspended fails, saying so. The reset after each test restores
the task's state with the rest, so resume it where the state the tests start from is set up (a container of a spec), not
in an earlier test.
`t.task(name).stopAfter(n).run()` stops the run after n items, as an operator's Suspend stops a task in the middle:
midPoint's running task says it cannot go on (`canRun()` false) once n items are done (the items its activity
processed), so a handler or a script that checks it between items ends there and records what it
did; the task itself is left as it was. A multi-threaded activity may do a few more items.
`t.task(name).run()` fails when the run fails, on all its objects (`FATAL_ERROR`) or on some (`PARTIAL_ERROR`), saying
the last object that failed and why; `t.task(name).allowPartialErrors().run()` passes a run that failed on some objects,
as tasks often do in production, and still fails one that could not run. `t.clock().runDue()` and its
scanners fail alike, the due tasks after they all ran; `t.clock().allowPartialErrors().runDue()` passes their partial
errors.

## Values and AssertJ

A user's `asObject()` is the user as midPoint has it now, the plain `UserType` the configuration's scripts see; read it
again after a change. Accounts, mails and work items give their values directly. With AssertJ (on the test classpath
of every line through the tester), `configtester.assertj.MidPointConditions` checks midPoint's references by the
typed names:

```java
import static com.github.wadahiro.midpoint.configtester.assertj.MidPointConditions.refTo;
import static com.github.wadahiro.midpoint.configtester.assertj.MidPointConditions.targeting;

UserType john = t.user("e1001").asObject();
assertThat(john.getCostCenter()).isEqualTo("Sales");
assertThat(john.getAssignment()).haveExactly(1, targeting(Roles.DEPT_SALES)).doNotHave(targeting(Roles.DEPT_IT));
assertThat(john.getRoleMembershipRef()).haveAtLeastOne(refTo(Roles.DEPT_SALES));
assertThat(john.getActivation().getEffectiveStatus()).isEqualTo(ActivationStatusType.ENABLED);

Account ldap = t.target(Resources.LDAP).account(dn);
assertThat(ldap.value(LdapAttrs.OU)).isEqualTo("Sales");
assertThat(ldap.groups()).containsExactly(SALES);
ldap.isEnabled();

assertThat(t.mails().to("e1001@example.com").subjects()).containsExactly("Welcome John Doe");
assertThat(t.workItems().forRole(Roles.PRIVILEGED_ACCESS).single().assignees()).containsExactly(Users.ROLE_OWNER.name());
```

| Type | Values |
|---|---|
| `User`, `Role`, `Org`, `Service` | `asObject()` (the `UserType`...), `values(item)` (an item by path or typed name, e.g. an extension item), `members()`, `parents()` (an org), `projections(resource).names()`/`list()`/`count()` |
| `Account`, `Group`, `TargetObject` | `value(attribute)`, `values(attribute)`, `attributes()`; an account's `groups()`; a group's `members()` |
| `Mails` | `subjects()`, `bodies()`, `perRecipient()`, `list()`, `count()` |
| `WorkItem` | `assignees()`, `candidates()`, `stage()`, `escalationLevel()`, `deadline()` |

A read gives names, as text (`List<String>`), whatever it reads (users, groups, members); a selection takes an object
type as midPoint's class (`changes().ofType(UserType.class)`), an enumeration's value as its XML value (`"enabled"`,
`"linked"`), and a change's kind as the selection names it (`added`, `modified`, `deleted`).

During a test AssertJ shows midPoint's values readably (`assignment of role Dept Sales`, `role Dept Sales`, a
polystring as its text, a date as an instant). A test that fails gets explanations appended to the failure (the
expected and actual values stay for the IDE's diff): the mappings behind the account values it read, and, as
possibly related, the mappings of the test that set an item of a user whose object it read, when the item's value
appears in the failure. Off with `-Dscenario.whyOnFailure=false`:

```
Expecting elements:
  [assignment of role Dept Sales]
to have exactly 1 times assignment of role Dept IT

Possibly related (the item's value appears in the failure):
Mappings that set assignment of e1001 in this test:
  - 'user-department-role' of User template for e1001 (task Reconcile HR, reconciliation): costCenter [] -> [Sales]; condition true; output [assignment of role Dept Sales]; 3 evaluations
```

The same evaluation repeated in a row is one line with its count: how often midPoint's projector evaluated the
mapping for the user in the operation (a reconciliation of many users evaluates it that often for each, a hint where
an expensive script runs repeatedly; the counts differ between lines).

The API's own assertions (`hasAssignment`, `hasAccountOn`, `hasAttribute`, `memberOf`, `isEnabled`...) check in a chain and
fail with the mappings behind the item they check.

## Mapping evaluations and times

`t.mappingStats()` counts and times the mapping evaluations of the test so far, for each mapping (by name and owner):
where an expensive script runs, and how often. The first evaluation of a mapping is far slower (the script's
compilation, the first search of a target system), so it is shown apart, and the mappings are sorted by the total of
the evaluations after it (three hires, the HR reconciliation reconciling every row each time, on 4.8):

```
Mapping evaluations in this test, by the total after the first, the longest first:
  'user-department-role' of User template: 12 evaluations, median 1.0 ms, after the first 10.3 ms; first 11.7 ms
  'sales-group' of Dept Sales: 4 evaluations, median 2.0 ms, after the first 5.0 ms; first 302.1 ms
  'hr-validTo' of HR: 12 evaluations, median 0.2 ms, after the first 2.7 ms; first 13.5 ms
  ...
```

A count is how often midPoint's projector evaluated the mapping (several times per user and operation, e.g. 2 on 4.8),
so a reconciliation of many users evaluates it that often for each. The times are of the test JVM, its
in-memory database and the tester's stand-in systems, and include the tester's recording of the expressions inside a
mapping: compare the mappings with each other, not with production. Each evaluation's time is also
`Evaluation.duration()` in the [low-level API](low-level-api.md).

## Cache of the loaded configuration and parallel test JVMs

A loaded configuration is kept on disk (`<tester.workDir>/tester-cache/`): the database, the target systems' content, the
mapped files and the load's report. The next JVM with the same configuration (same line and midPoint jars, same tester
classes, same manifest, files, overlay, initial data and extension schema) restores it instead of importing (sample
configurations: 3.2 s to 0.6 s per JVM; a configuration of 300 roles, 50 orgs and 1000 users: 9.2 s to 0.5 s on 4.8). A
damaged or unusable entry is loaded afresh, with a line in the log. `-Dtester.cache=false` turns it off.

`-Dtester.forks=N` runs N test JVMs per line module, each with its own midPoint, pgmem, ports and
`<workDir>/fork-<n>/`; the first JVM to need a configuration loads and caches it, the others wait and restore it. Default
1. midPoint already uses about two cores per JVM and several midPoints start more slowly together, so more JVMs pay
where tests wait on the real time or on a machine with many cores; measure first. The parent sets it up; a project
without it sets `<forkCount>`, `<reuseForks>true` and `-Dtester.forks=N -Dtester.fork=${surefire.forkNumber}` in the
`argLine`.

`-Dtester.timing=true` writes `<workDir>/tester/timing.md` (and prints it) and `timing.json` after the last class:
count, total, mean and maximum of each phase. The phases: the JVM before the tester (`startup.beforeTester`), the
tester's startup (`startup.*`, midPoint's Spring context in `startup.harness`), the deployment's fresh load or restore
(`deployment.loadFresh`, `deployment.cacheWrite`, `cache.restore.*`), resets and snapshots, driver calls and their
waits, each test's own time after its reset (`test.body`) and the whole JVM (`jvm.total`). Each phase is set beside the
previous run's (its `timing.json`, kept as `timing-previous.json`), or beside a reference run's with
`-Dtester.timing.baseline=<timing.json>`, so a change to the tester or to the deployment shows where the time went.

A failed assertion says what was expected, what is there and the mappings that set the value in the test;
`t.user("e1001").why("fullName").print()` and `t.target("LDAP").account(dn).why("cn")` show them on demand (mapping,
owner, sources old -> new, condition, output, task and channel).

## Starting states midPoint would not make: `t.raw()`

A state made through midPoint (a role assigned, an account provisioned) is the normal start of a test. Some states
midPoint would not make now: values an earlier version of the configuration or a migration left, an account that is
linked but gone from the target system, a value on the target system that drifted. Made through the model, such a
state already runs the rule the test is about, so a broken rule changes it while it is made and the test of the
behaviour passes. `t.raw()` writes past the model (no mappings, no provisioning, no policies, no audit), and reads
back what it wrote; through the model is `t.user(...).add/modify`, past it `t.raw()`, the values typed alike
(`Struct`s for a structured item):

```java
t.raw().user("jdoe").set("extension/token", "legacy-1");                 // addValues, removeValues: keep others
t.raw().user("jdoe").link("Directory", "legacy-1", s -> s.tag("legacy")); // target object, shadow and link
t.target("Directory").delete("legacy-1");                                // linked, but gone from the target
t.raw().user("jdoe").unlink("Directory", "legacy-1");                    // the link only
t.raw().shadow("Directory", "orphan").add();                             // a shadow nobody links
```

A linked account is three records: the object on the target system, its shadow in the repository, the focus's link
to the shadow. `link` writes the three (the object with the shadow's `attribute(...)`s if the target system does not
have it; the shadow of the resource's object type, `kind(...)`/`intent(...)` or the default account type, with the
target system's identifiers); the others write one each, so that the inconsistent states are made by combining them.
The read side names the place it reads, without provisioning (which would act on what it finds):
`t.user("jdoe").account("Directory").shadow().hasTag("legacy")`, `...onTarget().exists()`,
`t.user("jdoe").accounts("Directory").names()` (on the mirror and on LDAP alike). `hasAccountOn("Directory")`
claims all three: the link, its live shadow and the object on the target system. A role's group is linked alike:
`t.raw().role("Sales").link("Directory", "cn=sales,ou=groups,dc=example,dc=com", s -> s.kind("entitlement").intent("group"))`;
`t.raw()` has `user`, `role`, `org` and `service` by name.

## Nested classes: tests that share a prefix

A test of a business flow often repeats the same first steps: start a certification campaign, then check the
reminder; start it again, decide, pass the deadline, check the report; start it again... A `@Nested` class of a
`@MidPointConfigTest` class shares its prefix: its `@BeforeAll` methods run once, on midPoint reset to the loaded configuration,
and each of its tests starts from midPoint as the prefix left it (a checkpoint: the database, the target systems, the
mapped files, the stand-in databases, the clock, moved or stopped, and the mails, notifications, logs and HTTP requests
seen so far). What one test does is undone for the next. A nested class in a nested class starts from the outer
prefix and adds its own.

```java
@MidPointConfigTest
class CampaignTest {
    @Nested @TestInstance(Lifecycle.PER_CLASS)          // PER_CLASS: a @BeforeAll on the instance, fields it sets
    class TheCampaignHasStarted {
        Campaign campaign;

        @BeforeAll void start(MidPointConfigTester t) { campaign = t.certification("Role assignments").start(); }  // once

        @Test void aReminder22DaysBeforeTheDeadline(MidPointConfigTester t) { ... }
        @Test void anUndecidedCaseStaysAfterTheDeadline(MidPointConfigTester t) { ... }

        @Nested @TestInstance(Lifecycle.PER_CLASS)
        class ARevocationPastTheDeadline {
            @BeforeAll void revoke(MidPointConfigTester t) {                            // once, on the started one
                campaign.reviews().forUser("jdoe").forRole("Auditor").single().revoke(t.actor("sec-officer"));
                t.clock().advance("P30D").runDue();                                     // past the deadline
            }

            @Test void theCampaignIsClosed(MidPointConfigTester t) { ... }
            @Test void theReportListsTheRevocation(MidPointConfigTester t) { ... }   // not after the test above
        }
    }
}
```

- The tests of the top-level class start from the loaded configuration as before. `@BeforeEach` methods run in each test,
  after the checkpoint: they are part of the test, not of the prefix.
- Fields the prefix set (`campaign` above) are not checkpointed: they stay as the prefix left them, which is right
  for what the prefix created; do not change them in a test.
- What a checkpoint does not cover: midPoint's in-memory state that a reset does not restore either.
- Saving: a prefix shared by n tests runs once instead of n times; a checkpoint costs a database snapshot and a reset
  per test. Heavy prefixes (a synchronization, a report import, due tasks after a moved clock) gain most, light ones (a
  reconciliation of one account) little.
- Steps that continue each other stay one test method (Kotest's `steps` has no counterpart here).
