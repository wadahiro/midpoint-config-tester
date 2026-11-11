---
name: midpoint-config-tests
description: Writes automated tests of a midPoint deployment's configuration with midpoint-config-tester (JUnit, a real midPoint in the test JVM, no GUI). Use when asked to test, automate the manual test cases of, or set up tests for a midPoint configuration directory (post-initial-objects, resources, roles, object templates, tasks, function libraries); when deciding which manual or E2E test cases belong in such tests; or when a config test module fails to load the deployment. Covers the module and manifest, loading a real deployment (stand-ins for resources, custom jars and beans, replaced initial objects, placeholders, support builds and forks of midPoint), triaging manual cases, minimal test data, the scenario and unit test APIs, debugging, and reporting what the tool lacks.
---

# Testing a midPoint configuration with midpoint-config-tester

The tester starts the line's midPoint in the test JVM (in-memory PostgreSQL, no GUI, no web server), loads the
deployment's configuration once per JVM as midPoint's post-initial import would, replaces every resource by a
stand-in, and resets to that snapshot before every test. A test drives midPoint through its services (reconcile, run a
task, act as a user, request and approve, move the clock) and asserts what midPoint did. The tool's README is the
reference for every call named here; read its sections when you need details.

Work in this order. Do not write the feature tests before the deployment loads.

## 1. The module

One Maven module next to the configuration (e.g. `<subsystem>/config-test/`), not inside the tool:

```xml
<parent>
    <groupId>io.github.wadahiro.midpoint</groupId>
    <artifactId>midpoint-config-tester-parent-4.0</artifactId>   <!-- the deployment's line: 4.0, 4.4, 4.8, 4.9, 4.10 -->
    <version>0.1.0-SNAPSHOT</version>
    <relativePath/>
</parent>
```

