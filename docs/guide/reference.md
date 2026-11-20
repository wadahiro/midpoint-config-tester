# Limitations, properties and output files

## Limitations

- **Target systems are mirrors** unless kept on their real connectors. The mirror connector reproduces the
  schema and capabilities of a resource (readable passwords as the real connector's configuration says), not the
  behaviour of its real connector or system: no LDAP schema checks, no password policies of the target system itself
  (midPoint's own policies for the account apply), provisioning scripts recorded and not run, no asynchronous
  provisioning or updates;
  computed attributes, referential integrity and case insensitivity only as `ResourceOptions` say. Test
  what midPoint computes, not what the connector does. The tester's own stand-ins on a real connector are for CSV and
  LDAP only (any other connector reaches a system the test provides, [experimental](real-connectors.md)); its LDAP
  server is UnboundID's in-memory server with its standard schema (not
  OpenLDAP, 389 DS or AD: their schema, overlays such as `memberOf`, password policies and controls differ; its
  change log gives live synchronization no deletions and fails it on renames, see [Real connectors](real-connectors.md)).
- **Additional connectors stand in for their scripts only.** On a mirror a resource's `additionalConnector`s go, on a
  real connector each goes to a mirror that accepts scripts and nothing else
  ([Provisioning scripts and additional connectors](environment.md#provisioning-scripts-and-additional-connectors)):
  what midPoint takes from them besides (e.g. a `references` capability, 4.9+) is not tested.
- **PostgreSQL 18 in memory.** midPoint runs on pgmem, an in-memory PostgreSQL 18. Repository behaviour specific to other
  databases is not covered.
- **Little GUI.** Drivers call the services the GUI calls; GUI-only logic (wizards, the request access shopping
  cart) is not run; the request-access page's catalog (`requestAccess()`) is computed from its configuration as the
  line's GUI computes it, not rendered. What the GUI shows a user is asked of the services it calls
  ([What the GUI shows a user](gui-and-authorizations.md#what-the-gui-shows-a-user)): searches, pages by URL, the
  user's GUI profile (views, features, menu links), an object as the user gets it (`read`), the work items of their
  "My work items" (`workItems()`, the page's query as the user); not what a page does after it opens or the rest of
  the menus.
  The one exception is the objects' details pages: `newObjectForm` and `editForm` run the GUI's own page in the JVM
  (its fields' validations, the value typed as each field of the item's type takes it, its drop-downs' choices, the
  value rows of a multi-valued item with their add and
  remove buttons, Save, its tabs, the rows of its Assignments and Inducements tabs with their Unassign and "+"); not
  the browser's side, nor a new row's popup (`assign` from the tab is not driven). The "+" of a members panel (`createMemberButton`) is
  its buttons as the GUI derives them and its visibility by the GUI's rule, not the panel rendered; so are a members
  panel's Assign and Unassign (`membersPanel`, `governancePanel`, `orgTreeMembersPanel`): the GUI's rule of the
  buttons and the task or change the line's GUI makes, not the panel, its popup or its `searchBoxConfiguration`
  (relations and types it may narrow), the popup's relation-specific buttons and an org's "Other types" tab,
  indirect members and the subtree scope. The web application is not started: its filters
  (`web.xml`), the GUI's localization and the servers of plugins (gRPC, custom REST endpoints) are not run.
  midPoint's own REST API is called through its authentication, authorizations and controllers on 4.8 and later
  ([midPoint's REST API](environment.md#midpoints-rest-api)), with no HTTP server: TLS, proxies and the network are
  not tested.
- **The server's configuration is the tester's.** midPoint's `config.xml` has the settings the tester needs, the line's
  required ones (`tester.timeZone`, `tester.safeScriptingLanguagesOnly` on 4.11) and the manifest's
  [constants](environment.md#midpoints-constants); the rest of a production `config.xml` (repository, node and
  clustering settings, keystore) is not read.
- **An assignment's authorization is decided, not executed** (`canAssign`, the matrix's assign rows): as the
  clockwork decides the request and the execution of the change as written. The changes the projector adds to it
  (`roleMembershipRef`, metadata) are not checked, nor the rule for an assignment with a policy rule: an execution
  phase `#modify` narrowed to some items may refuse what the matrix allows. The GUI's list of what may be assigned
  (`getAssignableRoleSpecification`) is not checked either.
- **Recording gaps.** Metadata mappings are not recorded as mappings (their expressions are). Evaluations of a
  recurring task that runs between driver calls (before the test's first `t.clock().advance`: after it, recurring
  tasks run only through driver calls), and of worker threads of multi-threaded tasks, are not attributed to a driver
  call or lack their task's name. Distributed report export has no driver; certification work items are
  delegated and escalated only through timed actions (midPoint has no service call for it).
- **Variables are live objects.** `variables()` and `sources()` are what midPoint passed, not copies: compare them in
  the test that ran the driver.
- **Files.** Mapped directories are redirected for `java.io.File` and the JDK's Unix `Path`s, not for native code,
  child processes or files opened before the mapping (see [Files scripts read and write](files.md#files-scripts-read-and-write)).
  **Windows is not supported for file mapping**: there only `java.io.File` (and what is built from it) is redirected,
  `java.nio` paths such as `Path.of(...)` or `Files.writeString(...)` reach the real disk. Run tests that map
  directories on Linux or macOS.
- **Not in the tester**: GUI jars (only an index of their pages, built from them) and connectors other than CSV and LDAP on the classpath, custom Java overlays (add them as dependencies of
  your test module if your scripts need them; their Spring beans, such as hooks, through
  [Extension beans](environment.md#extension-beans); see [Customized midPoint deployments](customized-deployments.md)), clustering. What such beans call outside midPoint (HTTP services, cloud
  storage) is stood in for only through [HTTP services](environment.md#http-services).
- **Time**: `t.clock()` moves midPoint's clock and the JVM's wall clock, not `System.currentTimeMillis()` called from
  Java, `System.nanoTime()`, Quartz, the database or other processes (see
  [Activation and validity](midpoint-features.md#activation-and-validity)).
- **One line per JVM**: run a test suite once per line.

## Properties and output files

| System property | Default | Meaning |
|---|---|---|
| `tester.workDir` | `target` | working directory (midPoint home is `<workDir>/midpoint-home`) |
| `tester.timeZone` | none, required | the time zone the midPoint server runs in (an IANA ID); the parent passes it |
| `tester.safeScriptingLanguagesOnly` | none, required on 4.11 | `expressions/safeScriptingLanguagesOnly` of the server's `config.xml`; the 4.11 parent passes it |
| `tester.manifest.file` (POM property) | `midpoint-config-tester.yaml` next to the POM | the manifest of `@MidPointConfigTest` without a value |
| `tester.extensionSchemaDir` | none | extension schemas (`*.xsd`), see [Configuration and target systems](environment.md#configuration-and-target-systems) |
| `tester.cache` | `true` | `false`: no cache, every JVM loads its configurations afresh |
| `tester.cache.dir` | `<tester.workDir>/tester-cache` | the cache of loaded configurations (shared by the forks) |
| `tester.forks` | `1` | test JVMs per line module (the parent passes it; without the parent: Surefire `forkCount` plus `tester.forks` and `tester.fork`) |
| `tester.network.allow` | none | hosts the JVM may still reach, comma-separated, besides the manifest's (see [No other machine](environment.md#no-other-machine)) |
| `scenario.whyOnFailure` | `true` | `false`: a failed assertion does not list the mappings behind the value |
| `tester.taskTimeout` | `60` | seconds a driver call waits for background tasks |
| `tester.timing` | `false` | `true` writes the time of the tester's phases to `<workDir>/tester/timing.md` and `timing.json`, compared with the previous run (see [Cache of the loaded configuration and parallel test JVMs](writing-tests-junit.md#cache-of-the-loaded-configuration-and-parallel-test-jvms)) |
| `tester.timing.baseline` | none | a `timing.json` to compare with instead of the previous run's (a report kept from a reference run) |
| `tester.log` | `target/tester.log` | the log file, when the project has no logback configuration of its own |
| `pgmem.binary` (or `PGMEM_BINARY`) | from `pgmem-native` | the pgmem binary |
| `static.dir`, `static.schemaDir` | none | static checks of your configuration (static module) |

| Output | Written by |
|---|---|
| `<workDir>/tester/timing.md`, `timing.json` | `-Dtester.timing=true`, after the last test class (the previous run's `timing.json` is kept as `timing-previous.json`) |
| `<tester.workDir>/tester-cache/<deployment>/<key>/` | the cache of loaded configurations |
| `<tester.workDir>/fork-<n>/...` | everything of `<workDir>` per fork with `tester.forks` above 1 |
| `target/generated-test-sources/midpoint-names/` | the typed names (`NamesGenerator`) |
| `<workDir>/tester-resources/`, `<workDir>/tester-xml/` | resources as imported; XML given as text |
| `<workDir>/tester-mail/` | system configurations as imported, with the mail servers redirected; `files/`: the `redirectToFile` and `logToFile` files |
| `<workDir>/tester-files/<mapped dir>`, `<workDir>/midpoint-home/export/` | files of mapped directories; report exports (both reset with the database) |
| `target/tester.log` | midPoint's log (quiet configuration of the tester) |
| `target/static-check/<dir>.md` | static checks of `-Dstatic.dir` |
