# midpoint-config-tester

Automated tests for your midPoint configuration. Your tests, in **JUnit 5** or **Kotest**, run against a **real
midPoint** that starts inside the test, with your configuration loaded as on its first start. No midPoint server,
database or target system to install, and nothing outside is reached.

What you can do with it:

- **Check what the configuration does to people.** An HR record arrives, a user moves to another department, leaves:
  check the users, roles, accounts and groups midPoint made of it. A failed check lists the mappings that set the
  value.
- **Walk through processes.** Request a role and approve or reject it, run a certification campaign: check the cases,
  work items, mails and reports.
- **Travel forward in time.** Move the clock 30 days ahead and run the tasks that fall due: archiving, cleanups,
  validity dates, notifications before an end date. The clock moves for midPoint and for its scripts alike
  (`basic.fromNow`, `new Date()`, `System.currentTimeMillis()` in Groovy), unlike midPoint's own clock offset. Every
  test starts again from the same point, clock included
  ([What moves with the clock](docs/guide/midpoint-features.md#activation-and-validity)).
- **Check what a user is authorized to do.** Log in as a user and ask midPoint's authorizations what they find in a
  search, which pages and objects they may open and which items they may change. The GUI itself does not run.
- **Catch breakage early.** Names generated from the configuration stop compiling when it loses them; static checks
  find scripts that do not compile on the midPoint version you upgrade to.
- Experimental: **an authorization matrix**, kinds of users against what they may do, checked cell by cell
  ([An authorization matrix](docs/guide/authz-matrix.md)).

Supported midPoint versions: the LTS lines 4.8 and 4.4 and the feature releases 4.9 to 4.11
([Supported lines](docs/guide/lines.md)).

Every test starts from the configuration as loaded, or from a state the tests share, restored in a few tens of
milliseconds. Every resource runs on an in-memory mirror of itself, whatever its connector, and mail goes to an
in-memory server. A CSV or LDAP resource can run on its real connector instead, against files or an in-memory LDAP
server.

## What it tests, and what it does not

It tests what midPoint does with your configuration: mappings, object templates, roles and their inducements, policy
rules, approvals, certification, notifications, reports, tasks, scripts, authorizations, as the running midPoint
evaluates them. By design it leaves out:

- **The GUI.** No page is rendered or clicked. What a user may see and do is asked of midPoint's authorizations, as
  the GUI asks them ([The GUI and authorizations](docs/guide/gui-and-authorizations.md)); what a page does after it
  opens is not run.
- **The connectors and the target systems.** A resource runs on the mirror connector, made from its schema, so a test
  checks what midPoint sends and reads, not how a connector or a real system takes it (an LDAP server's schema checks,
  a database's constraints). A resource's provisioning scripts, through an SSH additional connector say, are recorded
  with their arguments, not run.
- **The production environment.** midPoint runs inside the test on an in-memory PostgreSQL, with no cluster, no
  network and no other machine.

The rest: [Limitations](docs/guide/reference.md#limitations).

## Getting started

A config repository holds its midPoint configuration (`post-initial-objects`, as deployed to midPoint's home), a
manifest `midpoint-config-tester.yaml` and the tests, with a POM whose parent is the tester's parent of its midPoint
line. [`examples/getting-started`](examples/getting-started) is such a repository:

```
pom.xml                         the parent of the line and the repository's coordinates
midpoint-config-tester.yaml     the manifest: config directories, target systems
post-initial-objects/           as deployed to midpoint.home/post-initial-objects
src/test/java/...               the tests with JUnit
src/test/kotlin/...             the tests with Kotest
```

The POM: the parent of the line, the tester's Maven repository, and the time zone the midPoint server runs in.

```xml
<parent>
    <groupId>io.github.wadahiro.midpoint</groupId>
    <artifactId>midpoint-config-tester-parent-4.8</artifactId>
    <version>0.8.0</version>
</parent>

<groupId>com.example.idm</groupId>
<artifactId>getting-started-config</artifactId>
<version>1.0-SNAPSHOT</version>

<properties>
    <tester.timeZone>Europe/Berlin</tester.timeZone>   <!-- the time zone the midPoint server runs in (required) -->
</properties>

<repositories>
    <repository>
        <id>midpoint-config-tester</id>
        <url>https://raw.githubusercontent.com/wadahiro/midpoint-config-tester/mvn-repo</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

The parent brings the tester of the line, JUnit, midPoint's dependency management, the generator of typed names and
Surefire set up for the tester. Maven runs on a JDK the line's midPoint supports (the parent checks it; see
[Supported lines](docs/guide/lines.md)). `tester.timeZone` (an IANA ID) is required: midPoint and its scripts run in
that zone on every machine, so a start date begins at midnight on the server whether the test runs on a laptop or a
CI machine in UTC. On 4.11 `tester.safeScriptingLanguagesOnly` is required too ([Scripting on
4.11](docs/guide/lines.md#scripting-on-411)).

The manifest points the tester at the configuration:

```yaml
config:
  dirs: [post-initial-objects]
targets:
  # the resource definitions have no stored <schema>: the attribute that names the accounts
  HR:   { namingAttribute: { AccountObjectClass: empno } }
  LDAP: { namingAttribute: { inetOrgPerson: dn } }
```

**No connector and no target system to set up.** Whatever connector a resource uses (LDAP, Active Directory, a
database table, a REST or SCIM service, a connector of your own), the tester replaces it with its **mirror
connector** as the configuration loads: an in-memory connector made from the resource's schema, which keeps what
midPoint writes and answers what midPoint reads. Your resource definitions stay as you wrote them. A test fills a
source as the real system would hold it (`t.source("HR").add(...)`) and reads what midPoint wrote to a target
(`t.target("LDAP").account(...)`). A resource exported from midPoint carries its schema; one written by hand has
none, so the manifest names the attribute that names its accounts (`targets` above). A CSV or LDAP resource can run on
its real connector instead ([Real connectors](docs/guide/real-connectors.md), experimental).

A first test (`src/test/java`): a row added to HR, the HR reconciliation run as deployed, then what midPoint made of
it.

```java
@MidPointConfigTest
class Step1JoinerTest {

    @Test
    void aJoinerGetsAUserAndAnLdapAccount(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();

        t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP");
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasAttribute("ou", "Sales");
        t.mails().to("e1001@example.com").withSubject("Welcome John Doe").single();
    }
}
```

<details>
<summary>The same test with Kotest</summary>

For tests in Kotlin, as Kotest specs, also add the tester's Kotest layer, Kotest (which brings Kotlin's standard
library) and the Kotlin compiler plugin (the parent manages their versions and runs `*Spec` classes):

```xml
<dependencies>
    <dependency>
        <groupId>io.github.wadahiro.midpoint</groupId>
        <artifactId>midpoint-config-tester-kotest</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>io.kotest</groupId>
        <artifactId>kotest-runner-junit5-jvm</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
<build>
    <plugins>
        <plugin>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

A spec in `src/test/kotlin`:

```kotlin
@MidPointConfigTest
class Step1JoinerSpec : MidPointConfigSpec({
    "a joiner gets a user and an LDAP account" {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()

        t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP")
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasAttribute("ou", "Sales")
        t.mails().to("e1001@example.com").withSubject("Welcome John Doe").single()
    }
})
```

</details>

`mvn test` starts midPoint once per JVM, loads the configuration as midPoint's first start does, and runs every test from
that state; the load report is `target/tester-deployment/<name>/load-report.txt`.

### Next: the tutorial

The tutorial goes on from this test in six steps, each a file of `examples/getting-started`: a mover, checked to
change nothing else; start and end dates, by moving the clock; a role request that alice approves or rejects;
its deadline; access for 30 days. From step 4 on, tests **branch from a saved state**: the tester saves midPoint's
state once the shared setup has run (the database, the target systems, the clock, the mails) and restores it before
each test in a few tens of milliseconds, so one test approves the request and the next rejects the same one.

- [Tutorial with JUnit](docs/guide/tutorial-junit.md)
- [Tutorial with Kotest](docs/guide/tutorial-kotest.md): the same steps, with `precondition` and rows as tests

## Documentation

- [Tutorial with JUnit](docs/guide/tutorial-junit.md) and [with Kotest](docs/guide/tutorial-kotest.md): the
  configuration of `examples/getting-started`, tested step by step.
- [Setting up a config repository](docs/guide/setup.md): the POM, the parent, required properties, the manifest.
- [Writing tests with JUnit](docs/guide/writing-tests-junit.md): the API and its rules, users, roles and orgs, tasks,
  values, `t.raw()`, `t.config()`, nested classes.
- [Writing tests with Kotest](docs/guide/writing-tests-kotest.md): specs in Kotlin, containers and their prefixes,
  preconditions, steps, rows as tests.
- [What a block did, and the configuration at rest](docs/guide/effects.md): claiming midPoint's changes, recomputing
  without a change.
- [Typed names](docs/guide/typed-names.md): constants generated from the configuration and shipped for midPoint's own.
- [The GUI and authorizations](docs/guide/gui-and-authorizations.md): what a user sees, may open and may change.
- [Mails](docs/guide/mails.md): the mails midPoint sends, notifications.
- [Target systems and the environment](docs/guide/environment.md): target systems, provisioning scripts, custom beans,
  constants, HTTP services, other databases.
- [Files scripts read and write](docs/guide/files.md): mapped directories.
- [Approvals, reports, activation, scripts and the audit log](docs/guide/midpoint-features.md).
- [The low-level API](docs/guide/low-level-api.md): resets, drivers, selecting mapping evaluations.
- [Static checks](docs/guide/static-checks.md): checking configuration XML against a line without starting midPoint.
- [Supported lines](docs/guide/lines.md): the lines, their JDKs, what differs between them.
- [Limitations, properties and output files](docs/guide/reference.md).

Advanced:

- [Customized midPoint deployments](docs/guide/customized-deployments.md): a custom build of midPoint, an overlay (its
  jars, beans, replaced classes and initial objects, Java agents, plugin servers), customized DDL of the repository.
- [Real connectors](docs/guide/real-connectors.md) (experimental): a resource on its real connector instead of the
  mirror.
- [An authorization matrix](docs/guide/authz-matrix.md) (experimental): kinds of users against what they may do, as a
  YAML file and an HTML report.

An [Agent Skill](https://agentskills.io) for writing these tests with a coding agent,
[`midpoint-config-tests`](skills/midpoint-config-tests/SKILL.md), installs into a config repository with the
[skills CLI](https://github.com/vercel-labs/skills) (Node.js 22.20 or later):

```bash
npx skills add wadahiro/midpoint-config-tester --skill midpoint-config-tests -a claude-code   # -g: for every project
```

Without `-a` the CLI asks which agents to install it for.

Developing the tester itself: [docs/development.md](docs/development.md).

## License

[EUPL-1.2](LICENSE), as midPoint 4.10 and later. The baseline objects of each line are copies of midPoint's initial
objects and keep their own headers.