- **midPoint build.** The line's release by default. A support build or a fork (the image runs another build): set
  `<midpoint40.version>` (the line's property) to it, installed in the local repository. The parent manages midPoint's
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
| `sed`s tokens (`@HOST@`) or toggles comment blocks per environment | `config.replace: { "@HOST@": test-value, "<!--X-->": "" }` (texts as they are) |
| `{{name}}` properties | `config.properties` |
| sets `<constants>` in config.xml (`<const>`, `midpoint.getConst`) | `constants: { name: test-value }` |
| replaces one of midPoint's initial objects in its build (e.g. a system configuration with a PolyString normalizer) | an overlay directory with that file: a file with an initial object's OID replaces it as a whole, and `000-` sorts first |
| uses a feature of its midPoint build the release lacks | run on that build (section 1); an overlay without the feature only as a last resort |
| resources | `targets.<resource name>`: `mirror` (default, any connector) or `csv`/`ldap` (the real connector) |
| scripts call HTTP services (webhooks) via `java.net.URL` | `http.redirect: ["https://hooks.example.com"]`; requests are recorded |
| mail | `mail: { redirect: true }` (default) |
| recurring tasks | always held (no key): they run only when a test runs them (`t.task(name).run()`, `t.clock().runDue()`) |
| typed names | `names: { package: ... }` |

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
  - attributes multi-valued on the real system: `multiValued: { Connection: [parameters] }` (an error like "Attempt
    to replace 2 values to a single-valued item" on a target attribute);
  - `key=value` values the target system keeps one per key (a map: an added value replaces its key's value, a removed
    one removes the key; read the connector's update): `keyedValues: { Connection: { parameters: "=" } }`, also with
    a stored schema. Without it a recompute that changes one parameter leaves the old and the new value side by side;
  - other ways the target system keeps written values (normalizes case, sorts, adds a default): a Groovy expression of
    `values`, `add`, `remove`, `replace`, `name` returning the values kept, `store: { Connection: { parameters: "..." } }`
    (`keyedValues` is its shortcut; read the connector's update to write it);
  - an identifier the target system issues (an association's `valueAttribute` that is not the name, e.g. a numeric
    `identifier`): `identifier: identifier` (a random UUID; the expression form below makes it like the real one).
  - an identifier without a native name (`icfs:uid` beside `icfs:name` in the stored schema) that the connector
    issues apart from the name (read its create): `identifier: icfs:uid`; otherwise the mirror takes the name;
  Prefer the export when there are several such attributes: it has them all as in production.
- A target system that issues the identifier on create (a certificate's serial and validity, read back by an
  inbound): `identifier: { attribute: certId, value: "<Groovy of name, attributes, now>" }`, the value the real
  connector returns; read the connector's create for its format.
- A real CSV connector's file gets a header of the columns the resource names; for the real file's own layout give
  its header line as `initialData: initial-data/<file>.csv`.

## 3. Load the deployment first

Write `DeploymentTest` with one trivial assertion (a role exists) and iterate until it passes. The load reports every
resource it cannot stand in for at once; then midPoint's import errors, one file at a time.
`target/tester-deployment/<name>/load-report.txt` lists the import order, the initial objects and the stand-ins.

| Load failure | Cause, fix |
|---|---|
| refused connections to other machines | a service the deployment calls has no stand-in: `http.redirect`, a stand-in resource, or `network.allow` if it must be real |
| scripts import GUI classes (`unable to resolve class com.evolveum.midpoint.web...`) | declare `com.evolveum.midpoint.gui:admin-gui` without a version (the parent manages it, test scope) |
| a script reads `System.getenv(...)` (null, an NPE) | the manifest's `environment` (Groovy), or Surefire's `environmentVariables` for Java code |
| a script connects to another system's database over JDBC | the manifest's `databases` (its table definitions as init SQL) and `environment` with `${database.<name>.url}` |
| cannot tell which attribute names accounts of ri:X | no stored schema: `namingAttribute` |
| Item ... has no definition | a feature of another midPoint build (fork, newer line), or a missing extension schema |
| duplicate key ... uc_..._name | names collide under the default PolyString normalizer: the deployment replaces the initial system configuration (overlay it) |
| connectorRef cannot be resolved | the file is not recognized as a resource (report it as a tool gap) |
| background work did not finish | a task the tester waits for; look at its name and schedule |
| N generated name(s) are not in the deployment | regenerate the names, or an object the load did not import |

## 4. Triage the manual test cases

Classify each case before writing tests; most of a GUI-driven manual case is often midPoint logic.

- **A, scenario**: a midPoint-internal flow (sync, mappings, roles and inducements, lifecycle, tasks, approvals,
  certification, notifications, what is provisioned to a target).
- **B, unit**: one function, script, mapping or value policy with given inputs is enough (`t.midpoint().script(...)`).
- **C, static**: compiles / parses (the tool's static checks with `classpath(...)`).
- **D, manual/E2E**: GUI rendering and clicks, SSO and login flows, portals, the real external system's own behavior,
  infrastructure, performance.
- **P, partial**: the midPoint part as A/B, the rest stays D. Say which is which.

Prefer A/B over D wherever the manual case only observes midPoint logic through a screen. Manual workarounds for time
(editing dates by hand) become `t.clock().advance(...)`.

## 5. Write the tests

- **Minimal data per test.** Do not reproduce the manual environment's data set; take only its shape (attribute names,
  code formats, categories) and create what one test needs, fictitious, in the test.
- **Packages**: `configtest` (the deployment itself), `scenario` (one class per feature or test-case group, javadoc
  naming the manual case IDs), `unit` (functions and scripts).
- **Calls** (README "Writing tests"):
  - sources and targets: `t.source(r).add(name, attrs)`, `.modify`, `.delete`; `t.reconcile(r)`; `t.target(r).account(n)`
  - tasks: `t.task(name).run()`; a task shipped suspended: `.resume().run()`; stopped in the middle (an operator's
    Suspend): `.stopAfter(n).run()`
  - users: `t.user(n).exists().hasItem(path, v).hasRole(r).hasAccountOn(r).isEnabled()/isDisabled()/isArchived()`;
    `values(path)` for extension items
  - time: `t.clock().advance("P31D")`; a script's time window at its bounds (`LocalTime.now()` against 22:00):
    `t.clock().stopAt(day.atTime(21, 59, 59, 999_000_000))` stops the clock there, to the millisecond
  - cron tasks at a time of day: start the test at a fixed time first, `t.clock().startAt(day.atTime(12, 0))`, so
    what is due does not depend on when the test runs (never an `Assumptions` on `LocalTime.now()`)
  - functions: `t.midpoint().script("lib.execute('f', [user: u])").variable("u", user).evaluate().value()`
  - files scripts read: `t.midpoint().files().map("/dir").write("/dir/in.tsv", text)`
  - the directories the deployment's tasks write in (its volume): manifest `files: { initial: { /dir: files/dir } }`
    (empty directories by a `.gitkeep`); a due task that cannot write fails `t.clock().runDue()`
  - a flow whose tests repeat the same first steps: a `@Nested @TestInstance(PER_CLASS)` class whose `@BeforeAll`
    (a `MidPointConfigTester` parameter) is the prefix, run once; each of its tests starts from it (README "Nested
    classes"). In Kotlin, a Kotest spec (`MidPointConfigSpec`, README "Kotest specs"): a container
    (`"started" - { prefix; "test" { } }`), `steps(...)` for tests that continue each other; never put work between a
    container's tests
  - a shell command (a report's post-report script) sees the mapped directory in its arguments; one it builds itself
    does not. midPoint only logs a failed post-report script (WARN), so assert the file it should have written
  - a change from a service's channel (a hook that checks it): `t.inChannel(channelUri, () -> ...)`
  - an object collection's list as a user sees it (a "my requests" page): `t.actor(user).collection(name)`
  - calls a service makes on a user's behalf (a portal over midPoint's API; keep the service's own protocol in the
    project): `t.callAs(Caller.user(u).privileged().owner(u).channel(uri), () -> ...)`
  - an add-on's own service (an API server the tester cannot host): `t.midpoint().createBean(ServiceClass.class)`
    and `t.midpoint().invoke(label, (task, result) -> service.call(...))`; its protocol stays in the project
  - mails `t.mails()`, HTTP `t.httpRequests()` (answers: `t.httpServer().on(method, url).respond(status, body)`), what scripts log `t.logs().info().containing(...)`, the audit log
    `t.audit().ofType("DELETE_OBJECT").execution().target(name).single().outcome()` (a part of the test:
    `t.audit(() -> ...)`; midPoint audits to its database, so audit reports and scripts that search it work; another
    system's SQL on midPoint's own tables: `t.repositoryDatabase().rows(sql, ...)`), a mail that cannot be sent
    `t.mailServer().reject(address)`, failures
    `t.expectFailure(() -> ...)`
- **Lines differ.** Check the README's line notes before asserting status values (e.g. an archived user is `archived`
  on 4.0/4.4 and `disabled` on 4.8+).
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
