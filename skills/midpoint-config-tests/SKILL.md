---
name: midpoint-config-tests
license: EUPL-1.2
description: Writes automated tests of a midPoint deployment's configuration with midpoint-config-tester (JUnit, a real midPoint in the test JVM, no GUI). Use when asked to test, automate the manual test cases of, or set up tests for a midPoint configuration directory (post-initial-objects, resources, roles, object templates, tasks, function libraries); when deciding which manual or E2E test cases belong in such tests; or when a config test module fails to load the configuration. Covers the module and manifest, loading a real deployment (stand-ins for resources, custom jars and beans, replaced initial objects, placeholders, support builds and forks of midPoint), triaging manual cases, minimal test data, the scenario and unit test APIs, debugging, and reporting what the tool lacks.
---

# Testing a midPoint configuration with midpoint-config-tester

The tester starts the line's midPoint in the test JVM (in-memory PostgreSQL, no GUI, no web server), loads the
deployment's configuration once per JVM as midPoint's post-initial import would, replaces every resource by a
stand-in, and resets to that snapshot before every test. A test drives midPoint through its services (reconcile, run a
task, act as a user, request and approve, move the clock) and asserts what midPoint did. The tool's guide is the
reference for every call named here (https://github.com/wadahiro/midpoint-config-tester/blob/main/README.md, its pages in https://github.com/wadahiro/midpoint-config-tester/blob/main/docs/guide); read them when you
need details.

Work in this order. Do not write the feature tests before the configuration loads.

## 1. The module

One Maven module next to the configuration (e.g. `<subsystem>/config-test/`), not inside the tool:

```xml
<parent>
    <groupId>io.github.wadahiro.midpoint</groupId>
    <artifactId>midpoint-config-tester-parent-4.8</artifactId>   <!-- the deployment's line: 4.4, 4.8, 4.9, 4.10, 4.11 -->
    <version>0.8.0</version>
    <relativePath/>
</parent>
<properties>
    <tester.timeZone>Europe/Berlin</tester.timeZone>   <!-- required: the zone the midPoint server runs in -->
</properties>
```

- **Time zone.** `tester.timeZone` is required: the zone of the production midPoint server (its `TZ` or
  `-Duser.timezone`), not the developer's. Scripts' local dates (`basic.parseDateTime('yyyy-MM-dd', ...)`,
  `LocalDate.now()`) and `t.clock()`'s `LocalDateTime`/`LocalDate` are read in it, so CI in UTC gives the server's
  results.
- **4.11.** `tester.safeScriptingLanguagesOnly` is required too: `expressions/safeScriptingLanguagesOnly` of the
  server's `config.xml` (true on a fresh installation: MEL and safe Velocity only). A deployment without its own
  `000-system-configuration.xml` gets 4.11's initial one: MEL by default and no Groovy in resources, templates,
  reports or roles; ship the production system configuration to test Groovy as an upgraded server runs it
  (https://github.com/wadahiro/midpoint-config-tester/blob/main/docs/guide/lines.md#scripting-on-411).

- **midPoint build.** The line's release by default. A support build or a fork (the image runs another build): set
  `<midpoint48.version>` (the line's property) to it, installed in the local repository. The parent manages midPoint's
  own modules at that version, so the tester built for the release runs on those jars. Check with
  `mvn dependency:tree -Dincludes=com.evolveum.midpoint.model:model-impl`.
- **Extension schema.** `tester.extensionSchemaDir` in the surefire `systemPropertyVariables`: the deployment's `*.xsd`
  directory.
- **The deployment's jars.** A jar its scripts import: a test dependency (`system` scope with a `systemPath` is fine for
  a jar the build makes). Build it from the current sources first: a stale jar lacks newer classes.
- **Classes that replace midPoint's** (an overlay of `WEB-INF/classes`): copy them into `target/test-classes` with a
  `testResource` (include only the non-GUI packages), so they come before midPoint's jars.
- **The deployment's Spring beans** (change hooks, services scripts call): `src/test/resources/tester/ctx-extension.xml`
  with a `context:component-scan` of their packages and a `context:property-placeholder` of test properties (dummy
  endpoints, dummy modes). The tester's context imports every `tester/ctx-extension*.xml`.

## 2. The manifest (`midpoint-config-tester.yaml` next to the POM)

Mirror what the image does to the configuration before midPoint imports it; read its Dockerfile, entrypoint and
preparation scripts for that.

| Deployment does | Manifest |
|---|---|
| copies several directories into post-initial-objects | `config.dirs: [a, b]` (layered by relative path, imported by name order) |
| imports a directory after the deployment (by hand, a later job) | `config.then: [{ dirs: [c] }]` (each stage after the ones before it, in its own name order); `processing: full` when it is imported as the GUI import does (templates, provisioning; the post-initial import of 4.0 is raw) |
| `sed`s tokens (`@HOST@`) or toggles comment blocks per environment | `config.replace: { "@HOST@": test-value, "<!--X-->": "" }` (texts as they are) |
| `{{name}}` properties | `config.properties` |
| sets `<constants>` in config.xml (`<const>`, `midpoint.getConst`) | `constants: { name: test-value }` |
| replaces one of midPoint's initial objects in its build (e.g. a system configuration with a PolyString normalizer) | an overlay directory with that file: a file with an initial object's OID replaces it as a whole, and `000-` sorts first |
| uses a feature of its midPoint build the release lacks | run on that build (section 1); an overlay without the feature only as a last resort |
| resources | `targets.<resource name>`: `mirror` (default, any connector); experimental (docs/guide/real-connectors.md): `csv`/`ldap` (the real connector against the tester's stand-ins) or `real` (any connector against a system the test provides, with a `RealSystem` that restores its data) |
| scripts call HTTP services (webhooks) via `java.net.URL` | `http.redirect: ["https://hooks.example.com"]`; requests are recorded |
| mail | `mail: { redirect: true }` (default) |
| recurring tasks | always held (no key): they run only when a test runs them (`t.task(name).run()`, `t.clock().runDue()`) |
| typed names | `names: { package: ... }` |
| JDK | Maven on a JDK the line's midPoint supports (4.4: 11, 17; 4.8 to 4.10: 17, 21; 4.11: 21, 25; the parent checks it), pinned in the repository (`mise.toml`, `.sdkmanrc`) |

Resources deployed without a stored `<schema>` (its `definition`; midPoint fetches it from the real connector):
- **Best**: export each such resource from a midPoint that has connected to the real system (STG, a local environment;
  after a test connection), copy its `<schema>...</schema>` element as it is into a file (the usual prefixes are
  declared for it), and give the file: `schema: schema/<resource>.xml`. The mirror then has the real classes, multiplicities, naming attributes and
  identifiers. Do not commit the connector configuration.
- **Otherwise** the mirror takes the attributes `schemaHandling` names, single-valued unless `maxOccurs` says so:
  - what names objects when `icfs:name` is not used: `namingAttribute: dn`, or by class
    `namingAttribute: { User: userName, Group: displayName }`;
  - attributes only scripts read (`basic.getAttributeValue(projection, "x")`): `attributes: { user: [x] }`;
  - DNs that match regardless of case: `caseInsensitiveNames: true`;
  - attributes multi-valued on the real system: `multiValued: { Profile: [settings] }` (an error like "Attempt
    to replace 2 values to a single-valued item" on a target attribute);
  - `key=value` values the target system keeps one per key (a map: an added value replaces its key's value, a removed
    one removes the key; read the connector's update): `keyedValues: { Profile: { settings: "=" } }`, also with
    a stored schema. Without it a recompute that changes one setting leaves the old and the new value side by side;
  - other ways the target system keeps written values (normalizes case, sorts, adds a default): a Groovy expression of
    `values`, `add`, `remove`, `replace`, `name` returning the values kept, `store: { Profile: { settings: "..." } }`
    (`keyedValues` is its shortcut; read the connector's update to write it);
  - an identifier the target system issues (an association's `valueAttribute` that is not the name, e.g. a numeric
    `identifier`): `identifier: identifier` (a random UUID; the expression form below makes it like the real one).
  - an identifier without a native name (`icfs:uid` beside `icfs:name` in the stored schema) that the connector
    issues apart from the name (read its create): `identifier: icfs:uid`; otherwise the mirror takes the name;
  Prefer the export when there are several such attributes: it has them all as in production.
- A target system that issues the identifier on create (an order or ticket number, read back by an inbound):
  `identifier: { attribute: ticketId, value: "<Groovy of name, attributes, now>" }`, the value the real connector
  returns; read the connector's create for its format. When its create of an identifier it issued before only hands
  it back (no new object, no failure), add `existing: adopt`.
- A real CSV connector's file gets a header of the columns the resource names; for the real file's own layout give
  its header line as `initialData: initial-data/<file>.csv`.

## 3. Load the configuration first

Write `DeploymentTest` with one trivial assertion (a role exists) and iterate until it passes. The load reports every
resource it cannot stand in for at once; then midPoint's import errors, one file at a time.
`target/tester-deployment/<name>/load-report.txt` lists the import order, the initial objects and the stand-ins.

| Load failure | Cause, fix |
|---|---|
| refused connections to other machines | a service the deployment calls has no stand-in: `http.redirect`, a stand-in resource, or `network.allow` if it must be real |
| scripts import GUI classes (`unable to resolve class com.evolveum.midpoint.web...`) | the tester brings `admin-gui`: look for an exclusion of it on the tester's dependency |
| a script reads `System.getenv(...)` (null, an NPE) | the manifest's `environment` (Groovy), or Surefire's `environmentVariables` for Java code |
| a script connects to another system's database over JDBC | the manifest's `databases` (its table definitions as init SQL) and `environment` with `${database.<name>.url}` |
| cannot tell which attribute names accounts of ri:X | no stored schema: `namingAttribute` |
| Item ... has no definition | a feature of another midPoint build (fork, newer line), or a missing extension schema |
| duplicate key ... uc_..._name | names collide under the default PolyString normalizer: the deployment replaces the initial system configuration (overlay it) |
| connectorRef cannot be resolved | the file is not recognized as a resource (report it as a tool gap) |
| background work did not finish | a task the tester waits for; look at its name and schedule |
| N generated name(s) are not in the deployment | regenerate the names, or an object the load did not import |

## 4. Triage the manual test cases

Classify each case before writing tests; most of a GUI-driven manual case is often midPoint logic. An object
template's item validation (`item/validation/server`: a form's "identifier is taken", "3 to 100 characters") runs
only in the GUI's form, never when a test adds the object through the model: test it with
`t.newObjectForm(type).archetype(a).parent(org).validate(item, value).passes()` / `.fails(message)` (an existing
object's: `t.editForm(type, name)`), as the user who fills it in with `t.actor(user).newObjectForm(type)` (their
`actor` and authorizations, and whether they may open the page), the whole form with `.submit().saves()` /
`.rejects(item)` (`submit()` waits for the GUI's save in the background to end; `.submit().outcome()`: `SAVED`,
`REJECTED` with the page's messages, `INCOMPLETE` still running after 120 s, `UNDETERMINED` no result shown). Several values of a multi-valued item in one save, as its value rows take them:
`.set("extension/tags", listOf("a", "b"))` (exactly these), on `editForm` `.add(item, v)` / `.remove(item, v)`. A
boolean's or an enumeration's drop-down takes the choice: `.set("extension/vip", true)`, `null` clears it. Numbers, dates (`Instant`, to the minute), durations, QNames and lookup values are typed as the field takes them; a reference or a password fails `set` (change it through `t.actor(u).modify`). The tester runs the GUI's own page in the JVM, nothing to declare
(docs/guide/gui-and-authorizations.md). The "+" of a members panel: its buttons by `t.org(x).offersNewMember(type,
archetype[, relation])` (configuration), the user's by `t.actor(u).createMemberButton(t.org(x)).isShown()` /
`.isHidden()`, and `.click(type, archetype[, relation])` is the new member's form, `.set(...).submit().saves()`. The
tabs of the page as the user sees them: `t.actor(u).editForm(Orgs.SITE_1).tabs().hasTab("Members").hasNoTab("Governance")`
(a tab by label, label key or identifier; `.names()`, `.tab(x).hiddenBecause()`). A user's own profile page (the side
menu's Profile, `#selfAll`, for a user without `#userDetails`): `t.actor(u).selfProfileForm().set("telephoneNumber", v)
.submit().saves()`, the same form API (`tabs()`, `validate`, read-only fields fail `set`; the Save needs both phases;
4.8+ `adminGuiConfiguration/selfProfilePage` picks its panels). A user's own password change (the side menu's
Credentials, `#selfAll`): `t.actor(u).selfCredentials().currentPassword(old).newPassword(new).submit().saves()`,
`.deselect("LDAP")` / `.select("LDAP")` a target, `.open()` for `asksCurrentPassword()`, `targets()`,
`unmetRequirements()`, a refusal by `rejectsWithMessageContaining(part)` (a policy or authorization refusal changes
nothing; 4.8+ with the targets not listed the policy's message is in `targetResults()`, not `messages()`). The side menu as the user sees it,
built by the GUI (an item listed when any of the user's authorizations names its page's action, whatever the decision;
pair it with `canOpen(url)`, the enforcer's decision): `t.actor(u).sideMenu().hasItem(MenuPage.REQUEST_ACCESS)
.hasNoSection("ADMINISTRATION")`, `.hasItem("/admin/users")` / `("All users")`, `.hasNoItem(MenuPage.ROLES)`,
`.labels()`, `.item(x).url()`. The rows of its Assignments and
Inducements tabs as the user sees them, with the rows' Unassign and the "+" (the security enforcer with the object,
request phase): `t.actor(u).editForm(Roles.TEAM_1).inducements().hasRow(Roles.META_1).row(Roles.META_1).hasUnassign()`,
`.assignButton().isHidden()`; removing a row and saving as the user does it, refused by the model as an
unassignment (`#unassign`, an inducement's with `orderConstraints` order 1):
`t.actor(u).editForm(Roles.TEAM_1).uninduce(Roles.META_1).submit().saves()` / `.rejectsWith(message)` (exact) /
`.rejectsWithMessageContaining("(meta-1)")` (a part: the model's refusals embed OIDs; also
`.failsWithMessageContaining(text)` on `validate`),
`.unassign(Orgs.SITE_1)` on the Assignments tab. Which roles the request-access page shows a requester (computed
as the line's GUI computes it: 4.0 / 4.4 `roleManagement`, 4.8+ the GUI profile's `accessRequest`; each entry
ANDed with what they may assign to the target, searched as them):
`t.actor(u).requestAccess().offersRole(Roles.VPN).showsNoRole(Roles.ADMIN_TOOLS)`, `.requestAccess(Users.JACK)
.offersTarget(Users.JACK).withRelation(ORG_MANAGER).offersRole(...)`, `.withType(ServiceType::class.java)` for an
org folder's services (4.0 / 4.4: a folder lists roles and services together, `withType` only narrows), `.explain(role)` for why; then its Submit with the page's options: `t.actor(u).request().assign(role)`, several roles in one Submit
`.request().comment("why").assign(roleA, roleB)` (one case per role needing approval; `roleManagement/defaultExecuteAfterAllApprovals`
false applies each role as it is approved, true (default) all together at the end), a cart `.request().submit(c -> c.assign(a).assign(b, x -> x.validTo(end)))`,
several targets `.request(u1, u2).assign(role)` (the GUI's task owned by the requester, waited for; 4.0 adds it through the
model, so a requester needs `#add` of `TaskType` as midPoint's End user role grants it, 4.4 starts it unchecked, 4.8+
privileged, so only a deny of the requester's refuses it; a target refused fails the task on it). A members panel's Assign and Unassign as the user does them (the GUI's task
or change, its authorization checks): `t.actor(u).orgTreeMembersPanel(t.org(x)).assign(ORG_MANAGER, t.user(j))`,
`.unassign(ORG_MANAGER, t.user(j))` (a row), `.unassignSelected(...)`, `.unassignAll(...)`; the same on
`membersPanel(x)` and `governancePanel(x)` of the details page; `.assignButton().isShown()` / `.isHidden()`; a refusal
with `t.expectFailure { ... }`.

- **A, scenario**: a midPoint-internal flow (sync, mappings, roles and inducements, lifecycle, tasks, approvals,
  certification, notifications, what is provisioned to a target).
- **B, unit**: one function, script, mapping or value policy with given inputs is enough (`t.midpoint().script(...)`).
- **C, static**: compiles / parses (the tool's static checks with `classpath(...)`).
- **D, manual/E2E**: GUI rendering and clicks, SSO and login flows, portals, the real external system's own behavior,
  infrastructure, performance.
- **P, partial**: the midPoint part as A/B, the rest stays D. Say which is which.

Prefer A/B over D wherever the manual case only observes midPoint logic through a screen. Manual workarounds for time
(editing dates by hand) become `t.clock().advance(...)`.
The clock moves midPoint's `Clock` and the JVM's time sources as scripts read them (`basic.fromNow`,
`basic.currentDateTime()`, Groovy's `System.currentTimeMillis()`, `new Date()`, `java.time`): what midPoint's own
time setting cannot move in a manual test. It does not move `System.currentTimeMillis()` called from Java (the
audit's timestamps, custom Java libraries), `System.nanoTime()`, the database or other processes; recurring tasks run
only through `t.clock().runDue()` or `t.task(name).run()` (interval and cron, by the shifted clock). The table:
`docs/guide/midpoint-features.md`, "What moves with `t.clock()`".

## 5. Write the tests

- **Minimal data per test.** Do not reproduce the manual environment's data set; take only its shape (attribute names,
  code formats, categories) and create what one test needs, fictitious, in the test.
- **Packages**: `configtest` (the deployment itself), `scenario` (one class per feature or test-case group, javadoc
  naming the manual case IDs), `unit` (functions and scripts).
- **Calls** (https://github.com/wadahiro/midpoint-config-tester/blob/main/docs/guide/writing-tests-junit.md and the pages it links):
  - sources and targets: `t.source(r).add(name, attrs)`, `.modify`, `.delete`; `t.reconcile(r)`; `t.target(r).account(n)`
  - tasks: `t.task(name).run()`; a task shipped suspended: `t.task(name).resume()`, then `t.task(name).run()` on its
    own line (a verb is a statement: never chain onto an `Effects`; in Kotlin `.run()` there is the standard
    library's); an operator's Suspend: `t.task(name).suspend()`; stopped in the middle: `.stopAfter(n).run()`
  - users: `t.user(n).exists().hasItem(path, v).hasAssignment(r).hasAccountOn(r).isEnabled()/isDisabled()/isArchived()`;
    `values(path)` for extension items; `hasAssignment(r)` is the default relation, a manager's
    `hasAssignment(r, SchemaConstants.ORG_MANAGER)`; an assignment with a validity:
    `assign(r, a -> a.validTo(end))` (also in `request().assign` and `modify(m -> m.assign(...))`)
  - focuses by name or OID: `t.org(name).add(...)` for a name the test gives and midPoint keeps; a focus an object
    template names (or renames): `val org = t.addOrg { ... }` (`addUser`, `addRole`, `addService`), then `org` and
    `org.oid()` where a name goes, never the name it was added with
  - refusals: `t.expectFailure { ... }.isPolicyViolation()` / `.isNotAuthorized()` / `.isAlreadyExists()`, claims;
    the message the GUI shows (a constraint's `presentation/message`, translated): `.userMessage("ja")`, with the
    deployment's `midpoint.home/localization` given as the manifest's `config.localization`
  - a resource's provisioning scripts (run through an SSH `additionalConnector`): accepted and recorded, never run;
    `c.connectorCalls().to(r).scripts().single().hasArgument("user", "jdoe").codeContains("mkdir")`; a broken
    connection: `t.target(r).scriptsFail()`
  - what a verb did, and nothing else: `effects.verifyAll(c -> { c.changes().of(n).single().only(...); ... })`
    (Kotest: `effects.verifyAll { changes()... }`, the lambda after the parentheses); nothing at all: `none()`
  - the configuration at rest, after a verb that provisions: `effects.changes().focuses().forEach { it.recompute().none() }`
    and a second task run `.none()`; a strong outbound to an attribute midPoint cannot read is sent on every
    recompute, a strong password on every reconcile (and recomputation task, which reconciles by default): claim it
    (`only(...)`) or make it normal
  - a source feed: rows as `withData` in a container (blanks, case, apostrophes, non-ASCII, empty optional cells, a
    date at the day boundary, an unknown code), each row from the container's prefix
  - time: `t.clock().advance("P31D")`; a script's time window at its bounds (`LocalTime.now()` against 22:00):
    `t.clock().stopAt(day.atTime(21, 59, 59, 999_000_000))` stops the clock there, to the millisecond
  - cron tasks at a time of day: start the test at a fixed time first, `t.clock().startAt(day.atTime(12, 0))`, so
    what is due does not depend on when the test runs (never an `Assumptions` on `LocalTime.now()`)
  - functions: `t.midpoint().script("lib.execute('f', [user: u])").variable("u", user).evaluate().value()`
  - a constant of a deployed script lowered for one case (a daily limit of hundreds made 2), instead of editing the
    deployed object by hand: `t.config().functionLibrary(lib).function(f).replace("LIMIT = 300", "LIMIT = 2")` (the
    text exactly once; the next evaluation runs it; gone at the test's end and at a restore to an earlier checkpoint)
  - files scripts read: `t.midpoint().files().map("/dir").write("/dir/in.tsv", text)`
  - the directories the deployment's tasks write in (its volume): manifest `files: { initial: { /dir: files/dir } }`
    (empty directories by a `.gitkeep`); a due task that cannot write fails `t.clock().runDue()`
  - a flow whose tests repeat the same first steps: a `@Nested @TestInstance(PER_CLASS)` class whose `@BeforeAll`
    (a `MidPointConfigTester` parameter) is the prefix, run once; each of its tests starts from it (writing-tests-junit.md, "Nested
    classes"). In Kotlin, a Kotest spec (`MidPointConfigSpec`, https://github.com/wadahiro/midpoint-config-tester/blob/main/docs/guide/writing-tests-kotest.md): a container
    (`"started" - { prefix; "test" { } }`), `steps(...)` for tests that continue each other; never put work between a
    container's tests
  - a shell command (a report's post-report script) sees the mapped directory in its arguments; one it builds itself
    does not. midPoint only logs a failed post-report script (WARN), so assert the file it should have written
  - a change from a service's channel (a hook that checks it): `t.inChannel(channelUri, () -> ...)`
  - an object collection's list as a user sees it (a "my requests" page): `t.actor(user).collection(name)`
  - an object as a user sees it, the items they may not read removed: `t.actor(user).read(t.user(x))`
    `.hasItem(path, values...)` / `.hasNoItem(path)` / `.values(path)`
  - an approver's own work items (their "My work items", deputies included), decided as them:
    `t.actor(approver).workItems().forUser(x).single().approve()`; `t.workItems()` reads every work item as the
    administrator
  - a certification reviewer's comment column (a save of the comment alone, the response kept):
    `campaign.reviews().forUser(x).single().comment(t.actor(reviewer), text)`, read back with `.comment()` of a
    review listed again; `accept(actor)` / `revoke(actor)` later keep that comment, as the GUI's buttons send it
  - a campaign the test did not start (a task, a script, a report import or the model API made it):
    `t.campaign(nameOrOid)` gives the same `Campaign` as `t.certification(definition).start()`, with its `reviews()`,
    `comment`/`accept`/`revoke` and stage verbs (all lines; fails if there is no such campaign)
  - an account's naming attribute and identifier are attributes too: `account(dn).hasAttribute("dn", dn)`, a SCIM
    user's `hasAttribute("userName", name)` / `hasAttribute("id", id)`
  - a user's own change or deletion in the GUI (a self-service form, deleting from a list):
    `t.actor(user).modify(t.user(x)) { it.set(path, value) }`, `t.actor(user).delete(t.user(x))`. An actor's
    operations run synchronously in a lightweight task of the operation, as the GUI's, in the GUI's channel
    (`MidPointConfigTester.GUI_CHANNEL`) and owned by the actor. The tasks midPoint starts from one are midPoint's, as
    in production: a task runs with its owner's authorizations; an approved request's execution runs in the request's
    channel, owned by the requester; a background task in the channel stored in it (reconciliation, import and live
    sync switch to their own). A server task the test runs (`t.task(name).run()`) runs as the task defines, not as an
    actor; to test what a user starts in the background, run the task they would start, owned by them
    (`docs/guide/gui-and-authorizations.md`)
  - calls a service makes on a user's behalf (a self-service application over midPoint's REST API; keep the
    service's own protocol in the project): `t.callAs(Caller.user(u).privileged().owner(u).channel(uri), () -> ...)`
  - a client of midPoint's own REST API (4.8+): `t.rest().basic(user, password).get("/ws/rest/self")`, `.header(...)`,
    `.patch(path, json)`; `status()`, `body()`, `json()`, `effects()`; through midPoint's authentication and authorizations
    (the system configuration must reference a security policy), not `callAs`
  - a custom endpoint of the deployment (a server the tester cannot host): `t.midpoint().createBean(ServiceClass.class)`
    and `t.midpoint().invoke(label, (task, result) -> service.call(...))`; its protocol stays in the project
  - mails `t.mails()`, HTTP `t.httpRequests()` (answers: `t.httpServer().on(method, url).respond(status, body)`), what scripts log `t.logs().info().containing(...)`, the audit log
    `t.audit().ofType("DELETE_OBJECT").execution().target(name).single().outcome()` (a part of the test:
    `t.audit(() -> ...)`; midPoint audits to its database, so audit reports and scripts that search it work; another
    system's SQL on midPoint's own tables: `t.repositoryDatabase().rows(sql, ...)`), a mail that cannot be sent
    `t.mailServer().reject(address)`, a mail to an address the RFCs do not allow (`john..doe@example.com`; midPoint
    4.10+ sends it with `-Dmail.mime.address.strict=false`): listed, the address read leniently in `to()`/`cc()`/`from()`
    (else the header as written) and noted in `mail.unreadable()`, never failing `mails()` or a verb; failures
    `t.expectFailure(() -> ...)`
- **Lines differ.** Check the line notes (https://github.com/wadahiro/midpoint-config-tester/blob/main/docs/guide/lines.md#line-notes) before asserting status values
  (e.g. an archived user is `archived` on 4.4 and `disabled` on 4.8 and later).
- **Pin bugs you find** as tests of the current behavior, with a comment of what was intended, and report them.

## 6. When a test fails

- A driver call that ran a task fails with the task's errors and `last failure: <object>: <message>`.
- `target/tester.log` (WARN and up) has midPoint's errors with stack traces.
- An unexpected value: the assertion's message lists the mappings behind it (`why()`).
- An external call (Apache HttpClient, AWS SDK) cannot be stood in for: let the test tolerate exactly that error and
  assert midPoint's side.

## 7. What the tool lacks

When the tool cannot do what a case needs, do not work around it silently. Append to the module's `TOOL-GAPS.md`:
what was needed, the generic shape (no project names, URLs, data or file names of the deployment), the workaround if
any. These go back to the tool.
