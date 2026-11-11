# midpoint-config-tester

Tests for a midPoint configuration, run by a **real midPoint** from JUnit 5. A config repository (its
`post-initial-objects` directory and a manifest) is loaded as a deployment, as midPoint loads it on its first start,
and tests are operation scenarios over it: an HR record arrives, a user requests a role, a reviewer revokes an
assignment, the clock moves to a start date; then what midPoint made of it (users, accounts, groups, cases, mails,
reports) is checked by name.

- One artifact per midPoint release line (4.0, 4.4, 4.8, 4.9, 4.10). Each starts the line's own midPoint in the test JVM:
  Spring context, Clockwork, provisioning, cases, approvals, certification, reports, task manager, on an in-memory
  PostgreSQL. No midPoint server or database has to be installed.
- The deployment is loaded with the line's own initial and post-initial import (raw on 4.0, full processing on 4.4+);
  every resource is moved to a stand-in (an in-memory LDAP server, a CSV file, the tester's mirror connector) and mail
  goes to an in-memory SMTP server, so no real system is reached.
- Every test starts from the loaded deployment (a reset takes milliseconds); a loaded deployment is cached across runs.
- Typed names generated from the configuration (`Roles.DEPT_SALES`, `LdapAttrs.OU`) and shipped for midPoint's own
  objects and items (`InitialRoles.END_USER`, `UserItems.COST_CENTER`): a name the configuration no longer has does not
  compile.
- `why()` and every failed assertion show the mapping evaluations behind a value (recorded while midPoint ran).

The design, the owner's decisions and what each phase built are in [docs/next-design.md](docs/next-design.md).

## Getting started

A config repository needs a POM with one of the parents of this repository (one per line), its
`post-initial-objects`, the manifest `midpoint-config-tester.yaml` next to the POM, and tests. The example
[`examples/hr-ldap`](examples/hr-ldap) is such a repository, built and tested with this repository:

```
pom.xml                         the parent of the line and the repository's coordinates
midpoint-config-tester.yaml     the manifest: config directories, target systems, names package
post-initial-objects/           as deployed to midpoint.home/post-initial-objects
initial-data/directory.ldif     the LDAP server's initial content
src/test/java/...               the tests
```

```xml
<parent>
    <groupId>io.github.wadahiro.midpoint</groupId>
    <artifactId>midpoint-config-tester-parent-4.8</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</parent>

<groupId>com.example.idm</groupId>
<artifactId>hr-ldap-config</artifactId>
<version>1.0-SNAPSHOT</version>
```

The releases are in the Maven repository on this repository's branch `mvn-repo` (see [Publishing](#publishing));
declare it in the POM, where Maven also looks for the parent:

```xml
<repositories>
    <repository>
        <id>midpoint-config-tester</id>
        <url>https://raw.githubusercontent.com/wadahiro/midpoint-config-tester/mvn-repo</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

A snapshot of this repository: install it first (`mvn install -DskipTests`, or `-pl parent/midpoint-4.8 -am` for one
line). The parent `midpoint-config-tester-parent-<line>` brings:

- the tester artifact of the line (test scope) and JUnit, with the line's dependency management (midPoint's BOM, and
  the versions the tester pins over it: without them the repository's class path is not the one the tester is tested
  with);
- the generator of typed names, run at `generate-sources` on the manifest (its `names.package`), its output a test
  source root;
- Surefire: one JVM for all test classes (one midPoint), `-Dtester.forks=N` for more, the manifest as the default of
  `@MidPointConfigTest`, the agent of the file redirection (4.8+), and the tests on the JDK that runs Maven, one the
  line's midPoint supports: the parent checks it (see [JDKs](#jdks)); 4.0 and 4.4 compile with `--release 11`;
- Evolveum's repository and the pgmem profiles: the in-memory PostgreSQL
  ([pgmem](https://github.com/shibukawa/pgmem)) is a native binary from `io.github.shibukawa.pgmem:pgmem-native`,
  classifier `linux-x86_64` (default), `darwin-arm64`, `linux-arm64` or `windows-x86_64` by OS; on another platform
  give a binary with `-Dpgmem.binary=<path>` (or `PGMEM_BINARY`).

Properties a repository may set: `tester.manifest.file` (default `midpoint-config-tester.yaml` next to the POM),
`tester.names.skip`, `tester.forks`, and `midpoint.version` (or `midpoint40.version`... of the parent's line) for
another build of the line's midPoint, a support build or a fork in the local repository: the parent manages midPoint's
own modules at it (midPoint's BOM does not), so the tests run on those jars with the tester built for the line's
release. `mvn test` then loads the deployment once per JVM and runs the tests; the load
report is `target/tester-deployment/<name>/load-report.txt`.

To test one configuration on several lines (an upgrade from 4.4 to 4.8), use one module per line, each with the
parent of its line, the shared tests (`<testSourceDirectory>../src/test/java</testSourceDirectory>`) and
`<tester.manifest.file>${project.basedir}/../midpoint-config-tester.yaml</tester.manifest.file>`; overlays in the
manifest hold what a line needs differently. midPoint keeps static state, so a JVM holds one line.

## Writing tests

The API (`com.github.wadahiro.midpoint.configtester`: `@MidPointConfigTest`, `MidPointConfigTester`) targets the LTS lines 4.0, 4.4 and 4.8 first (4.9 and
4.10 run it with 4.8's import semantics). The project points the tester at its `post-initial-objects` directory with a manifest,
`midpoint-config-tester.yaml`:

```yaml
config:
  dirs: [post-initial-objects]          # as deployed to midpoint.home/post-initial-objects
  exclude: ["tasks/900-*.xml"]
  properties: { ldapBase: dc=example,dc=com }       # {{ldapBase}} in the files
  replace: { "@MAIL_HOST@": smtp.example.com }      # texts replaced as they are (as a deployment script's sed)
  overlays:
    "4.0": { dirs: [overlays/4.0], remove: [] }     # files that replace, add or remove files on one line
targets:                                # by resource name; any other resource is a mirror of its schema
  HR:   { connector: csv }
  LDAP: { connector: ldap, initialData: initial-data/directory.ldif, referentialIntegrity: true }
mail: { redirect: true }                # midPoint's mail servers -> the tester's SMTP server
```

A file that midPoint exports, `<object xsi:type="c:RoleType">`, is read as the `<role>` it is (resources moved to
their stand-ins, typed names, expression places). A task the deployment ships suspended is resumed with
`t.task(name).resume()` (`mp.resumeTask`), as an operator does. The tester holds the recurring tasks (loosely bound
ones, the usual kind): they run only by `t.task(name).run()` or `t.clock().runDue()`, so a resume does not start one
(`TaskRunsTest.aRecurringTaskAddedAfterTheClockHasMovedIsHeldToo`); the due tasks then run it when midPoint would. A
task suspended after it was runnable keeps its standard trigger paused, and resuming it fires a run missed meanwhile at
once when `misfireAction` is `executeImmediately` (the default), at its next regular time with `reschedule`; a trigger
midPoint builds anew on the resume (a task deployed suspended has none; 4.4 re-creates the one of an activity task)
starts now: an interval task at once, a cron task at its next cron time, whatever the misfire action
(`TaskRunsTest.aRunMissedWhileSuspendedIsDueAtOnceByDefault`, `…WaitsForTheNextCronTimeWithReschedule`). Quartz's
misfire threshold (60 s) is not applied: a test moves the clock further. Single-run and tightly bound tasks are not
held: a suspended single-run task runs on the resume, within the call
(`TaskRunsTest.aSuspendedSingleRunTaskRunsOnTheResume`); so does a tightly bound one, whose thread then sleeps until its
next run: the driver calls do not wait for it, and the reset after the test suspends it (`ResumedTasksTest`). Both
follow midPoint's clock only, the time a test sets: to run the task whatever its schedule, a test calls
`t.task(name).run()` after the resume; `t.clock().runDue()` says whether midPoint would start it by then, so a test that
uses it fixes the clock (`t.clock().startAt`, `stopAt`, `advanceTo`), or the due tasks on the real time may cross a cron
time. A run of a task still suspended fails, saying so (`SuspendedTaskScenarioTest`). The reset after each test restores
the task's state with the rest, so resume it where the state the tests start from is set up (a container of a spec), not
in an earlier test.
`t.task(name).stopAfter(n).run()` stops the run after n items, as an operator's Suspend stops a task in the middle:
midPoint's running task says it cannot go on (`canRun()` false) once n items are done (the task's progress, or the items
an activity processed on 4.4+), so a handler or a script that checks it between items ends there and records what it
did; the task itself is left as it was (`TaskStopScenarioTest`). A multi-threaded activity may do a few more items.
`t.task(name).run()` fails when the run fails, on all its objects (`FATAL_ERROR`) or on some (`PARTIAL_ERROR`), saying
the last object that failed and why; `t.task(name).allowPartialErrors().run()` passes a run that failed on some objects,
as tasks often do in production, and still fails one that could not run (`TaskErrorsTest`). `t.clock().runDue()` and its
scanners fail alike, the due tasks after they all ran; `t.clock().allowPartialErrors().runDue()` passes their partial
errors.

Once per JVM the tester starts the line's midPoint, imports **the line's initial objects** as midPoint does on its
first start (system configuration, administrator, End user and Approver roles, default security and password
policies, archetypes, collections, reports, the cleanup and scanner tasks; taken from midPoint's own jars), copies the
directories (overlays and properties applied, every resource moved to its stand-in, mail redirected; the user's files
are never changed), runs **midPoint's post-initial import** on the copy (a file with the OID of an initial object,
e.g. the system configuration, replaces it as a whole, as in production; so a deployment that replaces one of
midPoint's initial objects in its build puts that file first, e.g. in an overlay) (its file order, raw on 4.0 and full processing on 4.4+, bulk action files executed, the first
failing file fails the load with midPoint's errors), waits for the background work and takes a snapshot. Every test
starts from that snapshot.

```java
@MidPointConfigTest("src/test/resources/midpoint-config-tester.yaml")
class HrLifecycleTest {

    @Test
    void joinerMoverLeaver(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
        t.user("e1001").hasFullName("John Doe").hasRole("Dept Sales").isEnabled().hasAccountOn("LDAP");
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com")
                .hasAttribute("ou", "Sales").memberOf("cn=sales,ou=groups,dc=example,dc=com").isEnabled();
        t.mails().to("e1001@example.com").withSubject("Welcome").exactly(1);

        t.source("HR").modify("1001", "department", "IT");
        t.task("Reconcile HR").run();
        t.user("e1001").hasRole("Dept IT").hasNoRole("Dept Sales");

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
        t.user("e1002").isEnabled().hasRole("Dept IT").hasAccountOn("LDAP");
    }
}
```

Requests, approvals, certification, reports and refusals, by name (`scenarios/access`):

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
t.user("jack").hasRole("Privileged Access");

Failure f = t.expectFailure(() -> t.user("ann").assign("Auditor"));  // excluded by Accountant
assertThat(f.isPolicyViolation()).isTrue();

Campaign c = t.certification("Role assignments").start();
c.reviews().forUser("jack").forRole("Auditor").single().revoke(t.actor("sec-officer"), "left the audit");
c.closeStage().remediate();
t.user("jack").hasNoRole("Auditor");

ReportOutput out = t.report("Users by cost center").run(Map.of("costCenter", "IT"));   // 4.4+
out.hasRows(2).hasRowWith("User", "jack");
```

### What the GUI shows a user

What a user sees and may change in the GUI is decided by their authorizations, and asked the same way as a request,
logged in as them (`GuiAuthorizationScenarioTest`, the Help desk role of `scenarios/access`):

```java
// the objects a search finds (the GUI's lists): midPoint narrows it by the user's #read authorizations
assertThat(t.actor(Users.HELPDESK).search(UserType.class))
        .containsExactlyInAnyOrder(Users.JACK, Users.ROLE_OWNER, Users.HELPDESK);
assertThat(t.actor(Users.HELPDESK).canSearch(Users.ANN)).isFalse();   // #search: the list shows it
assertThat(t.actor(Users.HELPDESK).canGet(Users.ANN)).isTrue();    // #get: its details open
assertThat(t.actor(Users.HELPDESK).canRead(Users.JACK)).isTrue();  // #read: both

// a page opens, as the GUI decides it: by its URL, or by the actions of its @PageDescriptor
assertThat(t.actor(Users.HELPDESK).canOpen("/admin/users")).isTrue();
assertThat(t.actor(Users.HELPDESK).canOpen("/admin/user/" + Users.JACK.oid())).isTrue();   // #userDetails
assertThat(t.actor(Users.HELPDESK).canOpen(AuthorizationConstants.AUTZ_UI_USERS_ALL_URL,
        AuthorizationConstants.AUTZ_UI_USERS_URL, AuthorizationConstants.AUTZ_UI_USERS_VIEW_URL)).isTrue();

// an object's form: what it shows and what it lets the user change
Form form = t.actor(Users.HELPDESK).form(Users.JACK);
assertThat(form.modifiableItems()).containsExactly("telephoneNumber");
assertThat(form.canRead("emailAddress")).isTrue();
```

- The names follow midPoint's actions: `#search` lets a search find an object, `#get` lets the user get it by its OID,
  `#read` allows both. `search(type)` searches as the user through `ModelService` (all objects of the type, as the
  GUI's list does before its own filters) and gives typed refs, equal to the generated names; `canSearch(object)`
  is whether it finds the object, `canGet(object)` whether getting it succeeds, `canRead(object)` both.
- `collection(name)` is an object collection's list as the user sees it (a "my requests" page): the collection's
  definition read and compiled privileged, as the GUI does, its filter's expressions evaluated for the user
  (`midpoint.principalOid`), its base collection, then searched as the user (`CollectionScenarioTest`).
- `canOpen(url)` decides a URL as the GUI's authorization evaluator does: the path after the GUI's context, matched
  (case-insensitive Ant patterns) against every page's `@PageDescriptor` and the GUI's own URL mappings
  (`PageUrlMapping` on 4.0 and 4.4, `EndPointsUrlMapping` on 4.8), the actions of all that match together, with
  `#guiAll`; a page any user opens (login pages, `permitAll`) is allowed, a URL no page matches is denied. The patterns
  overlap as in the GUI: `/admin/user/**` (a user's details, `#userDetails`) also matches the new user page
  `/admin/user`. `canOpen(actions...)` (more than one argument, or one not starting with `/`) takes a page's actions
  instead (`PageUsers`: `#usersAll`, `#users`, `#usersView`). Either way the security enforcer decides
  (`decideAccess`): one allowed and none denied. A failed assertion shows what the URL matched and needed.
- The URLs come from an index the build writes from the line's GUI jars (`tester/gui-pages.tsv` in the line's jar,
  `GuiPagesIndex`: the descriptors read from the class files, the URL mappings loaded), not on the classpath; 4.9 and
  4.10 have no index yet (`canOpen(url)` fails there with a message).
- `form(object)` is the object's definition as the GUI's form gets it (`getEditObjectDefinition`, request phase):
  `canRead(path)`, `canModify(path)`, and the top-level `readableItems()` and `modifiableItems()`; `opens()` is false
  when the user may not read the object at all.
- A failed assertion after any of them lists the user's authorizations, by the role they come from:
  `'read-it-users': allow authorization-model-3#read of UserType with a filter; items name, givenName...`.
- midPoint's initial End user role (every user of the sample has it) lets a user get **any** user, with all items
  (`assignment-target-get`, for the GUI's selection windows): a search finds only what `#read` allows, but every user's
  details open for everyone, and a narrower `#read` shows no more than that. The test shows it; a deployment that
  keeps the role as shipped has it too.
- The execution phase is not asked on its own: a request or a change through the API goes through both
  phases, as in the GUI.

The GUI's configuration (`adminGuiConfiguration`) is asked the same way: `guiProfile()` is the user's GUI profile as
midPoint compiled it at login (`GuiProfileScenarioTest`):

```java
GuiProfile manager = t.actor(Users.IT_MANAGER).guiProfile();
assertThat(manager.views(UserType.COMPLEX_TYPE)).extracting(GuiProfile.CollectionView::identifier)
        .containsExactlyInAnyOrder("people", "department-members");   // the system's, the induced role's
assertThat(manager.view(UserType.COMPLEX_TYPE, "department-members").inMenu()).isTrue();   // 4.4+
assertThat(manager.featureVisibility("orgTreeExpandAll")).isEqualTo(UserInterfaceElementVisibilityType.HIDDEN);
assertThat(manager.defaultTimezone()).isEqualTo("Asia/Tokyo");
```

- midPoint merges the system configuration's `adminGuiConfiguration`, then each one of the roles, orgs and archetypes
  the user has (assigned or induced, with their conditions), by its rules: a later time zone wins, a hidden feature
  stays hidden. `guiProfile()` is that result (`CompiledGuiProfile`, 4.0
  `CompiledUserProfile`), not a merge of the tester's own: the object collection views (`views()`, by type), the
  additional menu links, the dashboard links, the features, the details pages' configuration (`objectDetails()`),
  the time zone and the data language; `compiled()` is midPoint's object for the rest.
- `inMenu()` is whether the line's side menu lists a view under its type, by the line's rule: 4.8+, not the
  default view, not hidden or vacant, for no operation or modify; 4.4, the same with a collection (the default view
  too); 4.0, a collection whose reference the user gets, an archetype or an object collection. So on 4.0 an end user's
  menu has no views when the user may not read the collection (the sample's manager). The menu item's own page
  (the users list) is `canOpen`. The rest of the menu (which sections and pages it shows) is not modeled.
- A failed assertion after it shows the user's views, features and links.

### An authorization matrix

Who may open, find, get, change and assign what is usually a matrix: kinds of users by what they may do. Write it
as YAML; the tester checks every cell (one test each) and writes an HTML report of it, per line. Besides the pages
(the GUI's authorizations), the rows are midPoint's model authorizations: the actions on objects, their assignments
and the items of their forms; and what the personas' GUI profiles make of the side menu.

```java
@AuthzMatrix("classpath:authz-matrix.yaml")      // loads the project's deployment, as @MidPointConfigTest does
class AuthzMatrixCheck extends AuthzMatrixTest { // the cells are its tests
}
```

`@AuthzMatrix(value = ..., manifest = "classpath:...")` names another manifest (`AuthzMatrixScenarioTest`).

```yaml
spec: authz-matrix/1
personas:                                  # the columns: users the tester creates with these roles, orgs, archetype
  end-user:  {label: End user, group: Self-service}                 # (the user template applies, as to any user)
  help-desk: {label: Help desk, group: Operations, roles: [Help desk]}
  it-manager: {label: IT manager, roles: [Department manager], orgs: [{org: IT, relation: manager}]}
objects:                                   # samples of what authorizations select as their object (and target)
  it-user: {user: jack, label: A user of IT (costCenter IT)}
  other-user: {user: ann, label: A user of another cost center}
  auditor: {role: Auditor, label: A requestable role}
assignment-targets: [auditor, {object: auditor, relation: owner}]  # what auto assign rows assign (default: every
                                                                   # role, org, service, archetype of the objects)
rows:                                      # the rows: a tree of checks, written or added with auto
  - {group: pages, label: Pages, auto: pages}                       # every page of the GUI, by URL
  - {group: gui, auto: [views, features, menu-links]}               # what the personas' GUI profiles have
  - {group: it-user, object: it-user, auto: [search, get, add, modify, delete, assign, unassign, items]}
  - group: mine
    object: self                           # the persona's own user
    rows: [{check: get}, {id: phone, check: modify telephoneNumber}]
default: N                                 # every cell no rule gives a value
rules:                                     # in order, the later wins
  - name: help-desk-users
    for: [help-desk]                       # or "*"
    rows: [pages/admin/users, it-user/search, it-user/items/modify/telephoneNumber]
    value: Y                               # or per line: {default: N, "4.8": Y}
    note: the users of IT; the telephone number is all they change
critical: [it-user/items/modify/assignment, it-user/delete]
```

- **Checks**: `open <url>` (as `canOpen`; `{it-user}` in a URL is that object's OID, `{self}` the persona's),
  `search`, `get`, `read` (both), `add`, `modify`, `delete` (midPoint's actions on the object, in both phases),
  `assign <object> [as <relation>]` and `unassign ...` (the row's object is the focus; as `Actor.canAssign`), `read
  <item>` and `modify <item>` (the object's form). An item of an object the persona does not get is `-`, not
  applicable: it matches N. From the persona's GUI profile (`Actor.guiProfile()`): `view <type>/<identifier>`,
  `feature <identifier>`, `menu-link <label>`.
- **The GUI's configuration** is the persona's GUI profile as midPoint compiled it at login (the system
  configuration's `adminGuiConfiguration`, then each of their roles', orgs' and archetypes'). A `view` row is Y when
  the side menu lists the object collection view: the profile has it, the line's menu lists it (`inMenu`: not hidden,
  not the default view on 4.8+; on 4.0 a collection the persona gets), and the persona is allowed the actions the
  menu asks for a type's views (`#usersAll` or `#usersView` for users, not `#users`, which opens the users list; the
  same for orgs, roles, services, resources, cases, tasks; views of other types get no row, a warning). A `feature`
  row is midPoint's `isFeatureVisible`, a `menu-link` row whether the profile has the additional link (the page it
  opens is a page row). The rest of the menu (its sections and pages) is the page rows; a details page's panels
  are not rows: the tester does not start the GUI, whose default panels midPoint adds to the profile.
- **Assignments** are decided as midPoint's clockwork decides a request, then its execution: the request is allowed
  by `#modify` of the `assignment` item or of all items, else by `#assign` (`#unassign`) with the target and the
  relation (`#delegate` too for a deputy); the execution needs `#modify` of the change. An authorization's `target`,
  `relation` and `orgRelation` are thus checked with samples: a persona's `orgs` take a relation
  (`{org: IT, relation: manager}`), a row's target one too (`manager`, `org:manager` or `{namespace}name`). The
  sample's `AssignAuthorizationScenarioTest` checks the decision against the requests themselves.
- **`auto`** adds rows from the running midPoint: `pages`, every page of the GUI that needs an authorization (the
  line's index, grouped by the URL's first segment, ids as in the URL: `pages/admin/users`, `pages/admin/user-any` for
  `/admin/user/**`); on an object, the actions listed and with `items` every item of its type in its form
  (`it-user/items/read/emailAddress`, `it-user/items/modify/telephoneNumber`); `views`, `features` and `menu-links`,
  what any persona's GUI profile has (`gui/views/UserType/people`, `gui/features/orgTreeExpandAll`,
  `gui/menu-links/Service-desk`). So a page, an item or a view nobody thought of is checked too: it is N unless a
  rule says otherwise.
- **Rows in rules** are paths of the tree: `*` is one segment or part of it (`pages/admin/roleNew*`), `**` any, a
  group selects all its rows. The lines have other pages (4.0's URLs from its `url()` end with `/**`, 4.4 has the
  new GUI's `userNew` beside `user`): a pattern no row of the line matches is a warning when rows are automatic, an
  error otherwise.
- **The file is checked** when read and when run, with its line: values only `Y` or `N` (not YAML's `yes`), unknown
  keys, personas, objects, checks, roles, orgs, items, URLs no page matches.
- **The report** (`<workDir>/authz-matrix/<file>-<line>.html`, one file, nothing loaded from elsewhere): personas as
  columns grouped by `group`, the rows' tree foldable (a folded group shows its count and what is not as expected),
  the cells marked as expected or not (✓ · – ✗ !), a rule's note on its cells (※), critical rows framed when allowed;
  filters: not as expected, with a note, critical allowed, personas differ. A cell shows the rule that gave its
  value (and its line), the note and the persona's authorizations.
- A cell not as expected fails its test with the rule, the note, the persona's authorizations and the report's path.
- **Every check is made as the persona logged in**: with their authorizations as midPoint compiles them at login,
  as the GUI's login does (from every role, org and archetype, assigned or induced, with relations, order
  constraints and conditions as midPoint evaluates them). The report and the failure messages list these, grouped
  by where they come from (`role Department manager`, also when an org induces it).
- **The samples are checked against the selectors.** For each persona, each selector of their compiled
  authorizations (`object` and `target`) that selects more than a type (a filter, `special: self`, `orgRelation`,
  `orgRef`, `roleRelation`, `archetypeRef`), of a type a sample may have: which samples it selects for the persona
  and which not, as midPoint selects them (a search by the filter midPoint makes of the selector alone,
  `Access.selectedBy`; the persona's own user is the sample `self`). With no sample inside or none outside, the
  matrix does not show where the selector ends: a warning (printed, and at the top of the report). The persona's
  details in the report list every such selector with its samples inside and outside; a selector with other clauses
  (`owner`, `assignee`, `delegator`, `tenant`) is listed as not checked.

The sample (`scenarios/access/authz-matrix.yaml`, 1656-1972 cells per line, about 4 s) shows what the matrix finds in
midPoint's own initial roles, each kept as a rule with a note: the End user role gets any user with all items, opens
a role's details and the new role page (`#roleDetails`); `#userDetails` opens the new user page too
(`/admin/user/**` matches `/admin/user`); `#users` opens the shadows list (`PageShadows`); a service's details need
`#service` on 4.0 and `#serviceDetails` on 4.8; every End user assigns themself the requestable roles but unassigns
none. The Department manager role of the sample assigns the members of the orgs its user manages the requestable
roles, with the default relation only; its `#usersView` shows the managers the users' views in the side menu (the help
desk, with `#users`, opens the users list but sees no views), which 4.0 does not list for them (they do not get the
views' collection), and its GUI configuration hides a feature the system configuration shows.

### Mails

`t.mails()` is every mail midPoint sent in the test, as the tester's SMTP server received it: the deployment's own
notifiers and transport, only the mail server redirected. Narrow by `to(address)`, `from(address)`,
`withSubject(text)` and `withBodyContaining(text)`; check with `exactly(n)`, `none()`, `single()` (a `Mail`:
`subject()`, `body()`, `hasBodyContaining(texts...)`, `from()`, `to()`, `attachments()`...), and with
`perRecipient()`, the number of mails each address got, for "these and nobody else". 4.0 and 4.4 send one mail to all
recipients of a notification, 4.8+ one per recipient: `to(...)` and `perRecipient()` count per recipient and read
the same on every line; `count()` counts SMTP transactions. 4.8+ appends a subscription notice to the body: check
with `hasBodyContaining`, not equality.

Approval notifications of `scenarios/access` (`ApprovalMailScenarioTest`): `simpleWorkflowNotifier` handlers in the
system configuration (category `workflowProcessEvent` add and delete to the requester, `workItemLifecycleEvent` add to
the assignee, `workItemAllocationEvent` modify, the `notifyBeforeAction` reminder, and add of an escalation), the
subject and body from expressions over `event`, `requester`, `requestee`, `assignee` and `workItem`; the stage of the
owner has a deadline (`duration` P5D) and a timed action that escalates to her deputy, with a reminder a day before:

```java
t.actor(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
t.mails().to("role-owner@example.com").withSubject("Approval needed: Privileged Access for Jack Sparrow").single()
        .hasBodyContaining("Dear Rose Owner,", "Stage 1 waits for your decision.");
WorkItem owners = t.workItems().assignedTo(Users.ROLE_OWNER).single();
Instant deadline = owners.deadline();                              // created + P5D

t.clock().advance("P4DT1H").runDue();                               // the trigger scanner runs the timed actions
t.mails().to("role-owner@example.com").withSubject("Reminder").single().hasBodyContaining("escalated in P1D");

t.clock().advance("P1D").runDue();                                  // past the deadline
WorkItem escalated = t.workItems().forUser(Users.JACK).single().atEscalationLevel(1).hasAssignee(Users.OWNER_DEPUTY);
t.mails().to("owner-deputy@example.com").withSubject("Escalated to you").single();
escalated.approve(t.actor(Users.OWNER_DEPUTY));
t.workItems().assignedTo(Users.SEC_OFFICER).single().approve(t.actor(Users.SEC_OFFICER));

t.mails().to("jack@example.com").withSubject("Request approved: Privileged Access").single()
        .hasBodyContaining("approved by Sam Officer");
assertThat(t.mails().perRecipient()).isEqualTo(Map.of("jack@example.com", 2, "role-owner@example.com", 2,
        "owner-deputy@example.com", 1, "sec-officer@example.com", 1, "sec-analyst@example.com", 1));
```

Group approvers are expanded on work item creation, as real deployments do: the sample's second stage (`approverRef`
to the Security officers role) has `groupExpansion` `onWorkItemCreation`, so each member gets a work item assigned to
them (`t.workItems().assignedTo(member)`) and a mail, and `evaluationStrategy` `firstDecides`: the first decision
counts and closes the other members' work items without a decision (`WorkItem.isCancelled()`; the decided one
`isApprovedBy(user)` / `isRejectedBy(user)`). What midPoint mails and what it does not (4.0, 4.4 and 4.8 alike): a
work item is mailed to its assignees (and their deputies); a group approver left unexpanded (the default
`groupExpansion`) gives one work item offered to the group to claim, with no assignee, so no one is mailed for it
(`WorkItem.candidates()` shows such a group). Every new
work item also raises a `workItemAllocationEvent` add, so a handler of that event for escalations needs an
`expressionFilter` (`event.operationKind?.value() == 'escalate'`). 4.8's schema has a `simpleCaseManagementNotifier`
element that no notifier of its notification module handles (its `EventHandlerRegistry` throws "Unknown handler"; read in the sources, not run): use `simpleWorkflowNotifier`, as on
4.0 and 4.4. The transport differs: `notificationConfiguration/mail` on 4.0 and 4.4, a named
`messageTransportConfiguration/mail` on 4.8 (an overlay of the system configuration).

### What a block did

`t.effectsOf { ... }` runs a block and returns what midPoint did meanwhile, with the work the block waited for (a
task's background work): `changes()`, the objects it added, modified or deleted in its repository, one change per
object with the items changed; `connectorCalls()`, the calls it made to the connectors of its resources, writes
(`add`, `modify`, `delete`, `executeScript`) and reads (`fetch`, `search`, `count`, `fetchChanges`...), mirror and
real connectors alike; `mails()`, `logs()` and `audit()` of the block; and `repositoryCalls()`, the repository calls
below midPoint's cache, for looking into what a configuration costs. Selections narrow and check like `t.mails()`.

A check that something did not happen, and a check of what happened that also proves nothing else did, are
verifications: in `claims()` (Kotlin: `effects.verifyAll { ... }`) each check claims what it selects (`exactly`,
`none`, `single`, and `ignored()` for what the test does not care about), and `verifyAll()` fails listing every
changed object, connector write, mail and error logged that no check claimed. `none()` is a verification without
checks. Connector reads, logs below ERROR and audit records need no claim.

```kotlin
val effects = t.effectsOf {
    t.clock().advanceTo(renewalDay)
    t.task("Nightly renewal").run()
}
when (row.outcome) {
    NOTHING -> effects.none()                          // no object changed, nothing sent to a resource, no mail
    RENEW -> effects.verifyAll {                       // these, and nothing else
        changes().of("jdoe").single().only("activation/validTo")
        connectorCalls().to("LDAP").modifies().of("uid=jdoe,ou=people,dc=example,dc=com").single().only("validTo")
        mails().to("jdoe@example.com").withSubject("renewed").single()
    }
}
effects.connectorCalls().to("HR").reads().count()      // how often the block read HR
effects.repositoryCalls().reads().perMethod()          // {"getObject UserType"=3, "searchObjects ShadowType"=8...}
```

```java
Claims claims = t.effectsOf(() -> t.task("Reconcile HR").run()).claims();
claims.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
claims.changes().ofType("ShadowType").added().ignored();               // the group shadows midPoint discovered
claims.connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single().only("ou");
claims.connectorCalls().to("LDAP").modifies().exactly(3);
claims.verifyAll();
```

What counts (`EffectsScenarioTest`, every line alike): a change is what midPoint wrote to its repository
(below the cache), so a script's direct `repositoryService` write is one too; a delta that leaves the item's values as
they were (a reconciliation setting `synchronizationSituation` to the value it has) is none. Not counted: `metadata`
and `operationExecution` at any depth (4.8 writes an `operationExecution` into each object a task processes), the
modifications of task and node objects, a shadow's synchronization timestamps, situation description, caching
metadata, attributes and reference attributes (midPoint's copy of the account, which 4.9 on refreshes as it reads; the account's own changes
are the connector calls). A connector call is recorded as midPoint made it to the connector instance; its account is the naming
attribute's value (`icfs:name`, an LDAP `dn`), else the identifiers; `of(account)` ignores case. The tester's own calls (an assertion that reads a
user, a wait that reads a task, `t.source(...)` writing a source system) are not effects: change source systems before
the block. A time-triggered follow-up (a trigger, a task's next run) is not part of the block unless the block runs it
(`t.clock().runDue()`). The block's exception propagates; check a failure inside it with `t.expectFailure`.

### Typed names

Names in strings fail only when the test runs. With `names: { package: com.example.idm.names }` in the manifest, a
generator writes Java constants from the deployment at build time, and every verb takes them (the strings stay as the
escape hatch, for names the configuration does not have):

```java
// before
t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
t.task("Reconcile HR").run();
t.user("e1001").hasRole("Dept Sales").hasAccountOn("LDAP");
t.target("LDAP").account(dn).hasAttribute("ou", "Sales");

// after: a typo, or a role the configuration no longer has, does not compile
t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
        .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales"));
t.task(Tasks.RECONCILE_HR).run();
t.user("e1001").hasRole(Roles.DEPT_SALES).hasAccountOn(Resources.LDAP).hasItem(UserExtension.SITE, "Prague");
t.target(Resources.LDAP).account(dn).hasAttribute(LdapAttrs.OU, "Sales");
```

Generated classes: `Resources`, `Roles`, `Orgs`, `Services`, `Archetypes`, `Users` (users the configuration holds),
`Tasks`, `Reports`, `CertificationDefinitions`, `<Resource>Attrs` per resource (its `schemaHandling` attributes, a stored
schema's attributes, a CSV connector's unique/name/password columns, the header of a CSV file of initial data, the
attributes of an LDIF of initial data), `UserExtension` (items of `config.schema` that extend users) and `Names` (all
of them). The names are those of every line together: a name only some lines have (an overlay adds, replaces or
removes its file) carries its lines (`Reports.USERS_BY_COST_CENTER.existsOn(t.line())`), and using it on another
line fails with a clear message. So shared
test sources compile on every line. An attribute of another resource (`account.hasAttribute(HrAttrs.FIRST_NAME)` on
LDAP) fails at once. Two names that would make the same constant (`Dept-Sales`, `Dept Sales`) fail the generation.

At load, every generated name of the line is checked against the loaded deployment (objects by name and OID,
attributes in the schema midPoint stored for the resource, extension items in midPoint's schema): generated classes of
an older configuration fail the load with the list of what is missing.

The generator is a main class of the tester artifact (no midPoint needed: plain XML and YAML), run by
exec-maven-plugin; it searches the given directories for `midpoint-config-tester.yaml` with `names.package`, writes a
file only when its content changes and deletes generated files no longer produced. The parents of
[Getting started](#getting-started) configure it; a project without them adds:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.0.0</version>
  <executions>
    <execution>
      <?m2e execute onConfiguration,onIncremental?>
      <id>midpoint-names</id>
      <phase>generate-sources</phase>      <!-- the IDE's "generate sources" runs it too -->
      <goals><goal>java</goal></goals>
      <configuration>
        <mainClass>com.github.wadahiro.midpoint.configtester.names.NamesGenerator</mainClass>
        <classpathScope>test</classpathScope>
        <arguments>
          <argument>${project.build.directory}/generated-test-sources/midpoint-names</argument>
          <argument>${project.basedir}/src/test/resources</argument>
        </arguments>
      </configuration>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>build-helper-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>midpoint-names-source</id>
      <phase>generate-test-sources</phase>
      <goals><goal>add-test-source</goal></goals>
      <configuration>
        <sources><source>${project.build.directory}/generated-test-sources/midpoint-names</source></sources>
      </configuration>
    </execution>
  </executions>
</plugin>
```

(The parents of config repositories configure it; this repository runs it from each line module's own classes,
`tester/pom.xml`.)

**midPoint's own names** are shipped in `com.github.wadahiro.midpoint.configtester.names.midpoint`, with the lines
that have them:

| Class | Names |
|---|---|
| `InitialUsers`, `InitialRoles`, `InitialArchetypes`, `InitialOrgs`, `InitialServices` | `ADMINISTRATOR`; `END_USER`, `SUPERUSER`, `APPROVER`, `REVIEWER`, `DELEGATOR`; `SYSTEM_USER`, `BUSINESS_ROLE`, `APPROVAL_CASE`, `PERSON` (4.8+)...; the orgs of 4.10; `INTERNAL` (4.4+)... |
| `InitialTasks`, `InitialReports` | `VALIDITY_SCANNER`, `TRIGGER_SCANNER`, `CLEANUP`; `RECONCILIATION_REPORT`, `USERS_IN_MIDPOINT`... (4.4+: the tester does not import 4.0's Jasper reports) |
| `InitialCollections`, `InitialDashboards`, `InitialPolicies`, `InitialMarks`, `InitialTemplates` | `MidPointObjectRef`s (name, OID, type) of types no verb takes by name: `ALL_TASKS`; `ADMIN_DASHBOARD`; `DEFAULT_SECURITY_POLICY`, `DEFAULT_PASSWORD_POLICY`; the marks of 4.8+; `PERSON_OBJECT_TEMPLATE` |
| `UserItems` | items of midPoint's user schema for `hasItem`, `values` and `why`: `NAME`, `FULL_NAME`, `GIVEN_NAME`, `FAMILY_NAME`, `EMAIL_ADDRESS`, `TELEPHONE_NUMBER`, `EMPLOYEE_NUMBER`, `COST_CENTER`, `LOCALITY`, `TITLE`, `ORGANIZATION`, `ORGANIZATIONAL_UNIT`, `LIFECYCLE_STATE`, `ADMINISTRATIVE_STATUS` (`activation/administrativeStatus`), `VALID_FROM`, `VALID_TO`, `EFFECTIVE_STATUS`...; `EMPLOYEE_TYPE` (4.0 only), `PERSONAL_NUMBER` (4.8+) |

```java
t.user(Users.JACK).hasRole(InitialRoles.END_USER).hasItem(UserItems.COST_CENTER, "IT");
t.midpoint().find(ValuePolicyType.class, InitialPolicies.DEFAULT_PASSWORD_POLICY.oid());
```

A name whose object differs between lines gets one constant per name (`InitialReports.CERTIFICATION_DECISIONS_REPORT`
on 4.4, `CERTIFICATION_WORK_ITEMS_REPORT` on 4.8+, the same OID). The classes are written by the
add-midpoint-line skill's `scripts/initial-names.py` from the initial objects of every line; `InitialNamesTest` checks
on every line that each name resolves after the initial import, that each initial object of these types has a name,
and that each user item is in the line's schema exactly on its lines.

### Values and AssertJ

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
assertThat(ldap.status()).isEqualTo(Status.ENABLED);

assertThat(t.mails().to("e1001@example.com").subjects()).containsExactly("Welcome John Doe");
assertThat(t.workItems().forRole(Roles.PRIVILEGED_ACCESS).single().assignees()).containsExactly(Users.ROLE_OWNER);
```

| Type | Values |
|---|---|
| `User` | `asObject()` (the `UserType`), `values(item)` (an item by path or typed name, e.g. an extension item) |
| `Account` | `value(attribute)`, `values(attribute)`, `attributes()`, `groups()`, `status()` |
| `Mails` | `subjects()`, `bodies()`, `perRecipient()`, `list()`, `count()` |
| `WorkItem` | `assignees()`, `candidates()`, `stage()`, `escalationLevel()`, `deadline()` |

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
an expensive script runs repeatedly; the counts differ between lines, e.g. 3 on 4.0 and 2 on 4.8).

The API's own assertions (`hasRole`, `hasAccountOn`, `hasAttribute`, `memberOf`, `isEnabled`...) check in a chain and
fail with the mappings behind the item they check.

### Mapping evaluations and times

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

A count is how often midPoint's projector evaluated the mapping (several times per user and operation, e.g. 3 on 4.0
and 2 on 4.8), so a reconciliation of many users evaluates it that often for each. The times are of the test JVM, its
in-memory database and the tester's stand-in systems, and include the tester's recording of the expressions inside a
mapping: compare the mappings with each other, not with production. Each evaluation's time is also
`Evaluation.duration()` in the PoC API.

### Deployment cache and parallel test JVMs

A loaded deployment is kept on disk (`<tester.workDir>/tester-cache/`): the database, the target systems' content, the
mapped files and the load's report. The next JVM with the same deployment (same line and midPoint jars, same tester
classes, same manifest, files, overlay, initial data and extension schema) restores it instead of importing (sample
deployments: 3.2 s to 0.6 s per JVM; a deployment of 300 roles, 50 orgs and 1000 users: 9.2 s to 0.5 s on 4.8). A
damaged or unusable entry is loaded afresh, with a line in the log. `-Dtester.cache=false` turns it off.

`-Dtester.forks=N` runs N test JVMs per line module, each with its own midPoint, pgmem, ports and
`<workDir>/fork-<n>/`; the first JVM to need a deployment loads and caches it, the others wait and restore it. Default
1; see [Speed](#speed) for when it pays (on a 10-core laptop it does not, for this repository's tests).

A failed assertion says what was expected, what is there and the mappings that set the value in the test;
`t.user("e1001").why("fullName").print()` and `t.target("LDAP").account(dn).why("cn")` show them on demand (mapping,
owner, sources old -> new, condition, output, task and channel). The sample deployments and their tests are
`tester/core/src/test/resources/scenarios/{hr,hr-mirror,access}` and
`tester/core/src/test/java/com/github/wadahiro/midpoint/configtester`; what each phase built is in
[docs/next-design.md](docs/next-design.md).

### Nested classes: tests that share a prefix

A test of a business flow often repeats the same first steps: start a certification campaign, then check the
reminder; start it again, decide, pass the deadline, check the report; start it again... A `@Nested` class of a
`@MidPointConfigTest` class shares its prefix: its `@BeforeAll` methods run once, on midPoint reset to the deployment,
and each of its tests starts from midPoint as the prefix left it (a checkpoint: the database, the target systems, the
mapped files, the stand-in databases, the clock, moved or stopped, and the mails, notifications, logs and HTTP requests
seen so far). What one test does is undone for the next. A nested class in a nested class starts from the outer
prefix and adds its own.

```java
@MidPointConfigTest
class CampaignTest {
    @Nested @TestInstance(Lifecycle.PER_CLASS)          // PER_CLASS: a @BeforeAll on the instance, fields it sets
    class TheCampaignHasStarted {
        AccessCertificationCampaignType campaign;

        @BeforeAll void start(MidPointConfigTester t) { campaign = startCampaign(t); }   // once

        @Test void aReminder22DaysBeforeTheDeadline(MidPointConfigTester t) { ... }
        @Test void anUndecidedCaseStaysAfterTheDeadline(MidPointConfigTester t) { ... }

        @Nested @TestInstance(Lifecycle.PER_CLASS)
        class ARevocationPastTheDeadline {
            @BeforeAll void revoke(MidPointConfigTester t) {                            // once, on the started one
                decide(t, campaign, "revoke");
                pastTheDeadline(t, campaign);
            }

            @Test void theCampaignIsClosed(MidPointConfigTester t) { ... }
            @Test void theReportListsTheRevocation(MidPointConfigTester t) { ... }   // not after the test above
        }
    }
}
```

- The tests of the top-level class start from the deployment as before. `@BeforeEach` methods run in each test,
  after the checkpoint: they are part of the test, not of the prefix.
- Fields the prefix set (`campaign` above) are not checkpointed: they stay as the prefix left them, which is right
  for what the prefix created; do not change them in a test.
- What a checkpoint does not cover: midPoint's in-memory state that a reset does not restore either, and a mail
  recipient rejected with `t.mailServer().reject(...)` (set it in the test that needs it).
- Saving: a prefix shared by n tests runs once instead of n times; a checkpoint costs a database snapshot and a reset
  per test. Heavy prefixes (a synchronization, a report import, due tasks after a moved clock) gain most: about a
  third of a certification flow's time on a 4.10 deployment (5 tests, 35 s to 23 s, as with Kotest); light ones (a
  reconciliation of one account) little. `NestedPrefixScenarioTest` shows what each test starts from.
- Steps that continue each other stay one test method (Kotest's `steps` has no counterpart here).

### Kotest specs: the same in Kotlin

`midpoint-config-tester-kotest` (opt-in, Kotlin) runs [Kotest](https://kotest.io) specs over the deployment with the
same checkpoints: a container's code before its first test is its prefix, run once, and each test of the container
starts from midPoint as the prefix left it. The tree reads like the flow, values the prefix computed are plain local
values, and tests are named by sentences.

```kotlin
@MidPointConfigTest
class CampaignSpec : MidPointConfigSpec({
    "the campaign has started" - {
        val campaign = t.startCampaign()             // once
        "a reminder 22 days before the deadline" { ... }
        "an undecided case stays after the deadline" { ... }
        "a revocation past the deadline" - {
            t.decide(campaign, "revoke")             // once, on the started campaign
            t.clock().advanceTo(campaign.deadline).runDue()
            "the campaign is closed" { ... }
            "the report lists the revocation" { ... } // from the revocation, not after the test above
        }
    }
    steps("a scenario's steps") {                    // tests that continue each other, stopping at the first failure
        "hire" { ... }
        "move" { ... }
    }
})
```

- Each root starts from the deployment, as a JUnit test method does; `t` is its tester. A failed assertion gets the
  mappings behind the values it read, as in JUnit (`ExplanationSpec`).
- Work in a container between its tests would be undone after the next test: the next test fails and says so (put it
  before the first test, in a container of its own, or use `steps`).
- Kotlin values the prefix computed (`campaign` above) are not checkpointed, as the fields of a nested class are not;
  the checkpoint covers what it covers for nested classes, and saves as much.
- Set up: the parent POMs manage Kotlin and Kotest and run `*Spec` classes; a repository adds, as test dependencies,
  `midpoint-config-tester-kotest`, `io.kotest:kotest-runner-junit5-jvm`, `org.jetbrains.kotlin:kotlin-stdlib` and the
  `kotlin-maven-plugin` (`examples/kotest-scenarios`, on 4.0 with JDK 11 and on 4.8). IntelliJ needs the Kotest plugin
  to run one spec or test from the gutter; Maven's `-Dtest=` selects specs by class name.

## The low-level API (`t.midpoint()`)

The sections below describe the low-level API (`com.github.wadahiro.midpoint.tester`: `MidPoint`, `Run`,
`Evaluation`, `TargetSystem`...), the engine under `MidPointConfigTester`. A test reaches it with `t.midpoint()` (below
`mp`): to read an object, drive a call the `MidPointConfigTester` API has no verb for, import a resource with options
of its own, or select the evaluations of a run. The tester's own engine tests (`RuntimeTest`, `ResetTest`,
`TaskRunsTest`, `ClockTest`, `FilesTest`, `LiveSync*Test`, `MembershipTest`) use it on a deployment of nothing
(`scenarios/bare`); the tests named below as examples or as the pins of a line difference are in git history
(`git show 1e39281^:tester/core/src/test/java/com/github/wadahiro/midpoint/tester/<Test>.java`).

### Contents

- [Supported lines](#supported-lines)
- [Resets](#resets)
- [Configuration and target systems](#configuration-and-target-systems)
- [Drivers](#drivers)
- [Selecting evaluations](#selecting-evaluations)
- [Approvals, certification, reports, dashboards and time](#approvals-certification-reports-dashboards-and-time)
- [Activation and validity](#activation-and-validity)
- [Scripts, value policies and bulk actions](#scripts-value-policies-and-bulk-actions)
- [Files scripts read and write](#files-scripts-read-and-write)
- [Hooks, notifications and authorizations](#hooks-notifications-and-authorizations)
- [Mail notifications](#mail-notifications)
- [HTTP services](#http-services)
- [Static checks](#static-checks)
- [Line notes](#line-notes)
- [Limitations](#limitations)
- [Properties and output files](#properties-and-output-files)
- [Building this repository](#building-this-repository)

## Supported lines

| Line | midPoint | Artifact | JDK running the tests | Groovy | Repository |
|---|---|---|---|---|---|
| 4.0 | 4.0.3 | `midpoint-config-tester-4.0` | **11** | 2.5.8 | old (Hibernate) repository on PostgreSQL |
| 4.4 | 4.4.11 | `midpoint-config-tester-4.4` | 11 / **17** | 3.0.19 | native (`sqale`) |
| 4.8 | 4.8.14 | `midpoint-config-tester-4.8` | 17 / **21** | 4.0.24 | native |
| 4.9 | 4.9.9 | `midpoint-config-tester-4.9` | 17 / **21** | 4.0.24 | native |
| 4.10 | 4.10.3 | `midpoint-config-tester-4.10` | 17 / **21** | 4.0.24 | native |

The build and its tests cover the LTS lines 4.0, 4.4 and 4.8; the modules of 4.9 and 4.10 (tester, static checks,
parents) are built and tested only with `-Pnon-lts`.

All artifacts have the group `io.github.wadahiro.midpoint` and the same API (packages
`com.github.wadahiro.midpoint.configtester` and, for the PoC API, `com.github.wadahiro.midpoint.tester`): a test
written for one line compiles and runs on the others. Each line has the parent `midpoint-config-tester-parent-<line>`
for config repositories. What differs
between lines is midPoint's behaviour, see [Line notes](#line-notes).

midPoint keeps static state, so a JVM holds one midPoint line. To test one configuration on two lines, run the same
tests in two Maven modules (or two runs) with different tester artifacts.

## Resets

Every test method starts from the deployment as loaded: what a test imported or changed is gone after it. A reset restores the database snapshot, midPoint's caches, the target systems, the configuration index and midPoint's
clock, and suspends background tasks still running after 10 seconds. It runs no task: Quartz is held until all of it is
restored, and every recurring task goes back to the schedule it had at the snapshot (its next run one interval after
its last run); recurring tasks held after `t.clock().advance` fire on their own again. Each test method starts logged in as
`administrator`, with no notifications.

## Configuration and target systems

| Call | What it does |
|---|---|
| `mp.importObject(file)` | imports an object as the GUI import does with "Full processing": references by filter resolved, added through the Clockwork, overwriting an existing one |
| `mp.importObjects(dir)` | every `*.xml` file of the directory, in file name order, as one run with one result |
| `mp.importObjectXml(xml)` | like `importObject`, for XML text |
| `mp.importObjectRaw(file)` | stored as is (raw), nothing evaluated |
| `mp.importResource(file)` / `mp.importResource(file, options)` | the resource definition as deployed, with only its connector replaced by the mirror connector; returns its `TargetSystem` |
| `mp.importResource(file, ResourceOptions.defaults().realConnector())` | the resource on its real CSV or LDAP connector, with a file or an in-memory LDAP server of the tester (see [Real connectors](#real-connectors)) |
| `mp.target(resourceNameOrOid)` | the `TargetSystem` of an imported resource |
| `mp.csv(resource)`, `mp.ldap(resource)` | the CSV file / the LDAP server of a resource on its real connector |

Files given to these calls are indexed: that is how evaluations get their identity and file location. XML given as
text is written to `<workDir>/tester-xml/` and indexed from there; a resource as imported (with the mirror connector) is
written to `<workDir>/tester-resources/`.

**Target systems.** The mirror connector is an in-memory ConnId connector that reproduces the real resource's schema
(from the stored `<schema>` of a definition exported from midPoint; without one, the object classes and attributes
`schemaHandling` uses, as strings) and its activation and password capabilities. A `TargetSystem` reads and changes it
with the real resource's names: objects by their naming attribute (`icfs:name`, or e.g. the DN), attributes by local
name. Validity dates (ConnId's `__ENABLE_DATE__` / `__DISABLE_DATE__`, midPoint's `activation/validFrom` / `validTo`)
are there when the native capabilities list them, or, without those, when `schemaHandling` maps them; they are
attributes in milliseconds (`attribute(account, "__ENABLE_DATE__")`). A status simulated with an attribute
(`capabilities/configured/activation/status/attribute`) is that attribute, as on the real connector: the mirror then
has no native status and has the attribute even when `schemaHandling` does not name it; `enabled(account)` and
`setEnabled(account, enabled)` read and write the attribute with the enable and disable values (on the real CSV and
LDAP connectors too, see [Activation and validity](#activation-and-validity)). Passwords are stored (`password(account)`,
`setPassword(account, value)`) and returned as the real connector returns them: the native capability
`credentials/password/readable` if the definition lists the native capabilities; otherwise the connector's
configuration: the CSV connector's `passwordAttribute` (the column, returned by default), the LDAP connector's
`passwordReadStrategy` (`readable`: the value as stored; `incompleteRead`: a password without its value, which midPoint
marks incomplete; `unreadable`, the default: nothing); any other connector: not readable (ConnId's default). So a
password inbound (`asIs`) gets the account's password where production does, and nothing where production does not.

```java
TargetSystem ldap = mp.importResource(new File(CONFIG, "resource-ldap.xml"),
        ResourceOptions.defaults()
                .computed("memberOf", "groupOfNames", "member")   // computed by the target system
                .referentialIntegrity()                           // renames and deletes keep references valid
                .caseInsensitiveNames());                         // DNs match regardless of case
ldap.addAccount("uid=jdoe,ou=people,dc=example,dc=com", Map.of("cn", "John Doe"));
ldap.attributeValues("uid=jdoe,ou=people,dc=example,dc=com", "memberOf");
```

`ResourceOptions.namingAttribute(...)` and `identifier(...)` are only needed for hand-written definitions without a
stored schema whose naming attribute is not `icfs:name` (a stored schema is its `definition`: a `<schema>` with only
`generationConstraints` has none). A definition deployed without its stored schema (midPoint fetches it from the real
connector when it first connects) is best given the stored schema of the real resource: `ResourceOptions.schema(file)`
(manifest: `schema: schema/ldap.xml`): the `<schema>` element copied as it is out of the resource exported from a
midPoint connected to it (the usual prefixes, `xsd`, `ra`, `a`, `ri`, `icfs`..., are declared for it; the connector
configuration, credentials among them, stays out of the test repository). The first `<schema>` with a `definition`
in the file is used, so a whole export works too. The mirror then has the real classes, multiplicities, naming attributes and
identifiers; without it, the attributes `schemaHandling` names, single-valued unless `maxOccurs` says otherwise, as
the options below give them. An association's `shortcutAssociationAttribute` (LDAP's `memberOf`) is on the members'
class, computed from the holder's references when they are names or identifiers (as `computed(...)` does), else in
the schema only. Without a stored schema the mirror has the attributes `schemaHandling` names; one that
only a script reads (`basic.getAttributeValue(projection, "category")`) is added with
`ResourceOptions.attribute("AccountObjectClass", "category")` (manifest:
`attributes: { AccountObjectClass: [category] }`); one multi-valued on the real resource (a list of parameters)
that `schemaHandling` gives no `limitations/maxOccurs` is made so with
`ResourceOptions.multiValued("Connection", "parameters")` (manifest: `multiValued: { Connection: [parameters] }`).
A multi-valued attribute the target system keeps as a map of `key=value` values (Guacamole's connection parameters)
is declared with its separator, `ResourceOptions.keyedValues("Connection", "parameters", "=")` (manifest:
`keyedValues: { Connection: { parameters: "=" } }`): a value added replaces the value of its key
(`disable-paste=false` replaces `disable-paste=true`) and a value removed removes its key, with or without a stored
schema; a plain multi-valued attribute would keep both values. `keyedValues` is the shortcut of the general form, a
Groovy expression that makes the values the target system keeps when midPoint writes the attribute:
`ResourceOptions.store("Connection", "parameters", expression)`, in the manifest

```yaml
targets:
  Remote desktops:
    store:
      Connection:
        # values: kept so far (empty on create); add / remove: what midPoint adds and removes (the values it creates
        # the object with are add); replace: the values it replaces them with, or null; name: the object's name
        parameters: |
          def key = { it.split('=')[0] }
          def kept = (replace ?: values).findAll { v -> !(remove + add).any { key(it) == key(v) } }
          kept + add
```

returning the values to keep (what a target system does beyond storing them: one per key, lower case, sorted, a
default added). An attribute has `keyedValues` or `store`, not both (`StoredValuesTest`).
Classes named by different attributes take one each:
`ResourceOptions.namingAttribute("Group", "displayName")` (manifest: `namingAttribute: { User: userName, Group:
displayName }`). An identifier the target system issues on create (a certificate's serial and validity, an
order number) is made by a Groovy expression of the created object's `name`, its other `attributes` (name -> list of
values) and `now` (the tester's clock, an `Instant`): `ResourceOptions.identifier("certId", expression)`
(manifest: `identifier: { attribute: certId, value: "name + '/' + now.toEpochMilli()" }`); without the expression a
generated identifier is a random UUID. A connector that gives ConnId's `__UID__` no native name (a stored schema
shows it as `icfs:uid` beside `icfs:name`) may still issue it apart from the name, which the schema does not say:
`identifier: icfs:uid` (or with a `value`) makes the mirror issue it, so a rename keeps it; without it the
identifier is the name (`GeneratedIdentifierTest`). The resources the tester cannot stand in for are reported together, with the
manifest's other mistakes, in one load failure. `TargetSystem` has the same calls for accounts, groups (the
first non-account object type) and any object class by name (`addObject(objectClass, ...)`, `identifier(...)` for
references by `__UID__`). Changes made there are seen by midPoint when it next reads the object, and by the next
live synchronization run: the mirror connector synchronizes as the CSV connector does, a token standing for the
mirror's content at that moment and a run reporting the objects added, changed (name, status, password or stored
attributes) and deleted since (see [Live synchronization](#live-synchronization)).

### Real connectors

`ResourceOptions.defaults().realConnector()` keeps a resource on its real connector instead of the mirror, for the
behaviour the mirror does not reproduce: CSV parsing and quoting, LDAP schema checks, DN handling, how the connector
names attributes (the CSV connector shows the unique column as `ri:<column>`, not `icfs:name`). Supported: the
Evolveum CSV connector (`com.evolveum.polygon:connector-csv`) and LDAP connector (`connector-ldap`, `LdapConnector`;
not `AdLdapConnector`). The connectors are the versions the midPoint line ships (see
[docs/line-anatomy.md](docs/line-anatomy.md)); they are dependencies of the tester artifact (bundle jars, their
libraries inside, discovered by midPoint on the classpath).

```java
mp.importResource(new File(CONFIG, "resource-hr.xml"), ResourceOptions.defaults().realConnector());
mp.importResource(new File(CONFIG, "resource-ldap.xml"), ResourceOptions.defaults().realConnector());

mp.csv("HR").addRow(Map.of("empno", "1001", "firstName", "John", "lastName", "Doe"));  // write(rows), writeText(text)
mp.ldap("LDAP").loadLdif("dn: ou=people,dc=example,dc=com\nobjectClass: organizationalUnit\nou: people\n");
mp.ldap("LDAP").entry("uid=e1001,ou=people,dc=example,dc=com");                      // search(filter), ldif()
mp.target("LDAP").groupValues("cn=it,ou=groups,dc=example,dc=com", "member");          // TargetSystem, as on a mirror
```

What the tester changes in the definition (the resource as imported is in `<workDir>/tester-resources/`):

| | Changed | Kept |
|---|---|---|
| Both | `connectorRef`: the OID of the line's connector of the type the configuration properties' namespace names (a version in a filter is not kept); a stored `<schema>` and the native capabilities are taken out (read again from the connector) | `schemaHandling`, synchronization, every other property and the ConnId framework settings |
| CSV | `filePath` -> `<workDir>/tester-csv/<oid>.csv`, `tmpFolder` -> `<workDir>/tester-csv/<oid>-tmp` | delimiter, quote, escape, encoding, unique/name/password columns, `readOnly`... |
| LDAP | `host` -> `127.0.0.1`, `port` -> the server's port, `connectionSecurity` -> `none`, `bindPassword` -> the tester's; `servers` removed; `bindDn` set to `cn=tester` only when missing | `baseContext`, `bindDn`, paging, password hashing and every other property |

- **CSV**: the file starts with a header: the unique column, the name and password columns (as configured), then the
  `ri:` attributes of the account object types of `schemaHandling` and the attribute of a simulated activation status;
  `writeText` replaces it (e.g. with a sample
  export). `CsvFile` writes and reads with the resource's delimiter, quote, escape, comment marker, record separator,
  multi-value delimiter and encoding (the connector's defaults: `;`, `"`, `\`, `#`, CRLF, none, UTF-8). As a
  `TargetSystem`, accounts are the rows named by the name column; there are no groups. Live synchronization: the
  connector copies the file to `<tmpFolder>/<file>.sync.<token>` (the token is the time in milliseconds) and a run
  reports the rows added, changed and deleted since the copy of its token.
- **LDAP**: an UnboundID LDAP SDK `InMemoryDirectoryServer` per resource on the loopback interface, serving
  `baseContext` with the SDK's standard schema (RFC 4512/4519/2798...; note that it has `member` optional in
  `groupOfNames`), checking entries against it (required and allowed attributes, syntaxes, single structural class)
  and generating `entryUUID`. The base entry is created (`domain`, `organization`, `organizationalUnit` by its RDN);
  the tree below it comes from the test (`loadLdif`, or `TargetSystem.addObject`, which adds the object class's
  superior classes and the RDN attributes). `ResourceOptions.referentialIntegrity()` turns on the server's referential
  integrity for the association attributes of `schemaHandling`. No `memberOf`, no password policy, no access control.
  No native activation either (the `LdapConnector` has none for a generic server, and server lock attributes such as
  389 DS `nsAccountLock` or OpenLDAP `pwdAccountLockedTime` are not in the standard schema): simulate the status on an
  attribute the schema has (`fixtures/activation/resource-directory.xml` uses `employeeType`). The server keeps a
  change log (`cn=changelog`, the root DSE's `changeLog`, `firstChangeNumber` and `lastChangeNumber`), which the
  connector's live synchronization reads by change number (`synchronizationStrategy` `auto` finds it; `sunChangeLog`
  names it). The in-memory server's change log differs from those the connector is written for in two ways
  (`LiveSyncRealConnectorsTest`): a deletion has no `targetUniqueID`, so the connector (with `uidAttribute`
  `entryUUID`) skips it and the shadow stays; a rename is `changeType: moddn`, which the connector does not know (it
  knows `modrdn`), so the run fails and midPoint suspends the task.
- Resets restore the file and the server content like a mirror's, with the CSV connector's copies for live
  synchronization and the LDAP server's change log, so the token in the restored database finds its copy or its change
  number; a class that no longer uses the resource stops its server.

**Extension schemas**: `-Dtester.extensionSchemaDir=<dir>` (the `*.xsd` files as deployed to midPoint's `schema`
directory, read when midPoint starts: one schema per test JVM, i.e. per line module). With it, extension items work as
in production: in fixtures (`<extension><ext:badge>...`), as mapping sources and targets (`extension/ext:badge`), in
changes (`change(...).replace("extension/badge", ...)`: the item name without its namespace, which the schema gives;
or an `ItemPath` with the QName), and in the `assignment` variable of constructions, whose extension midPoint merges
along the assignment path. Set it in the surefire configuration of the module that runs your tests:

```xml
<systemPropertyVariables>
    <tester.extensionSchemaDir>${project.basedir}/../config/schema</tester.extensionSchemaDir>
</systemPropertyVariables>
```

A deployment whose lines need different schemas points each line module at its own directory. The tester's own tests
use `tester/core/src/test/resources/schema` (`ExtensionTest`).

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
| `reconcile(resource[, kind, intent])`, `importAccounts(resource)` | a single-run reconciliation / import task of the resource's object type (default `account/default`) as the GUI starts one (an activity on 4.4+, the task handler on 4.0), run and waited for; a later call replaces and re-runs it |
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
evaluated (`Evaluation.task()` is the task's name). `HrToLdapScenarioTest` (fixtures in
`tester/core/src/test/resources/fixtures/scenario`) runs one on every line, on mirrors (`HrToLdapMirrorTest`) and on
the real connectors (`HrToLdapRealConnectorsTest`):

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

The same scenario driven by live synchronization (`LiveSyncScenarioTest`, fixtures in
`tester/core/src/test/resources/fixtures/livesync`, on the real connectors and on mirrors): a deployed live
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
- The token is kept where midPoint keeps it (the task's activity state on 4.4+, its extension on 4.0) and restored
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
result on every line although the variables differ: 4.0 and 4.4 give a correlation or reaction condition no `focus`,
4.8+ an empty pre-focus (see [Line notes](#line-notes)); both go by the shadow's owner (`FocusSelectionTest`).

**Shadows.** `mp.shadow(focus, resource[, kind, intent])` reads the shadow the focus links on the resource from the
repository (no target system read): a live one before dead ones (kept linked as `related`), failing when several live
ones match without kind and intent. `mp.situation(focus, resource)` is its `situation()`. The five situations
(`LINKED`, `UNLINKED`, `UNMATCHED`, `DISPUTED`, `DELETED`), kind, intent, `dead` and the synchronization timestamps are
the same on 4.0 to 4.10; `shadow.asObject()` gives the rest (e.g. `getCorrelation()`, 4.8+).

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

// report import (4.4+): an import task as the GUI's "Import report", on CSV lines (or a file)
Reports.Import in = mp.reports().importFrom("import-users", List.of(
        "\"Name\";\"Full name\";\"Cost center\"",                     // skipped: columns go by view order
        "\"sato\";\"Jiro Sato\";\"it-300\""));
in.status(); in.created(); in.modified();                         // PARTIAL_ERROR when some rows failed
in.run().select().within("column", "name", "costCenter");         // .../column[costCenter]/import/expression

// dashboards: DashboardService, as the dashboard page
Dashboards.Widget widget = mp.dashboards().widget("admin-dashboard", "employees");
widget.number(); widget.color(); widget.run().select().path("variation").element("condition");
```

**Time.** `t.clock()` moves midPoint's clock and the JVM's wall clock (`new Date()`, `Instant.now()`, Groovy's
`System.currentTimeMillis()`...), not Quartz, `System.nanoTime()` or the database. See
[Activation and validity](#activation-and-validity). The moved clock runs on; `t.clock().stopAt(instant)` (or a
`LocalDateTime` in the JVM's time zone) stops it there, to the millisecond, until the test ends, for a time window
checked at its bounds (a script that compares `LocalTime.now()` with 22:00): `t.clock().stopAt(day.atTime(21, 59, 59,
999_000_000))`, then `advance(Duration.ofMillis(1))` to 22:00:00.000 (`StoppedClockScenarioTest`).

Users who complete work items or decide need the authorization to: the baseline has midPoint's Approver and Reviewer
roles to assign them. The acting user is given by name; the call runs logged in as that user, in a task owned by them.

**Approvals of an object change** (an object rule, e.g. a policy rule in the inducement of a role the object has,
with a `modification` constraint and an approval action) work the same: `preview`, `request`,
the cases and work items. Their expressions get `object` as it was before the change and no `target`. A
`processSpecification` with `deltaFrom` takes only those items into the case; the rest of the change is a case of its
own (`Changes that do not require approval`, in `cases()`), executed together with the approved part when the
approvals end. Its `approvalDisplayName` names the case, before the action's, which is then not evaluated
(`ObjectApprovalTest`).

**Approval names and timed actions.** Without an `approvalDisplayName` (of the process specification, then of the
action), a case is named by the short message of the constraint that triggered (`presentation/shortMessage`; the
`longMessage` is never evaluated). A process specification's display name gets `object` after the change, the
approver expressions before it (`ApprovalNamesTest`). The timed actions of an approval stage (`delegate`,
`escalate`, `complete`, `notify`) run when their trigger is due: move the clock and run the trigger scanner. Their
conditions are not implemented by midPoint: never evaluated, the actions run regardless (`TimedActionsTest`).

**Certification stages, iterations and timed actions.** Each stage's `reviewerExpression` runs when that stage opens,
for the cases still under review (with the default outcome strategy a case revoked in a stage is not reviewed in the
next one). `reiterateCampaign` on a closed campaign puts the cases without a response into the next iteration; the
next `openNextStage` evaluates their reviewers again, and the driver labels of that iteration name it (`open stage 1
of role-assignments iteration 2`). Certification work items are delegated and escalated only by a stage's
`timedActions` (midPoint has no service call for it): move the clock and run the trigger scanner. A stage's deadline
is rounded to the end of its day (`deadlineRounding` `day`), and the stage closes at its deadline in the same scan,
so a timed action is easiest to test before the deadline (`time/value` negative, `CertificationStagesTest`).

**Report parameters with several values.** A `Collection` as a parameter value gives the parameter several values,
as a report task's `reportParam` may hold them (the GUI gives one). 4.8+ pass them to the expressions as a list (a
script filter returning it matches any of them; a `path` expression of the parameter fails on the list: "Unexpected
variable value"); 4.4 fails the export ("Attempt to get single value from item ... with multiple values")
(`ReportTest.severalParameterValues`). A failed export task throws `OperationFailed` with the first message of the
task's result tree.

**Report import** (`importFrom`) needs a report with `behavior/direction` `import`. `importFrom(report, lines,
username)` imports as that user, as when the user imports in the GUI: the import task is theirs, so the import
script's `actor` is the user and their authorizations apply (`ReportImportScenarioTest`). The lines are written to
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
  `validFrom`/`validTo` outbounds send (`PasswordAndValidityTest.accountIsValidUntilADayAfterTheUser`). Without an
  `administrativeStatus` outbound midPoint does not manage the status: a disabled user keeps an enabled account
  (`ActivationTest.administrativeStatusDisablesAndEnablesTheAccount`, Wiki).
- **Existence** (`existence/outbound`). Without one an account exists while it is legal (a valid assignment gives it)
  and is deleted otherwise. *Disable instead of delete*: a weak `$focusExists` existence mapping and a **strong** status
  mapping `legal ? input : DISABLED`. It must be strong: when the validity scanner recomputes a user whose assignment
  ended, `legal` is false before and after, so DISABLED is in the zero set, which a normal mapping does not apply
  (`ActivationTest.aNormalDisableMappingMissesTheEndOfAnAssignment`). *Delayed delete*: an existence mapping returning
  `false` with `timeFrom` = `$shadow/activation/disableTimestamp` + an offset and a condition on the shadow's
  `disableReason` (`#deprovision`); midPoint sets a recompute trigger on the shadow for that time, and the trigger
  scanner deletes the account (`fixtures/activation/resource-crm.xml`).
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
constraint of a mapping (`timeFrom`, `timeTo`) waits for. The scenarios are `ActivationTest` (mirror: native status,
no status mapping, user and assignment validity) and `ActivationScenarioTest` (simulated status, disable instead of
delete, delayed delete), which runs on mirrors (`ActivationMirrorTest`) and on the real CSV and LDAP connectors
(`ActivationRealConnectorsTest`), with the same results on every line but one note (see [Line notes](#line-notes)).

**What moves with `t.clock()`.** `advance` moves midPoint's `Clock` (its test override) and, by the same offset, the
JVM's wall clock as code reads it, so a script sees the advanced time however it reads it
(`ActivationTest.aMappingSeesTheAdvancedTime`, `scriptsSeeTheAdvancedTime`, `ClockTest`); the reset after each test
sets both back to the real time. With the tester's Java agent (the one of [Files scripts read and
write](#files-scripts-read-and-write), attached or given with `-javaagent`), on every line and JDK (11, 17, 21):

| Moves | Does not move |
|---|---|
| midPoint's `Clock`: the validity and trigger scanners, time constraints of mappings, `basic.currentDateTime()`, the timestamps midPoint writes (`disableTimestamp`, triggers, audit) | `System.nanoTime()` (monotonic: the tester's own waits use it, so a driver call after `advance("P30D")` does not time out) |
| `new Date()`, `Calendar.getInstance()`, `new GregorianCalendar()` | `System.currentTimeMillis()` called from **Java** code (a native method of a class loaded before any agent: it cannot be redefined); midPoint's code uses its `Clock` instead, custom Java libraries may not |
| `java.time`'s system clock: `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`, `ZonedDateTime.now()`, `Clock.systemUTC()`... | Quartz (it keeps the real time, see below) |
| Groovy's `System.currentTimeMillis()` (`System`'s meta class: dynamic Groovy, not `@CompileStatic`) | the database (`now()` in PostgreSQL; midPoint writes its timestamps from its `Clock`), connectors' and other processes' time |
| `basic.fromNow(...)` and `XmlTypeConverter.createXMLGregorianCalendar()` (prism's `System.currentTimeMillis()` calls, replaced) | |

How: Byte Buddy advice adds the offset where the JDK reads the wall clock (`Date()`, `GregorianCalendar(TimeZone,
Locale)`, the calendar provider, `Clock.currentInstant()` on JDK 21 or the system clock's `instant()` on 11 and 17,
`millis()`), and midPoint's `Clock` keeps its own override at the same offset (it adds it to
`System.currentTimeMillis()`). At start the tester checks every source on this JVM; if one is not shifted (or there
is no agent), it prints why and only midPoint's `Clock` moves (the tester says so at start).

**Quartz keeps the real time.** Its scheduler thread waits on `System.currentTimeMillis()`, so a trigger made on a
shifted `new Date()` (`startNow()`) would wait for the real time to catch up: Quartz's own reads of `Date` and
`Calendar` are left unshifted. Its triggers go on firing on the real time, so from the first `advance` until the
reset the tester holds the regular runs of recurring tasks: a listener vetoes every firing of the standard trigger of a
loosely bound recurring task (not a pause, which midPoint undoes whenever it synchronizes a task), except the one
"Run now" re-creates to start the task. A task with a short interval therefore does not run by itself in the middle of
`runValidityScanner()` or between driver calls; `t.clock().runDue()` runs once each task due by midPoint's clock,
and `mp.runTask` still runs a task exactly once (`TaskRunsTest.recurringTaskRunsOnItsScheduleByMidPointsClock`,
`aRecurringTaskRunsOnlyThroughTheDriverOnceTheClockHasMoved`, `aRecurringTaskAddedAfterTheClockHasMovedIsHeldToo`).
Tightly bound tasks (a thread that sleeps between runs) are not held. In production the real time passes for
everything at once; that difference is the point of the tester's clock. Log lines written after an advance carry the shifted time.

**Quartz keeps its jobs in memory.** The tester starts midPoint with the in-memory job store
(`<jdbcJobStore>false</jdbcJobStore>`), midPoint's default on one node; a cluster (`taskManager.clustered`) keeps them
in the database by default (the JDBC job store). The reset restores the task rows, not Quartz: it holds the scheduler,
has midPoint synchronize the jobs with the restored rows (`TaskManager.synchronizeTasks`), puts every recurring trigger
back on the schedule of the restored task (its next run after its last one) and drops the other triggers. A trigger the
test resumed stays resumed for a task the restore made suspended again; the due tasks check the task's state, so it does
not run. The JDBC store would not spare that work: its rows hold the fire times of the snapshot on the real time, past
by the restore, so every trigger would misfire on the release. What a test cannot see of a deployment on the JDBC store:
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

Scripts midPoint runs without an `ExpressionFactory` (model hooks, `execute-script` actions, report columns and
subreports) are recorded too, and located by their canonical form. The script of an `execute-script` action is the
`value` of its `script` parameter, read as the `<script>` midPoint makes of it: its identity is
`.../action/parameter[script]`.

## Files scripts read and write

Bulk actions, task scripts, hooks and mappings often read input files (`/opt/midpoint/var/import/hr.csv`) and write
output files, themselves or through Java utilities; reports write their exports. A test maps the directories the
configuration names to directories of its own, provides the input files there and asserts what was written. The real
directories are never touched.

```java
@Test
void export(MidPointConfigTester t) {
    MidPoint mp = t.midpoint();
    mp.files().map("/opt/midpoint/var")                                    // and everything under it
            .write("/opt/midpoint/var/import/hr.csv", "id,name\n1001,John Doe\n");
    Answer<BulkActionOutput> out = mp.bulkAction(xml);  // new File('/opt/midpoint/var/export/users.csv').withWriter {...}
    WrittenFile users = out.run().fileWritten("/opt/midpoint/var/export/users.csv");
    assertThat(users.lines()).containsExactly("1001;JOHN DOE");
    assertThat(mp.files().read("/opt/midpoint/var/export/users.csv")).isEqualTo(users.text());
}
```

| Call | What it does |
|---|---|
| `mp.files().map(dir)` | maps the directory (absolute, as the configuration names it) to `<workDir>/tester-files/<dir>` |
| `write(path, text \| bytes)`, `read(path)`, `lines(path)`, `bytes(path)`, `exists(path)`, `list(dir)` | the files by the paths the configuration uses; `write` creates parents, `list` gives every file below |
| `physical(path)`, `mapped()`, `exportDirectory()` | where a file really is; the mapped directories; midPoint's report output directory |
| `run.filesWritten()`, `run.fileWritten(path)`, `run.filesDeleted()` | what one driver call created, changed or deleted (`WrittenFile`: `path()`, `name()`, `created()`, `text()`, `lines()`, `bytes()`) |

The directories the deployment's tasks write in, as its volume has them, are mapped by the manifest for every test, with
the tree they start with (files, and empty directories kept in git by a `.gitkeep`, which is not copied):

```yaml
files:
  map: [/opt/midpoint/var]
  initial: { /var/lib/midpoint: files/var-lib-midpoint }   # also mapped; the tree comes back before every test
```

**How the mapping works.** At its start the tester attaches a Java agent (Byte Buddy) that instruments three JDK
classes, so the redirection holds for **any code in the JVM**: Groovy scripts, Java utilities they call (custom jars,
Apache Commons IO or CSV, midPoint's own code), the test itself.

- Every constructor of `java.io.File`: at its end, a path under a mapped directory is replaced by the physical one.
  `FileInputStream`, `FileOutputStream`, `RandomAccessFile`, `FileReader`, `FileWriter`, `PrintWriter` construct a
  `File` from a name, so they follow, as do all `File` methods (`exists`, `listFiles`, `mkdirs`, the GDK's `text`,
  `eachLine`, `withWriter`...).
- Every constructor of `sun.nio.fs.UnixPath`, the default file system's `Path` on Linux and macOS: the same for its
  bytes. `Path.of`, `Paths.get`, `File.toPath`, `Path.resolve` (also from a parent that is not mapped) and thus all of
  `java.nio.file.Files` (streams, channels, directory streams, attributes, `walk`) follow.
- `java.lang.ProcessBuilder.start()`, which every process the JVM starts goes through (a report's post-report script
  run by the shell, Groovy's `"...".execute()`, `Runtime.exec`): a mapped directory written in the command's arguments
  is replaced by the physical one, where it starts a path (at the start of an argument or after a space, a quote, `=`,
  `;`...). A path the command builds itself (`cd /opt; ls midpoint/var`, a variable or the environment) is not seen,
  nor is the process's environment rewritten. A mapping in the process itself (a bind mount, a container) would need
  root or a container runtime; the tester stays at what the JVM starts.

The table of mapped directories lives in a class on the bootstrap class path (`FileRemap`), which the instrumented
constructors consult: one volatile read when nothing is mapped, a prefix comparison per mapped directory otherwise. Only
absolute paths under a mapped directory are rewritten, per path segment (`/opt/midpoint/var` does not hold
`/opt/midpoint/variable`), the innermost mapped directory wins, and a physical path is never rewritten again.

**The agent** (also the one that shifts the JVM's clock for `t.clock()`). Nothing is needed by default: the tester attaches the agent itself (`ByteBuddyAgent.install()`), which
works on JDK 11, 17 and 21; JDK 21 prints a warning about a dynamically loaded agent (and a future JDK will refuse it
without `-XX:+EnableDynamicAgentLoading`). To give it to the JVM instead, add the agent jar of the tester's
`byte-buddy-agent` dependency to the surefire `argLine`, as the tester's own 4.8+ modules do:

```xml
<plugin>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions><execution><goals><goal>properties</goal></goals></execution></executions>
</plugin>
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>... -javaagent:${net.bytebuddy:byte-buddy-agent:jar}</argLine>
    </configuration>
</plugin>
```

If the agent cannot be installed, the tester logs why at its start and `mp.files().map(...)` fails with that reason.

**midPoint's own files.** Report exports are written by midPoint's Java code to `<midpoint.home>/export`
(`ReportUtils.getExportDir()` on every line), which is already a directory of the tester (`<workDir>/midpoint-home`).
It is watched like a mapped directory, by its real path: an export's CSV is in `run.filesWritten()` of the
`mp.reports().export(...)` call (or of the task run that exported it), next to `Reports.Output.lines()`.

Report imports (`mp.reports().importFrom(report, file)`) copy their CSV to `<midpoint.home>/import/` themselves; a
mapped input file can be given by its configured path (`new File("/opt/midpoint/var/import/hr.csv")`), since the
test's own code is redirected too.

The files of CSV resources on the real connector (`mp.csv()`: `filePath` moved to `<workDir>/tester-csv/`) and the
`redirectToFile` and `logToFile` files of mail transports (moved to `<workDir>/tester-mail/files/`, see
[Mail notifications](#mail-notifications)) are moved by rewriting the configuration when it is imported; they are not
mapped directories and need no mapping.

**What a run wrote** is found by comparing the content of the mapped directories and the export directory before and
after the driver call, so files written by Java code (midPoint, a custom library) into them count too.

**Lifecycle.** Mapped directories and their content are part of the reset: what `@BeforeAll` methods mapped and wrote
(input files) is there in every test method of the class; what a test method mapped, wrote or deleted, and the report
exports, are gone after it (the database snapshot and the files are restored together). The next test class starts
with no mapped directory.

**Policy for other paths.** Only mapped directories are redirected; every other path is left as it is, so a script
reading a file outside them reads the real file system (`FilesTest.pathsOutsideMappedDirectoriesAreLeftAlone`). Map
every directory your scripts use. `write` and the other `mp.files()` calls refuse paths outside mapped directories (and
the export directory), and a directory holding the tester's own `<workDir>` cannot be mapped.

**Limits.**

- Code sees the physical path: `file.path`, `absolutePath`, `Path.toString()` or a path written into an attribute or a
  log shows `<workDir>/tester-files/...`.
- Only absolute paths are mapped. A relative path is resolved against the test's working directory (not midPoint's);
  `..` is not resolved before the comparison (`/opt/midpoint/../midpoint/var` is not under `/opt/midpoint/var`).
- `java.nio` paths are redirected on the Unix file systems of the JDK (Linux, macOS); on Windows only `java.io.File`
  (and what is built from it) is. Paths of other file systems (zip, Jimfs) are not touched.
- Not redirected: native code, processes started with `ProcessBuilder` or `Runtime.exec` (they get the physical path
  only if the Java code passes a `File` or `Path` as text), and a `File` or `Path` constructed before the directory was
  mapped (e.g. kept in a static field), which keeps the path it had.
- File names are compared in UTF-8 bytes for `java.nio` paths (the JDK's encoding of file names on Linux and macOS).
- Report exports go to midPoint's own `<midpoint.home>/export`; midPoint has no setting for another directory, so map
  nothing for them.

## Hooks, notifications and authorizations

### Extension beans

A deployment's own Spring beans (a change hook, a service its scripts call) are not in the tester's context. Put a
Spring XML file `tester/ctx-extension*.xml` on the test classpath (`src/test/resources/tester/ctx-extension.xml`):
the tester's context imports every one, e.g. a component scan of the jar's packages and the properties the beans
need:

```xml
<context:property-placeholder location="classpath:tester/extension.properties" ignore-unresolvable="true"/>
<context:component-scan base-package="com.example.idm.hooks"/>
```

```java
run.hooks("forbid-test-users");          // HookRun: name(), state(), focusType(), focusName(), evaluation()
run.notifications(); mp.notifications(); // messages sent to dummy transports: per run / since the test began
run.mails(); mp.mails();                 // mails sent by midPoint's mail transport over SMTP (next section)
Answer<List<UserType>> visible = mp.actor("jdoe").search(UserType.class, null);   // also get, isAuthorized, isAuthorizedForAny, editDefinition, execute
```

- Scripting model hooks of the system configuration: each run with the Clockwork state and the focus when it started.
- Notifications are enabled as in production. A notifier whose transport is `dummy` (e.g. `dummy:hr`) or a custom
  transport named `dummy...` sends to a recording transport; `Notification` has transport, to, cc, bcc, subject, body.
  Notifier filters and expressions are recorded.
- `mp.actor(user)` makes one call as that user through `ModelService` and the security enforcer (authorization filter
  expressions are recorded); a denial is an answer (`value()` null, `run().failure()` the `SecurityViolationException`),
  not an exception.
- Without a logged-in user, midPoint evaluates authorizations in tasks: a task runs as its owner, so its model calls
  (e.g. a bulk action's search) apply the owner's authorizations, their filters evaluated with the owner as `subject`
  (`AccessTest.taskSearchesWithItsOwnersAuthorizations`: import a task with `ownerRef` to a user with limited roles).
  No production path evaluates
  authorization filters with no principal at all (an anonymous request has only `ROLE_ANONYMOUS`, a privileged call
  only the unrestricted authorization), so there is no driver for it.

**Channels.** A hook or a script may act on the channel a change came through (`context.getChannel()`: gRPC or REST
of a service that calls midPoint, a GUI page). `t.inChannel(channelUri, () -> ...)` runs the driver calls of the block
in that channel (their tasks carry it), e.g.
`t.inChannel("http://midpoint.evolveum.com/xml/ns/public/model/channels-3#grpc", () -> t.midpoint().modifyAdd(...))`;
outside a block the calls run in midPoint's default user channel (`ChannelScenarioTest`).

**Calls on a user's behalf.** A service that calls midPoint for its users (a portal over midPoint's API) logs a user
in, maybe privileged, owns the tasks for them and comes through its channel. `t.callAs(caller, () -> ...)` makes the
driver calls of the block that way, with a `Caller`: `Caller.user("jdoe")` (the user's own authorizations),
`.privileged()` (all authorizations on top, as midPoint's `runPrivileged` and a service account's "run privileged"
option), `.owner("jdoe")` (the owner of the tasks the calls create, the requester of the approvals they start; the
user by default) and `.channel(uri)`. Hooks, scripts, authorizations, approvals and the audit's initiator then see the
calls as in production; a call of the block that names its own user (`t.actor(...)`) keeps it (`CallerScenarioTest`).

```java
t.callAs(Caller.user("jdoe").privileged().channel(API_CHANNEL), () -> t.midpoint().addObjectXml(serviceXml));
t.callAs(Caller.user("api-client").owner("jdoe"), () -> t.midpoint().bulkAction(script));
```

**Code of the deployment the test calls itself.** A class of an add-on that midPoint does not run by itself in the
tester (a service of an API add-on, whose server needs midPoint's web application) is made with midPoint's beans
injected by `t.midpoint().createBean(type)`, and called as a driver call by `t.midpoint().invoke(label, (task, result)
-> ...)`: in the call's task (the `callAs` caller's), its evaluations recorded, the background work it starts waited
for, failing as a driver call fails (`CallerScenarioTest`). The add-on's protocol (how its server authenticates and
builds the call) stays in the project's tests.

## Mail notifications

Mail notifiers are tested with the configuration as deployed: midPoint's own mail transport builds each message
(from, recipients, encoded subject, body and content type, attachments) and sends it over SMTP to an in-memory server
in the tester's JVM ([GreenMail](https://greenmail-mail-test.github.io/greenmail/)). Nothing is mocked between the
notifier and the wire.

```java
Run run = mp.addObjectXml(userXml);
Mail mail = run.mails().get(0);          // also mp.mails(): every mail since the test method started
mail.from(); mail.to(); mail.cc();       // header addresses
mail.bcc();                              // envelope recipients in neither To nor Cc (no Bcc header is sent)
mail.recipients();                       // the SMTP envelope (RCPT TO)
mail.subject();                          // decoded; mail.header("Subject") is the encoded header
mail.body(); mail.bodyContentType(); mail.isHtml();
mail.attachments();                      // fileName(), contentType(), contentId(), content(), text(), size()
mail.contentType(); mail.headers(); mail.raw();   // multipart/mixed; headers as received; the MIME text
```

**What the tester changes on import.** Every import of a system configuration (`importObject`, `importObjects`,
`importObjectXml`, `importObjectRaw`, and `addObjectXml`) rewrites its mail transports before midPoint reads them:
`notificationConfiguration/mail` (4.0, 4.4; the legacy `mail` transport on 4.8+) and each
`messageTransportConfiguration/mail` (4.8+). In each of their `server` elements:

| Element | Becomes |
|---|---|
| `host`, `port` | `127.0.0.1` and the in-memory server's port (added when missing) |
| `transportSecurity` | removed: plain SMTP (no STARTTLS, no SSL) |
| `username`, `password`, `basicAuthentication`, `oauth2Authentication` | removed: no authentication (a password encrypted with the production key could not be decrypted here) |
| `redirectToFile`, `logToFile` (of the transport) | the same file name under `<workDir>/tester-mail/files/` |

Everything else stays as written (`name`, `defaultFrom`, `debug`, black and white lists, `recipientFilterExpression`,
`recipientAddressExpression`, the notifiers). Each rewrite prints a `Tester: mail transports redirected` line and
writes the imported copy to `<workDir>/tester-mail/`. Changing the mail servers later through `execute`/`modify`, or
adding a system configuration with `addObject(bean)`, is not rewritten.

- **`redirectToFile`** is kept: a transport that redirects to a file still writes there (now under the working
  directory) and sends nothing over SMTP, so its messages are not in `mails()`; the import line says so. Remove it
  from the configuration you test if you want the mails.
- A `mail` transport without `server` sends nothing (midPoint warns "Mail server(s) are not defined"), as in
  production; the import line says so.
- **`debug`** sets `mail.debug` and JavaMail prints the SMTP dialogue to standard output.
- midPoint sends in the operation's thread (or a task's, which the drivers wait for) and the server stores a mail
  before it acknowledges it, so `run.mails()` is complete when the driver returns. `mp.mails()` is cleared before each
  test method.
- A transport's **`recipientFilterExpression`** (variable `recipientAddress`) takes effect only with `redirectToFile`:
  the recipients it refuses are written to the file, the others are sent. Without `redirectToFile` midPoint evaluates
  it and sends to every recipient anyway; a file transport never evaluates it. `recipientAddressExpression` (4.5+,
  variable `recipient`) gives the address of a recipient that is a user (`TransportRecipientsTest`).
- `notifications()` stays for the `dummy` transports; a notifier with both kinds of transport appears in both.
- TLS, authentication (basic and OAuth2) and the delivery to a real server are not tested: they are what the tester
  replaces.

**A mail that cannot be sent.** `t.mailServer().reject()` makes the tester's SMTP server refuse every mail from then
on in the test, `reject("jdoe@example.com")` the mails to those recipients, `accept()` takes them again: the server
answers `451` to the mail's data, so midPoint's transport (or a script's) fails as with a real server's error, and the
mail is not in `t.mails()`. The next test's server takes every mail (`ApprovalMailScenarioTest`).

## Logs

`t.logs()` is what the configuration's scripts logged in the test through `log` (`log.info`, `log.warn`,
`log.error`: INFO and up, with their arguments filled in and the exception logged with them), narrowed like mails:

```java
t.logs().info().containing("reissued").exactly(1);
t.logs().errors().none();
assertThat(t.logs().messages()).containsExactly("reissued: t1/alice for alice");
```

Scripts' `log` is midPoint's `com.evolveum.midpoint.expression` logger; a custom library's loggers are captured too
when the manifest names them (`logs: { capture: [com.example.idm] }`). The capture starts again before every test
(midPoint resets logback when it applies the system configuration's logging); those loggers then also write INFO to
`target/tester.log` (`LogsScenarioTest`).

## Audit log

midPoint audits to its repository database, as in production (4.0's SQL audit service, the native repository's from
4.4; the database is in memory): the deployment's own searches of the audit log (audit reports, dashboards, scripts)
work. `t.audit()` is what midPoint wrote to it in the test, `t.audit(() -> ...)` what it wrote while the code ran
(and the work the code waited for): the request and execution records of each change, with the target, initiator,
channel, task, outcome and deltas, narrowed like logs. The fields are those of a row of the audit table
(`m_audit_event`), by name rather than by number: what an audit report, or another system that reads the table,
would see.

```java
AuditRecord deletion = t.audit().ofType("DELETE_OBJECT").execution().targetType("UserType").target("jdoe").single();
assertThat(deletion.outcome()).isEqualTo("FATAL_ERROR");   // the account could not be deleted; the user is gone
t.audit(() -> t.task("Delete users").run()).ofType("DELETE_OBJECT").execution().exactly(2);
t.audit().ofType("MODIFY_OBJECT").initiator("jdoe").none();
```

The audit table goes back with the rest of the database before every test (`AuditScenarioTest`).

Another system that reads midPoint's database over JDBC (a script of another instance that queries the audit table)
sees the line's own tables and codes: 4.0 stores the event type and stage as ordinals in `m_audit_event`, 4.4 on as
names in `ma_audit_event`. `t.repositoryDatabase()` runs that system's query on midPoint's database, so the test fails
when a midPoint upgrade changes what it reads (read it only: a row written there bypasses midPoint):

```java
List<Map<String, Object>> rows = t.repositoryDatabase().rows(
        "SELECT targetOid, outcome FROM m_audit_event WHERE eventType = 3 AND eventStage = 1 AND timestampValue > ?", since);
```

## midPoint's constants

`<const>` expressions and `midpoint.getConst(...)` read the `<constants>` of midPoint's config.xml, which a deployment
sets per environment. The manifest gives them for the tests:

```yaml
constants: { tenantId: "13", portalHost: portal.example.test }
```

They are set before the import (a resource's configuration may use them) and again before every test
(`ConstantsScenarioTest`).

## HTTP services

A script that calls an HTTP service (a webhook of a chat, a REST API) reaches nothing in a test. The manifest's
`http.redirect` lists URL prefixes the tester stands in for:

```yaml
http:
  redirect: ["https://hooks.example.com"]
```

In the deployment's copy, `https://hooks.example.com/x` becomes `http://tester-http.invalid/hooks.example.com/x`; the
JVM's proxy selector sends that host to an in-memory HTTP server, which records the request and answers `200` with an
empty body. A test reads them as the configuration wrote them, since the test began:

```java
assertThat(t.httpRequests()).singleElement().satisfies(r -> {      // mp.httpRequests()
    assertThat(r.url()).startsWith("https://hooks.example.com/");
    assertThat(r.body()).contains("archived");
});
```

The server's port is the JVM's and not in the database (a cached deployment works in every fork), and HTTPS needs no
certificate (the copy says `http`). It covers the clients that ask the default `ProxySelector`, `java.net.URL`'s
connections among them (a Groovy script's `new URL(url).openConnection()`); a client that does not (Apache HttpClient
by default, the JDK's `HttpClient` without a proxy) reaches the redirected host, which resolves nowhere, and fails.
Such a client is given the server's own address instead: the system property `tester.http.url`
(`http://127.0.0.1:<port>`, set before midPoint starts), e.g. `issuer.base-url=${tester.http.url}/issuer` in the
properties of the deployment's beans (see [Extension beans](#extension-beans)); its requests are recorded with that
address.

**Answers.** A test sets what the server answers, for what a script does with a service's error or result:

```java
t.httpServer().on("POST", "https://hooks.example.com/").respond(503, "{\"message\":\"unavailable\"}");
t.httpServer().on("GET", "/issuer/api/cert").respond(200, "{\"serial\":\"01\"}");
```

`on` takes a method and a URL prefix as the configuration has it (with a scheme) or a path prefix (from `/`); the body
is sent as JSON when it is an object or an array, else as text (null: none). The last answer given for a request
wins; without one a request gets `200` and no body. The answers are forgotten after the test (`HttpStandInTest`).

## Other systems' databases and the scripts' environment

A script may read or write another system's database over JDBC (another midPoint's audit tables, an application's),
at a URL it reads from an environment variable. The manifest stands in for such a database with an in-memory
PostgreSQL of its own, created from SQL files (the other system's table definitions), and gives the scripts their
environment, which may name the stand-in (its URL is known only when the tester runs):

```yaml
databases:
  audit: { init: [databases/audit.sql] }
environment:
  AUDIT_JDBC_URL: "${database.audit.url}"
  AUDIT_JDBC_USER: "${database.audit.user}"
  AUDIT_JDBC_PASSWORD: test
```

```java
t.database("audit").execute("INSERT INTO m_audit_event (eventType, targetOid) VALUES (?, ?)", 3, oid);
assertThat(t.database("audit").rows("SELECT * FROM m_user")).isEmpty();
```

**Times.** The stand-in is part of the simulated world: a time a test writes for the other system is on the test's
clock, `t.clock().now()` (an `Instant` parameter is written as a timestamp), because the scripts read that clock too
(Groovy's `System.currentTimeMillis()`, `new Date()`, `basic.currentDateTime()`), also when they compare with the other
system's times on purpose without midPoint's clock: what they rely on is one time base for both. The stand-in's own
`now()`, `CURRENT_TIMESTAMP` and column defaults are the real time: give times as values. Groovy compiled statically
(`@CompileStatic`, an expression profile with type checking) and Java code read the real `System.currentTimeMillis()`
and do not follow the test's clock (see [Activation and validity](#activation-and-validity)).

Each test starts from the init scripts' content. `environment` is what Groovy's `System.getenv(name)` returns (the
process's environment for other names); Java code reads the process's environment, which Surefire's
`environmentVariables` sets for fixed values (`DatabaseScenarioTest`).

## Classes of the GUI

midPoint's GUI module is not on the tester's class path: a script or a custom class that uses one of its classes (a
utility of the GUI, `com.evolveum.midpoint.web.util.ExpressionUtil`, Wicket) fails with `unable to resolve class`.
Such a repository declares it, without a version (the parents manage it at the line's midPoint version, test scope):

```xml
<dependency>
    <groupId>com.evolveum.midpoint.gui</groupId>
    <artifactId>admin-gui</artifactId>
</dependency>
```

On 4.0 and 4.4 the GUI module is a war, whose classes are its `classes` jar: add `<classifier>classes</classifier>`.

The GUI is only a library then: classes load when code uses them, and the tester's Spring context (midPoint's own
context files, named one by one; no Spring Boot auto-configuration) neither scans its packages nor reads its context
files, so no page, filter or web server starts. It costs about half a second of the JVM's start (a larger class
path); its `logback.xml` is not taken for the repository's (the tester's logging stays).

## No other machine

A test reaches only the tester's stand-ins, all on this machine (the in-memory database, LDAP server, SMTP and HTTP
servers). The tester refuses every connection of the test JVM to another machine (`java.net.Socket` and NIO socket
channels: JavaMail, Apache HttpClient, `java.net.URL`, cloud SDKs) with a `ConnectException`, and fails the test after
it, also when midPoint or a script only logged the error (a mail notification does), and the load when it happens
during the import. A deployment that has to reach a host names it: `network: { allow: [ldap.example.com] }` in the
manifest, or `-Dtester.network.allow=host,...`. After the import the tester also checks that every mail server of the
stored system configuration is its own (`NetworkGuardScenarioTest`, `ScriptedMailScenarioTest`).

## Static checks

`midpoint-config-tester-static-<line>` (4.0, 4.4, 4.8, 4.9, 4.10) checks a directory of configuration XML against that midPoint line
without starting midPoint
(no Spring, no database; a few seconds, most of it initializing prism). Every finding carries the identity the tester
gives evaluations of the same place.

| Check | Finds | Severity |
|---|---|---|
| `COMPILE` | every Groovy place compiled on the line's classpath (model-impl, notifications, report, workflow, certification; no GUI, connector or custom jars, which `classpath(...)` adds): missing or removed classes, syntax errors. Missing methods are found only at run time | error |
| `SCHEMA` | prism parsing of each file, strict and in compatibility mode | error / warning |
| `UPGRADE` | ninja verify's `ObjectUpgradeValidator` per object, each item path located in the file | error for `CRITICAL`, warning for `NECESSARY`; skipped on 4.0, which has no validator |
| `LINT` | evaluator elements the line does not know; mappings sharing a name in one object; Groovy that reads the JVM's clock rather than midPoint's `Clock` (`jdk-time`: `new Date()`, `Instant.now()`, `basic.fromNow`...: fine with the tester's `t.clock()`, which moves both, not with a clock override of midPoint alone); elements told apart by position only | error / warning / warning / note |

```java
StaticReport r = StaticCheck.of(Path.of("config")).schemaDir(Path.of("config/schema"))
        .classpath(Path.of("lib"), Path.of("ext/target/ext.jar"))   // the deployment's own jars (a directory: its jars)
        .run();
r.write(Path.of("target/static-check.md"));
r.assertNoErrors(Finding.Check.COMPILE);   // AssertionError: one line per failing place
assertThat(r.errors()).isEmpty();          // also findings(check[, severity]), compileErrors(), places()
```

Without writing a test: `mvn test -pl tester/static/midpoint-4.8 -am -Dstatic.dir=<dir> [-Dstatic.schemaDir=<dir>]
[-Dstatic.classpath=<jar,dir,...>]` writes `target/static-check/<dir name>.md` and fails on compile errors; for
another build of the line's midPoint (a support build, a fork), add `-Dmidpoint48.version=<version>`. Use the module of your line, with
Maven on its JDK (4.0: JDK 11). What differs between the lines' checks (Groovy messages, upgrade statuses, evaluator sets) is in
`docs/redesign.md`.

## Line notes

Behaviour differences between the lines, found by the PoC tests: each pinned both behaviours
(`Lines.atLeast(mp, "4.9")`) with a comment naming the midPoint code. Those tests were removed in phase 4 (the last
column names them; `git show 1e39281^:tester/core/src/test/java/com/github/wadahiro/midpoint/tester/<Test>.java`), the
findings stand. Read them before porting expectations between lines.

| Difference | 4.0 | 4.4 | 4.8 | 4.9 | 4.10 | Pinned in |
|---|---|---|---|---|---|---|
| A report's CSV export | not tested (no object collection reports) | no byte order mark | 4.8.14: starts with a UTF-8 byte order mark (4.8.12: none); `ReportOutput` reads the header without it | not checked | not checked | `ReportScenarioTest` |
| Template mapping of an added user: the evaluation for the old state (empty sources) | kept: the final outcome is `new=["John Doe"] removed=["null null"] \| old=["null null"]`; a failing script fails there first | an intermediate outcome only | as 4.4 | as 4.4 | as 4.4 | `RecorderTest.mappingWithError` |
| Hook without `state` | secondary three times, each with the focus | primary, secondary, secondary without focus, final | as 4.4 | as 4.4 | as 4.4 | `ChangeTest.importObjectsIsOneRun` |
| Correlation | legacy `synchronization` format (fixtures-4.4), no correlation context | as 4.0 | `correlation` in the object type; `correlationContext`, `correlatorState` | as 4.8 | as 4.8 | `RecorderTest.correlationExpression` |
| `focus` of a correlation or reaction condition without an owner | null | null | an empty pre-focus (`UserType` without a name) | as 4.8 | as 4.8 | `SynchronizationTest.unmatchedStaffIsAdded` |
| A single correlation candidate | linked without confirmation | confirmed; a rejected one gives a new user | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.singleCandidateMustBeConfirmed` |
| Reaction conditions | none: an unmatched contractor is added | evaluated | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.unmatchedContractorIsAddedOnlyWithoutReactionCondition` |
| Import of an account no object type classifies | fails ("No objectclass specified") | fails ("No object class found for the shadow") | `classificationCondition` false: imported without a user | as 4.8 | as 4.8 | `SynchronizationTest.serviceAccountsAreNotSynchronized` |
| The 4.4 format: an object type's `baseContext`, the `synchronization` section (condition, correlation, confirmation, reaction conditions), `notificationConfiguration/mail` | the line's format (fixtures-4.0) | the line's format | still read (deprecated) with the same outcomes; the synchronization condition is the classification condition: a service account is imported without a user instead of failing | as 4.8 | as 4.8 | `LegacyFormatTest` |
| Import of an account midPoint created (already classified) | not pinned | the synchronization condition is evaluated again | the classification condition is not evaluated | as 4.8 | as 4.8 | `SynchronizationTest.directoryMayDisableButNotEnable` |
| Full-processing import of the system configuration | runs the autoassign mappings against it and fails | passes | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.fullProcessingImportOfTheSystemConfigurationRunsTheAutoassignMappings` |
| Deleting an account | its attribute outbounds are evaluated | only its existence outbound | as 4.4 | as 4.4 | as 4.4 | `SynchronizationTest.costCenterAssignsTheRoleAutomatically`, `HrToLdapScenarioTest.hrDrivesTheDirectoryThroughReconciliation`, `ActivationScenarioTest.deprovisioningDisablesAndDeletesThirtyDaysLater`, `ActivationTest.assignmentValidityEndsTheAccountsAsTheirResourcesDeprovision`, `DependencyTest.theDirectoryAccountGoesOnlyAfterTheAccountThatDependsOnIt` |
| Deleting a user: the constructions of its roles | not evaluated | evaluated with a false condition | as 4.4 | as 4.4 | as 4.4 | `HrToLdapScenarioTest.hrDrivesTheDirectoryThroughReconciliation` |
| A change the authorization refuses | the user template is evaluated first | refused before any mapping | as 4.4 | as 4.4 | as 4.4 | `AccessTest.isAuthorizedAndExecuteAsUser` |
| The last evaluation of a search filter after a change | `assignmentTargetSearch`: the old state's; `associationTargetSearch`: the new state's | the reverse | as 4.4 | as 4.4 | as 4.4 | `SearchTest.assignmentTargetSearchFollowsTheCostCenter`, `EntitlementTest.associationTargetSearchFindsTheGroupComputedFromTheUser` |
| Association inbound when a membership is added: the last evaluation | without the new membership | with it | as 4.4 | as 4.4 | as 4.4 | `EntitlementTest.associationFromLinkTakesOnlyTheGroupOfEachRole` |
| Resource outbound: `source`, `containingObject`, `thisObject`, `assignmentPath` | not bound | the resource, the resource, null, null | as 4.4 | as 4.4 | as 4.4 | `VariablesTest.resourceOutbound` |
| `iteration` in a role's construction | not bound | bound | as 4.4 | as 4.4 | as 4.4 | `VariablesTest.constructionInAnInducement` |
| Inbound `focus` variable of a new user | not pinned | not pinned | a `PrismObject` | the `UserType` bean; also the variables `association`, `object` | as 4.9 | `RecorderTest.inboundMapping` |
| Preview (`mp.preview`) | the user is not changed, but sequences advance and `createOnDemand` really creates (design experiment E3) | as 4.0 | `SIMULATED_PRODUCTION`: nothing written, no simulation result kept | `previewChanges` is `executeChanges` in `SIMULATED_PRODUCTION` mode; as 4.8 | as 4.9 | `RecorderTest.previewThenExecute` |
| Approvers of stage 1, in order | in the order of the approver expressions | the org (candidates) first | as 4.0 | as 4.0 | as 4.0 | `ApprovalTest.preview` |
| Approval display name: the object argument | its name | `UserDescriptiveName(<full name>, <name>)` | as 4.4 | as 4.4 | as 4.4 | `ApprovalDetailsTest.displayNameWithTheObjectDisplayInformation` |
| `automaticallyCompleted` returning `skip` | a case, completed when its stage opens | no case | as 4.4 | as 4.4 | as 4.4 | `ApprovalDetailsTest.skipInItsShortForm` |
| Certification stage opening and closing | in the call | in the call | in the call | a certification task (waited for; `Evaluation.task()` set) | as 4.9 | `CertificationTest.campaign` |
| An expression in a certification scope's `searchFilter` | never evaluated: the repository gets the filter as it is, an `equal` without a value matches the objects without the item (other users are certified) | as 4.0 | as 4.0 | evaluated (the stage opens in a search-based activity) | as 4.9 | `CertificationScopeTest.theSearchFilterSelectsTheCertifiedUsers` |
| Recipient expression returning a string | stays a string | as 4.0 | a `RecipientExpressionResultType` | as 4.8 | as 4.8 | `HookAndNotificationTest.notificationsPerRunAndPerTest` |
| Mail transports | one, `notificationConfiguration/mail`; `<transport>mail:hr</transport>` is that transport (`TransportRegistry` drops what follows `:`) | as 4.0 | named `messageTransportConfiguration/mail` transports, each with its own servers and `defaultFrom` (an unknown `x:y` falls back to `x`) | as 4.8 | as 4.8 | `MailNotificationTest.htmlMailWithAnAttachmentThroughAnotherTransport` |
| Result of a notifier's or message template's attachment expression | a `NotificationMessageAttachmentType` bean (normalized text: its properties) | as 4.0 | as 4.0 | a container (normalized text: its type only) | as 4.9 | `MailNotificationTest.anAttachmentComputedPerUser` |
| Effective status of a user whose lifecycle state is `archived` (MID-9026: `ARCHIVED` deprecated, never an effective status) | `archived` (also for `proposed`, `draft`), `archiveTimestamp` set | as 4.0 | `disabled`, `disableTimestamp` set, no `archiveTimestamp` (a filter on it finds no one) | as 4.8 | as 4.8 | `HrLifecycleScenarioTest.anArchivedUsersEffectiveStatus` (`User.isArchived()`) |
| A notification handler given as `xsi:type="CustomNotifierType"` (its `expression` builds the message) | run as a custom notifier (`EventHandlerRegistry` dispatches by class) | as 4.0 | every operation that notifies fails (`ClassCastException`: no `EventHandlerType`) | as 4.8 | as 4.8 | `HandlerNotifierTest` |
| `recipientAddressExpression` of a transport | none | none | the address of a recipient that is a user (e.g. a notifier's default recipient) | as 4.8 | as 4.8 | `TransportRecipientsTest.theMailTransportAddressesTheUser` |
| A notifier with two recipients | one mail to both | as 4.0 | one mail per recipient (`notificationSendingStrategy` default `separateNotificationToEachRecipient`) | as 4.8 | as 4.8 | `MailNotificationTest.oneMailPerRecipientFrom48` |
| Encoded mail subject | `=?utf-8?Q?...` | `=?UTF-8?...` | as 4.4 | as 4.4 | as 4.4 | `MailNotificationTest.plainTextMailWithCcAndBcc` |
| A failed `checkExpression` in `ValueCheck.failed()` | its `failureMessage` | its display label | as 4.4 | as 4.4 | as 4.4 | `ScriptTest.validateValue` |
| Report import | none (`UnsupportedOperationException`) | `importReport` (columns by view order, `import/expression` with `input`, import script per row) | as 4.4 | as 4.4 | as 4.4 | `ReportImportTest` |
| Dashboards | widgets of collections and objects (no `widgetData` source); variations as 4.8 | as 4.8 | variations applied in turn: a later false one resets the display | as 4.8 | a true variation's display is kept | `ReportTest.dashboardVariation` |
| `schemaHandling/associationType` (`resource-ldap-association-types.xml`) | rejected (`delineation`) | as 4.0 | rejected (`associationType`) | read and run | as 4.9 | `AssociationTypeTest` |
| Base contexts of a reference simulated by the `references` capability (real LDAP) | none | none | none | the object's filter evaluated with no variables when the account's associations are read (groups of another unit are not the account's); the subject's never evaluated; a reference type in an object type's `configuredCapabilities` is not read and an association on the object side of an association type is refused ("Association type references attribute ... that does not exist in object type ...") | as 4.9 | `ReferenceBaseContextTest` |
| Value of a legacy association outbound | `ShadowAssociationType` | as 4.0 | as 4.0 | `ShadowAssociationValueType` | as 4.9 | `EntitlementTest.associationFromLinkAddsTheMemberToTheGroupOfTheRole` |
| An association added to an account by a primary change (approved by `addAssociationAspect`) | a `ShadowAssociationType` in `association`; the condition's `association` is that | as 4.0 | as 4.0 | a `ShadowAssociationValueType` in `associations/ri:<name>` (seen on 4.10: a legacy `association` delta is ignored) | as 4.9 | `AssociationApprovalTest.onlyAdminGroupsNeedTheOwnersApproval` |
| `assignmentTargetSearch` mapping, minus set when the target changes | the old assignment once | as 4.0 | as 4.0 | twice: as computed (no ID) and as stored | as 4.9 | `SearchTest.assignmentTargetSearchFollowsTheCostCenter` |
| Import of a `targetRef` filter with an expression | skipped, left to the evaluation | as 4.0 | as 4.0 | fails: "filter matches no object" (use `resolutionTime=run`) | as 4.9 | `VariablesTest.importOfATargetFilterWithAnExpression` |
| `SpringApplicationContextHolder` for scripts | `com.evolveum.midpoint.wf.impl.processes.common` | as 4.0 | `com.evolveum.midpoint.model.impl.expr` | as 4.8 | as 4.8 | `SampleConfigTest.bulkActionScriptWithASpringBean` |
| A function of a missing library | `ObjectNotFoundException` | as 4.0 | `ConfigurationException` ("No function library with OID") | as 4.8 | as 4.8 | `SearchTest.functionOfAMissingLibrary` |
| Task queries (activity format) | not tested (the 4.0 task format is not ported) | run | as 4.4 | as 4.4 | as 4.4 | `SearchTest.taskQuerySeesTheConfiguration` |
| Task archetypes in the baseline | none on 4.0 | 507, 508, 509, 511, 520, 521, 530, 531 | as 4.4 | as 4.4 | as 4.4 | `RuntimeTest.baselineAndWiring` |
| A custom policy constraint (`policyConstraints/custom`) | none: an object state constraint with the same expression (`fixtures-4.0/policy`) | as 4.0 (`fixtures-4.4/policy`) | `custom` (4.7+) | as 4.8 | as 4.8 | `PolicyTest.contractorsNeedAnEndDate` |
| `input` of a `scriptExecution` policy script | the user's `PrismObjectValue`: `input.description` fails, and so does the operation ("... is still UNKNOWN during cleanup"); the script's change is lost | the user (`input.description` resolves; the change is made) | as 4.4 | as 4.4 | as 4.4 | `PolicyTest.fieldStaffAreMarkedByAScript` |
| Bulk action scripts | `execute-script` actions only (no `<s:execute>`): located at their script parameter (`.../action/parameter[script]`); scripting tasks are the scripting handlers with the bulk action in the extension | `<s:execute>`; activities `iterativeScripting`, `nonIterativeScripting` | as 4.4 | as 4.4 | as 4.4 | `ScriptingTaskTest`, `PolicyTest.fieldStaffAreMarkedByAScript` |
| Provenance metadata on the value of an object type's association outbound | none | none | none | present | none | `ObjectTypeAssociationTest.theAccountJoinsTheGroupOfItsCostCenter` |
| Dashboard reports (a widget's own view columns) | not tested | exported (HTML; a CSV export has the widget table only) | as 4.4 | as 4.4 | as 4.4 | `DashboardReportTest` |
| Simulation metrics (domain, value and selection expressions; aggregated metrics with a domain restriction) | none | none | computed for the objects a preview task processes, summed in the simulation result | as 4.8 | as 4.8 | `SimulationTest` |
| Audit recording expression (`audit/eventRecording/expression`: change or drop each record) | none (no such element) | evaluated for the request and the execution record | as 4.4 | as 4.4 | as 4.4 | `AuditRecordingTest` |
| Custom audit property (`audit/eventRecording/property`) | on the execution record only (the request is audited before the system configuration is loaded) | on the request and the execution record | as 4.4 | as 4.4 | as 4.4 | `AuditPropertyTest.auditRecordsCarryTheCostCenterOfTheUser` |
| Approval comments of an approved assignment | `assignment/metadata/createApprovalComment` and the user's `metadata/modifyApprovalComment` | as 4.0 | as 4.0 | the same items in value metadata (`process`) | as 4.9 | `ApprovalCommentsTest.theApprovedAssignmentKeepsTheFormattedComment` |
| Certification comments of a certified assignment (`reviewerCommentsFormatting`, when the campaign closes) | `assignment/metadata/certifierComment` | as 4.0 | as 4.0 | in value metadata (`process`) | as 4.9 | `CertificationCommentsTest.theCertifiedAssignmentKeepsTheFormattedComment` |
| Provenance feed populators of a resource object type (metadata and acquisition items) | none (no value metadata) | evaluated: inbound values carry the metadata (their `provenance`, e.g. the `originRef` of an acquisition populator, when the template keeps it: `meta/item[provenance]`) | as 4.4 | as 4.4 | not evaluated: values of resource objects get no value metadata (MID-10514) | `ValueMetadataTest.valuesFromHrNoteTheirRecord`, `valuesOfContractorsComeFromTheAgency` |
| Asynchronous policy scripts, object collection default views in reports, activity reports, an activity's item and bucket processing conditions (also tailored), the object sets of import, reconciliation and repository tasks, activity tracing and profiling, dashboard widgets on filtered collections, report views | not tested | run | as 4.4 | as 4.4 | as 4.4 | `AsyncPolicyScriptTest`, `CollectionViewReportTest`, `ActivityReportsTest`, `ControlFlowTest`, `ResourceTaskQueryTest`, `RepositoryTaskQueryTest`, `ActivityTracingTest`, `DashboardWidgetTest`, `ReportViewTest` |
| Explicit work buckets (filters with expressions) | in the `workManagement` of the task handlers | in the activity's `distribution`; a buckets report | as 4.4 | as 4.4 | as 4.4 | `ActivityDistributionTest` |
| Shadow cleanup of a gone account | none | the deletion of every shadow of its query is notified without reading the resource: situation deleted, the shadow kept, not dead | each shadow is refreshed from the resource: situation deleted, dead | as 4.8 | as 4.8 | `RepositoryTaskQueryTest.theShadowsOfAShadowCleanupQuery` |
| Role membership management, shadow reclassification tasks | none | none | membership management | both | as 4.9 | `RepositoryTaskQueryTest`, `ResourceTaskQueryTest` |
| A report parameter with several values (a task's `reportParam`) | not tested (no object collection reports) | the export fails ("Attempt to get single value from item ... with multiple values") | the expressions get a list: a script filter returning it matches any value, a `path` expression fails ("Unexpected variable value") | as 4.8 | as 4.8 | `ReportTest.severalParameterValues` |
| A correlation confirmation returning no value | the import fails in its result only: "Failed to import: ... Couldn't confirm user user" | fails: "... Couldn't confirm user user" (cause: "Expression returned no value (0) in confirmation expression ...") | fails: "... Couldn't confirm candidate owner: Expression returned no value in confirmation expression for filter correlator '...'" | as 4.8 | as 4.8 | `MisconfigurationTest.confirmationMustReturnOneValue` |
| Focus archetype of an object type, `items` and `expression` correlators, confidence thresholds and expressions, correlation cases, indexed items, multi-source items | none (4.6+; indexing and multi-source 4.7+): skipped | as 4.0 | candidates restricted to the archetype; tiers; a candidate below `definite` makes the owner uncertain and opens a correlation case; an item indexed by a custom normalization is searched by its normalized value; multi-source items are selected from the authoritative identity | as 4.8 | as 4.8 | `CorrelationTest`, `IndexingTest`, `MultiSourceTest` |
| A value policy without `minUniqueChars` | unique characters not checked | as 4.0 | as 4.0 | their minimum is `minLength` (StringPolicy) | as 4.9 | `PasswordReadingTest.uniqueCharactersDefaultToTheMinimalLength` |
| Two structural archetypes on one user | refused after the template and inducements were evaluated (`ConfigurationException`) | refused before any evaluation (`SchemaException`) | as 4.4 | as 4.4 | as 4.4 | `ArchetypeTest.anObjectHasOneStructuralArchetype` |
| `archetypeRef` of a user of a sub-archetype | none (no `superArchetypeRef`) | the assigned archetype | as 4.4 | also its super-archetypes | as 4.9 | `ArchetypeTest.aSubArchetypeTakesThePolicyAndInducementsOfItsSuperArchetype` |
| A user's lifecycle state without a state model: `archived`; `suspended` | effective status ARCHIVED; suspended: DISABLED, assignments inactive (no account) | as 4.0 | DISABLED; suspended: DISABLED, assignments active (the account is kept, disabled) | as 4.8 | as 4.8 | `LifecycleStateModelTest.withoutAModelOnlyActiveAndDeprecatedUsersAreEnabled` |
| A model's state with `activeAssignments` false (an existing account) | the account is kept | as 4.0 | the assignments are inactive: the account is deleted | as 4.8 | as 4.8 | `LifecycleStateModelTest.anArchivedStateWithoutAForcedStatusLeavesTheContractorEnabled` |
| Security policy of an archetype (`securityPolicyRef`) | none: the global policy applies (`fixtures-4.4/security`) | as 4.0 | the archetype's password policy replaces the global one | as 4.8 | as 4.8 | `PasswordPolicyTest.privilegedUsersFollowTheSecurityPolicyOfTheirArchetype` |
| A mapping with `timeFrom` whose condition is false (a delayed delete of an account disabled with its user) | the condition is not evaluated while the time is not reached, and the recompute trigger is set anyway (no effect when it fires) | the condition is evaluated first, a false one sets no trigger (MID-6040) | as 4.4 | as 4.4 | as 4.4 | `ActivationScenarioTest.accountEndsWithTheUsersValidityAndIsKept` |
| Lifecycle state outbound of an account (`lifecycle/lifecycleState/outbound`) | evaluated: the account's lifecycle state (draft, then active; a draft account is provisioned) | as 4.0 | refused before the mapping: "Outbound mappings for 'lifecycleState' are no longer supported. Please migrate them to 'purpose' mappings." | as 4.8 | as 4.8 | `AccountLifecycleStateTest.anAccountIsDraftUntilTheUserHasAnEmployeeNumber` |
| Import of an entry with an auxiliary object class no construction gives (mappings not `tolerant`) | the class is removed from the entry; the user keeps the inbound's value | as 4.0 | as 4.0 | the class is removed; the inbound is evaluated again without it, the user has no value | as 4.9 | `AuxiliaryObjectClassTest.withoutToleranceTheImportRemovesTheClass` |
| Tag outbound of a multi-account object type; metadata mappings of a user template | none (no multi-accounts, no value metadata): skipped | one account per tag; only the mapping of a metadata item is applied (`meta/mapping` and `item/meta/mapping` never: an inverted check in `ItemValueMetadataProcessingSpec`) | as 4.4 | as 4.4 | as 4.4 | `AccountTagTest`, `MetadataMappingTest` |
| Confidence expressions of filter and items correlators, of their items' searches, of attribute correlators and template items; an expression in a delineation filter | none (4.6+): skipped | as 4.0 | evaluated per candidate (a correlator's own) or per match metric (a search's, e.g. the Levenshtein distance); the delineation filter's expression is never evaluated, its branch matches nothing | as 4.8 | as 4.8 | `CorrelationConfidenceTest`, `ResourceMisconfigurationTest.anExpressionInADelineationFilterIsNeverEvaluated` |

Fixtures are written in the newest format; `tester/core/src/test/resources/fixtures-<line>/` holds variants for that
line and older ones (e.g. resources without `delineation` for 4.4 and 4.0). A configuration you test on several lines
needs the same: midPoint of an older line cannot read a newer format. A newer line still reads parts of an older one
(deprecated): `LegacyFormatTest` imports the 4.4 variants on 4.8+ too (`Fixtures.legacy`).

Checked on every line with no difference to pin: the timed actions of an approval stage run by the trigger scanner, a
delegation's `approverExpression` adding the delegate, the conditions of timed actions never evaluated
(`TimedActionsTest`); an approval case named by the triggered constraint's short message when the action has no
`approvalDisplayName`, a process specification's display name evaluated with `object` after the change, long messages
never evaluated (`ApprovalNamesTest`); the approvers of `addAssociationAspect` from its `approverExpression`, its
`automaticallyApproved` never read (`AssociationApproversTest`); a mail transport's `recipientFilterExpression`
effective only with `redirectToFile`, a file transport's never evaluated (`TransportRecipientsTest`); a merge
configuration's `default` value expression (`MergeDefaultTest`); a collection stats constraint counting the collection
that holds it, 4.4+ (`CollectionStatsTest`); a simulation metric's `selectionRestriction`, a domain filter with an
expression matching nothing, 4.8+ (`SimulationPredicatesTest`); an import task's query of resource objects replaces the
activity's own by default (`queryApplication` `replace`: it must then name the resource, else "Resource not defined in a
search query"; `append` adds it), a skipped bucket is not searched, auto-scaling takes only the running tasks of its
query, an internal operations report without a fast filter reports nothing, an expression in a record filter of an
activity report is never evaluated and the filter matches no record (`ResourceTaskQueryTest`, `RepositoryTaskQueryTest`,
`ActivityTracingTest`); reports and dashboards take the columns of a view, not its collection (whose filter is never
evaluated), and a dashboard report cannot be imported (`ReportViewTest`); an object state constraint decided by a bulk
action or a message expression (`ObjectStateConstraintTest`); the lockout status outbound of a simulated lockout
attribute, and the existence, lockout status and lifecycle inbounds never evaluated (`LockoutTest`); the base context
filter of an object type, evaluated with no variables when provisioning searches (`BaseContextTest`, real LDAP;
`objectType/baseContext` in the 4.4 format, `delineation/baseContext` in 4.6+); the buckets of a tailored segmentation of
the explicit type, 4.4+ (`ResourceTaskQueryTest`); the synchronization sorter classifying
accounts (`SynchronizationSorterTest`); an inbound in a construction refused
(`ResourceMisconfigurationTest.anInboundInAConstructionIsRefused`); the auxiliary object class inbound of a tolerant
object type, the outbound never evaluated (`AuxiliaryObjectClassTest`); an expression in the filter of an object state
constraint or of a lifecycle state's forced assignment is never evaluated, the filter matches nothing
(`MisconfigurationTest`; forced assignments themselves are applied); certification reviewer comments formatted when the
campaign closes, the formatting evaluated for every decision, also one without a comment (`CertificationCommentsTest`);
an `asIs` password inbound gets the password of a connector that returns it, the projection's own password policy
(`objectType/securityPolicyRef`) fails a short password with a partial error (`PasswordReadingTest`, on the mirror and
the real CSV connector); the LDAP connector's `passwordReadStrategy` as midPoint reads the account (`LdapPasswordTest`,
mirror and real); extension items in mappings, changes and the merged `assignment` variable (`ExtensionTest`); a
collection filter with `$focus` fails ("No variable with name focus in collection filter",
`MisconfigurationTest.collectionFilterHasNoFocus`); a task's bulk action search applies its owner's authorizations,
filters evaluated with the owner as `subject` (`AccessTest.taskSearchesWithItsOwnersAuthorizations`); activation
(`ActivationTest`, `ActivationScenarioTest`, on mirrors and the real CSV and LDAP connectors): the status outbound's
input (`effectiveStatus`), no status provisioned without a status outbound, `validFrom` / `validTo` of users and
assignments applied by the validity scanner, a simulated status attribute, disable instead of delete (`disableReason`
`#deprovision`), the delayed delete's trigger on the shadow and its deletion by the trigger scanner; the JVM's wall
clock moving with `t.clock()` for scripts and Java code, Quartz on the real time (`ClockTest`, on JDK 11 for 4.0 and
4.4, JDK 21 for 4.8+).

Other line notes:

- **4.0**: the mapping hook is the model's mapping evaluator (4.0's `MappingImpl` cannot be subclassed), and neither
  4.0 nor 4.4 has `ConfigurationItemOrigin`: a mapping is placed by finding its value in the origin object. Two
  prism-equivalent mappings in one object fall back to the name or the canonical form.
- **4.4 / 4.0 accounts** are imported by searching the shadow and `importFromResource`; scanners and report exports
  are tasks switched to the background.
- **4.9 / 4.10**: the tester clears a repository cache 4.9+ does not clear after a database rollback
  (`ExtItemCache.nameToExtItem`; production never rolls back). 4.9 has the API of 4.10 at every place the tester
  touches; its initial objects are in `repo/system-init/src/main/resources/initial-objects` of the sources.
- **4.10**: resource mappings are copies of single attribute definitions.

## Limitations

- **Target systems are mirrors** unless kept on the real CSV or LDAP connector. The mirror connector reproduces the
  schema and capabilities of a resource (readable passwords as the real connector's configuration says), not the
  behaviour of its real connector or system: no LDAP schema checks, no password policies of the target system itself
  (midPoint's own policies for the account apply), no connector scripts, no asynchronous provisioning or updates;
  computed attributes, referential integrity and case insensitivity only as `ResourceOptions` say. Test
  what midPoint computes, not what the connector does. The real connectors are limited to CSV and LDAP (no Active
  Directory, no other connectors), the LDAP server is UnboundID's in-memory server with its standard schema (not
  OpenLDAP, 389 DS or AD: their schema, overlays such as `memberOf`, password policies and controls differ; its
  change log gives live synchronization no deletions and fails it on renames, see [Real connectors](#real-connectors)).
- **One connector per resource.** The tester replaces (mirror) or redirects (real connector) the resource's own
  connector only: an `additionalConnector` keeps its connector and configuration, those of the production system, so
  what midPoint takes from it (e.g. its `references` capability, 4.9+) is not tested.
- **PostgreSQL 18 in memory.** midPoint runs on pgmem, an in-memory PostgreSQL 18. The 4.4 native schema needed the
  PostgreSQL 16+ changes 4.8's script makes (marked in `tester/midpoint-4.4/src/main/resources/tester/sql/`). Repository
  behaviour specific to other databases is not covered.
- **No GUI and no REST.** Drivers call the services the GUI calls; GUI-only logic (forms, wizards, request access
  shopping cart) is not run. What the GUI shows a user is asked of the services it calls
  ([What the GUI shows a user](#what-the-gui-shows-a-user)): searches, pages by URL, forms; not what a page does
  after it opens, the menus, the GUI's own visibility settings (`adminGuiConfiguration`) or the REST
  authorizations.
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
  child processes or files opened before the mapping (see [Files scripts read and write](#files-scripts-read-and-write)).
  **Windows is not supported for file mapping**: there only `java.io.File` (and what is built from it) is redirected,
  `java.nio` paths such as `Path.of(...)` or `Files.writeString(...)` reach the real disk. Run tests that map
  directories on Linux or macOS.
- **Not in the tester**: GUI jars (only an index of their pages, built from them) and connectors other than CSV and LDAP on the classpath, custom Java overlays (add them as dependencies of
  your test module if your scripts need them; their Spring beans, such as hooks, through
  [Extension beans](#extension-beans)), clustering. What such beans call outside midPoint (HTTP services, cloud
  storage) is stood in for only through [HTTP services](#http-services).
- **Time**: `t.clock()` moves midPoint's clock and the JVM's wall clock, not `System.currentTimeMillis()` called from
  Java, `System.nanoTime()`, Quartz, the database or other processes (see
  [Activation and validity](#activation-and-validity)).
- **One line per JVM**: run a test suite once per line.

## Properties and output files

| System property | Default | Meaning |
|---|---|---|
| `tester.workDir` | `target` | working directory (midPoint home is `<workDir>/midpoint-home`) |
| `tester.extensionSchemaDir` | none (the tester's own tests: `tester/core/src/test/resources/schema`) | extension schemas (`*.xsd`), see [Extension schemas](#configuration-and-target-systems) |
| `tester.cache` | `true` | `false`: no deployment cache, every JVM loads its deployments afresh |
| `tester.cache.dir` | `<tester.workDir>/tester-cache` | the deployment cache (shared by the forks) |
| `tester.forks` | `1` | test JVMs per line module (this repository's POM; downstream: Surefire `forkCount` plus `tester.forks` and `tester.fork`) |
| `tester.manifest` | `midpoint-config-tester.yaml` | the manifest of `@MidPointConfigTest` without a value |
| `tester.taskTimeout` | `60` | seconds a driver call waits for background tasks |
| `tester.timing` | `false` | `true` writes the time of the tester's phases to `<workDir>/tester/timing.md` (see [Speed](#speed)) |
| `tester.log` | `target/tester.log` | the log file, when the project has no logback configuration of its own |
| `pgmem.binary` (or `PGMEM_BINARY`) | from `pgmem-native` | the pgmem binary |
| `static.dir`, `static.schemaDir` | none | static checks of your configuration (static module) |

| Output | Written by |
|---|---|
| `<workDir>/tester/timing.md` | `-Dtester.timing=true`, after the last test class |
| `<tester.workDir>/tester-cache/<deployment>/<key>/` | the deployment cache |
| `<tester.workDir>/fork-<n>/...` | everything of `<workDir>` per fork with `tester.forks` above 1 |
| `target/generated-test-sources/midpoint-names/` | the typed names (`NamesGenerator`) |
| `<workDir>/tester-resources/`, `<workDir>/tester-xml/` | resources as imported; XML given as text |
| `<workDir>/tester-mail/` | system configurations as imported, with the mail servers redirected; `files/`: the `redirectToFile` and `logToFile` files |
| `<workDir>/tester-files/<mapped dir>`, `<workDir>/midpoint-home/export/` | files of mapped directories; report exports (both reset with the database) |
| `target/tester.log` | midPoint's log (quiet configuration of the tester) |
| `target/static-check/<dir>.md` | static checks of `-Dstatic.dir` |

## Building this repository

```
pom.xml               root POM: midPoint versions, repositories, plugin versions, pgmem profiles
tester/
  pom.xml             tester parent: adds core/ to every line module, surefire settings
  core/               line-neutral sources (public API, runtime, recorder) and the shared tests
  base-4.0/           parent of 4.0 and 4.4 (JDK 11): approvals, certification, script hook, notification transport
  midpoint-4.0/       4.0: dependencies, harness, Spring context, recorder hook, drivers, SQL, baseline objects
  midpoint-4.4/       4.4: likewise
  base-4.8/           parent of 4.8+ (native repository): harness, Spring context, recorder hooks, drivers
  midpoint-4.8/       LineSupport, SQL and baseline objects of 4.8
  midpoint-4.9/       LineSupport, SQL and baseline objects of 4.9
  midpoint-4.10/      LineSupport, SQL and baseline objects of 4.10
  static/             static checks (own parent): core/ sources and tests, midpoint-<line>/ per line
parent/               parents of config repositories: common pom.xml, midpoint-<line>/ per line
examples/hr-ldap/     an example config repository on the 4.8 parent, tested in the build
docs/next-design.md   the `MidPointConfigTester` API: design, decisions, what each phase built
docs/redesign.md      the PoC: design, decisions, findings
```

The tests of a line module (`tester/core/src/test/java`) are the `@MidPointConfigTest` tests (`configtester`: the sample
deployments of `tester/core/src/test/resources/scenarios`) and the engine tests of the PoC API (`tester`: reset,
Quartz hold and task runs, time shift, file redirection, live sync, mirror options).

```bash
mvn test -pl tester/midpoint-4.8 -am                       # one line
mvn test -pl tester/midpoint-4.8,tester/midpoint-4.9,tester/midpoint-4.10 -am  # 4.8+ lines
mvn test -pl tester/midpoint-4.0 -am                       # 4.0 (Maven on JDK 11)
mvn test -pl tester/static/midpoint-4.8 -am                # static checks of one line
```

Patch versions can be overridden: `-Dmidpoint40.version=...`, `-Dmidpoint44.version=...`, `-Dmidpoint48.version=4.8.11`,
`-Dmidpoint49.version=...`, `-Dmidpoint410.version=...` (`RuntimeTest` checks that the line matches the release the module is built against).
The 4.0 BOM pins old test libraries (JUnit 5.3), so `midpoint-4.0` imports a newer JUnit BOM first.

### Speed

A build of every line (each with Maven on its JDK, [JDKs](#jdks)) takes under 4 minutes on a laptop (8 minutes before phase 4 removed the
PoC tests): each line module 31-43 s for 117 tests, midPoint's start 6-7 s of it, each static module 5 s, the example
15 s. Most of a line module's time is midPoint's own work; the tester keeps its own share small:

- One JVM per line module, one midPoint per JVM; a reset restores pgmem's in-memory snapshot (about 15 ms), never
  restarts anything. The reset before a class is skipped when the class before has just reset to the baseline and
  nothing ran since (no driver call, no task, no Quartz job; otherwise the full reset).
- Driver calls wait for background work by polling the task rows every 20 ms; measured, the wait is the task work
  itself (an event-driven wake-up gained nothing).
- `TaskRunsTest` waits on the real time where it must (a recurring task every 2 s: the held-run check ends at the held
  firing, about 2 s).
- `-Dtester.timing=true` writes `<workDir>/tester/timing.md` (and prints it) after the last class: count, total, mean and
  maximum of every phase (startup, resets, snapshots, driver calls and their waits, `@BeforeAll`).

Deployment cache (`@MidPointConfigTest`, on by default): a JVM restores a deployment loaded by an earlier JVM instead of
importing it again. Measured on 4.8 (load time per JVM, midPoint's start not included):

| Deployment | Fresh load | From the cache |
|---|---|---|
| `scenarios/access` (the line's initial objects included, the first load of a JVM) | 2.4 s | 0.27 s |
| `scenarios/hr` | 0.8 s | 0.38 s |
| synthetic: 300 roles, 50 orgs, 1000 users with 3 assignments each | 9.2 s (4.4: 9.4 s, 4.0: 4.2 s, raw import) | 0.54 s (4.4: 0.56 s, 4.0: 0.46 s) |

What is saved and restored: every table of the database (binary `COPY` over JDBC: pgmem keeps its snapshots in
memory only), the mirror objects, CSV files and LDAP entries with their change log (serialized), the mapped files, the
import order, the warnings and what the initial import did. On a restore the files are prepared again (the stand-ins
of this JVM: ports, CSV paths), the database is copied in with the reset's machinery (Quartz held, caches cleared,
Quartz jobs synchronized with the restored task rows), this JVM's node record is kept, and the stand-ins' connector
configuration and the mail redirection are written over the restored values. Timestamps in the database are those of
the cached load (a trigger the load set relative to "now" is as old as the entry).

Parallel test JVMs (`-Dtester.forks=N`, opt-in): measured on a 10-core Apple laptop, 4.8 line module, warm cache,
wall time of `mvn test` for the module, peak memory of the test JVMs and pgmem together, average CPU:

| Tests | forks=1 | forks=2 | forks=4 |
|---|---|---|---|
| full suite before phase 4 (458 tests) | 82 s, 6.8 GB, 200 % | 86 s, 7.5 GB, 420 % | 90 s, 7.9 GB, 685 % |
| tests of phase 3 (21) | 17 s, 2.0 GB, 284 % | 20 s, 2.9 GB, 432 % | 26 s, 5.9 GB, 613 % |
| 48 joiner/mover/leaver scenarios (probe) | 41 s, 3.6 GB, 224 % | 40 s, 4.2 GB, 478 % | 54 s, 6.5 GB, 764 % |

midPoint already uses about two cores per JVM, and N midPoints starting together take 17-21 s each instead of 7 s: on
this machine the forks only add heat (the fans run at 4 forks). They pay where the tests wait on the real time or on
a machine with many more cores. Each fork writes its own `<workDir>/fork-<n>/tester/` (timing
of its own classes). A downstream project sets `<forkCount>`, `<reuseForks>true` and
`-Dtester.forks=N -Dtester.fork=${surefire.forkNumber}` in the `argLine` (Surefire replaces the fork number there).

Opt-ins:

- **Maven Build Cache** (`.mvn/extensions.xml`, `.mvn/maven-build-cache-config.xml`), off unless
  `-Dmaven.build.cache.enabled=true` (for example in `MAVEN_OPTS`): a module whose inputs did not change since a passing
  build is not built and tested again. Inputs are the module's `src/` and effective POM (dependencies, so midPoint
  versions and the pgmem profile too) plus `tester/core` (shared sources, tests, fixtures, accepted files) for every
  line and static module, the `base-4.0` / `base-4.8` sources for their lines, and `static/core` for the static
  modules. A change in `tester/core` reruns every module; one in `midpoint-4.9` only that one (about 80 s instead of 7
  minutes; nothing changed: 2 s). The cache does not see `-D` properties, so `static.dir`, `static.schemaDir`, `tester.*`, `pgmem.binary` and `-Dtest`
  switch it off for the build (profiles `no-build-cache-*` of the root POM). A module served from the cache runs no tests, so its `target/tester/` reports
  are not written. Only `-Dpgmem.binary` is caught, not the
  `PGMEM_BINARY` environment variable. A project of your own that reads other `-D` properties in its tests needs a
  profile like `no-build-cache-<property>` for each. `-Dmaven.build.cache.skipCache=true` builds everything once
  without reading the cache; `rm -rf ~/.m2/build-cache/v1.1/io.github.wadahiro.midpoint` forgets this repository's
  entries (the cache directory is shared with other projects).
- **Lines in parallel**: `mvn -T 2 test` builds two line modules at once (each its own JVM and pgmem; more heat, about
  3 GB heap each). Tests inside a JVM always run one at a time.

### JDKs

Each line runs its tests on the JDK that runs Maven, one its midPoint supports (the release notes of the line); the
line's parent checks it before the tests (`tester.jdk`, maven-enforcer), not with `-DskipTests`:

| Line | Recommended | Supported |
|---|---|---|
| 4.0 | 11 | 11 (midPoint 4.0's Groovy 2.5.8 does not start on JDK 16 or later) |
| 4.4 | 17 | 11, 17 |
| 4.8, 4.9, 4.10 | 21 | 17, 21 |

A config repository pins the JDK of its line where Maven runs: a `mise.toml` (`[tools] java = "temurin-11"`) or a
`.sdkmanrc` in the repository, or `JAVA_HOME`; nothing per machine. The tester's own build needs JDK 17 or later to
compile (4.8+ and the Kotest layer), so a line is built and tested on its own, with Maven on its JDK, as CI does (one job
per line):

```bash
JAVA_HOME=<jdk-11> mvn test -pl tester/midpoint-4.0,tester/static/midpoint-4.0 -am
JAVA_HOME=<jdk-17> mvn test -pl tester/midpoint-4.4,tester/static/midpoint-4.4 -am
JAVA_HOME=<jdk-21> mvn test -pl tester/midpoint-4.8,tester/static/midpoint-4.8 -am
```

A build that only compiles and packages every line (`-DskipTests`, as the release does) runs on JDK 21.

### Publishing

[semantic-release](https://semantic-release.gitbook.io/) releases on every push to `main`
(`.github/workflows/release.yml`, `.releaserc.json`):

1. The tests of all lines (`ci.yml` with `-Pnon-lts`).
2. The next version from the [Conventional Commits](https://www.conventionalcommits.org/) since the last tag: `fix:`
   a patch, `feat:` a minor, and `BREAKING CHANGE:` a minor too while the tester is 0.x (the `releaseRules` of the
   commit analyzer in `.releaserc.json`; remove the rule to release 1.0.0); nothing for only `build:`, `docs:`,
   `chore:`, `test:` and the like. `next` goes to `main` as one squashed commit: its message gives the release type
   and the release notes (`feat:` with the changes in its body and a `BREAKING CHANGE:` footer when there is one, not
   `chore:`). After the release, `next` is reset to `main`. The tag `v0.0.0` is the baseline, so the first release was
   0.1.0.
3. `@terrestris/maven-semantic-release` sets the version in every module and runs `mvn deploy -DskipTests` (all lines,
   through `MAVEN_ARGS`). The example is not published.
4. The release commit (the versions and `CHANGELOG.md`, `[skip ci]`), the tag `v<version>`, a GitHub release, and a
   commit of the next snapshot version.

The deployment goes to the branch `mvn-repo`: a Maven repository (deployed into its checkout, then pushed), read at
`https://raw.githubusercontent.com/wadahiro/midpoint-config-tester/mvn-repo` without credentials.

With the repository variable `RELEASE_TO_CENTRAL` set to `true` it goes to Maven Central instead (Central Portal,
namespace `io.github.wadahiro`): the profile `release` adds the sources and javadoc jars, signs with GPG and uploads
the bundle with the `central-publishing-maven-plugin`, which waits until Central has published it. Its secrets:
`MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (the Central Portal's user token), `GPG_PRIVATE_KEY` (armored,
its public key on a key server such as `keys.openpgp.org`) and `GPG_PASSPHRASE`. By hand: the token as server
`central` in `settings.xml`, `mvn versions:set -DnewVersion=<version> -DprocessAllModules -DgenerateBackupPoms=false`,
`mvn deploy -P release,non-lts -DskipTests`, then publish the deployment in the Central Portal;
`-Dgpg.skip -Dmaven.install.skip -DskipPublishing` builds the staging (`target/central-staging`) only.

### Adding a midPoint line

The procedure is the Agent Skill [`.claude/skills/add-midpoint-line`](.claude/skills/add-midpoint-line/SKILL.md),
which an AI coding agent (or a person) follows: API research with a probe script, the module layout, the recorder hook
and identity placement, the line drivers, and the verification (shared tests, static checks, CI). [`docs/line-anatomy.md`](docs/line-anatomy.md) lists the files
of every line and the behaviour differences found; adding a line adds its column there.

## License

[EUPL-1.2](LICENSE), as midPoint 4.10 and later. The baseline objects of each line are copies of midPoint's initial
objects and keep their own headers.
