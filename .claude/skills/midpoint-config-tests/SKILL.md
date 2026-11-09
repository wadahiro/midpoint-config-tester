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

Work in this order. Do not write scenario tests before the deployment loads.

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
| replaces one of midPoint's initial objects in its build (e.g. a system configuration with a PolyString normalizer) | an overlay directory with that file: a file with an initial object's OID replaces it as a whole, and `000-` sorts first |
| uses a feature of its midPoint build the release lacks | run on that build (section 1); an overlay without the feature only as a last resort |
| resources | `targets.<resource name>`: `mirror` (default, any connector) or `csv`/`ldap` (the real connector) |
| scripts call HTTP services (webhooks) via `java.net.URL` | `http.redirect: ["https://hooks.example.com"]`; requests are recorded |
| mail | `mail: { redirect: true }` (default) |
| recurring tasks | `tasks: { recurring: held }`: they run only when a test runs them |
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
  - DNs that match regardless of case: `caseInsensitiveNames: true`.
  Ask for the export when a multi-valued attribute or an identifier matters (an error like "Attempt to replace 2
  values to a single-valued item" on a target attribute).
- A real CSV connector's file gets a header of the columns the resource names; for the real file's own layout give
  its header line as `initialData: initial-data/<file>.csv`.

## 3. Load the deployment first

Write `DeploymentTest` with one trivial assertion (a role exists) and iterate until it passes. The load reports every
resource it cannot stand in for at once; then midPoint's import errors, one file at a time.
`target/tester-deployment/<name>/load-report.txt` lists the import order, the initial objects and the stand-ins.

| Load failure | Cause, fix |
|---|---|
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
- **B, unit**: one function, script, mapping or value policy with given inputs is enough (`s.midpoint().script(...)`).
- **C, static**: compiles / parses (the tool's static checks with `classpath(...)`).
- **D, manual/E2E**: GUI rendering and clicks, SSO and login flows, portals, the real external system's own behavior,
  infrastructure, performance.
- **P, partial**: the midPoint part as A/B, the rest stays D. Say which is which.

Prefer A/B over D wherever the manual case only observes midPoint logic through a screen. Manual workarounds for time
(editing dates by hand) become `s.time().advance(...)`.

## 5. Write the tests

- **Minimal data per test.** Do not reproduce the manual environment's data set; take only its shape (attribute names,
  code formats, categories) and create what one test needs, fictitious, in the test.
- **Packages**: `configtest` (the deployment itself), `scenario` (one class per feature or test-case group, javadoc
  naming the manual case IDs), `unit` (functions and scripts).
- **Calls** (README "Scenarios"):
  - sources and targets: `s.source(r).add(name, attrs)`, `.modify`, `.delete`; `s.reconcile(r)`; `s.target(r).account(n)`
  - tasks: `s.task(name).run()`; a task shipped suspended: `.resume().run()`
  - users: `s.user(n).exists().hasItem(path, v).hasRole(r).hasAccountOn(r).isEnabled()/isDisabled()/isArchived()`;
    `values(path)` for extension items
  - time: `s.time().advance("P31D")`; a script's time window at its bounds (`LocalTime.now()` against 22:00):
    `s.time().stopAt(day.atTime(21, 59, 59, 999_000_000))` stops the clock there, to the millisecond
  - functions: `s.midpoint().script("lib.execute('f', [user: u])").variable("u", user).evaluate().value()`
  - files scripts read: `s.midpoint().files().map("/dir").write("/dir/in.tsv", text)`
  - mails `s.mails()`, HTTP `s.httpRequests()`, failures `s.expectFailure(() -> ...)`
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
