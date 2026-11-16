# midpoint-config-tester

Automated tests for your midPoint configuration. Your tests, in **JUnit 5** or **Kotest**, run against a **real
midPoint** that starts inside the test, with your configuration loaded as on its first start. No midPoint server,
database or target system to install, and nothing outside is reached.

What you can do with it:

- **Check what the configuration does to people.** An HR record arrives, a user moves to another department, leaves:
  check the users, roles, accounts and groups midPoint made of it. A failed check lists the mappings that set the
  value.
- **Walk through processes.** Request a role and approve or reject it, run a certification campaign, move the clock to
  a start or end date: check the cases, work items, mails and reports.
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

## Getting started

A config repository holds its midPoint configuration (`post-initial-objects`, as deployed to midPoint's home), a
manifest `midpoint-config-tester.yaml` and the tests, with a POM whose parent is the tester's parent of its midPoint
line. [`examples/getting-started`](examples/getting-started) is such a repository, with the files shown here: HR (a
CSV export) feeds users, enabled from their start date to their end date (activation `validFrom` and `validTo`), the
user template gives them the role of their department from their start date on, the roles provision LDAP accounts,
and a user may request VPN Access, for good or until a date, which alice approves (undecided for 3 days, it is
rejected); mails tell her of the request and the requester of the decision.

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
<artifactId>hr-ldap-config</artifactId>
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
connector** as the configuration loads, transparently: the resource definitions stay as you wrote them, and your
files are never changed (the tester works on a copy).

The mirror connector is an in-memory connector made from the resource's own schema: its object classes, attributes,
multiplicities, identifiers and capabilities. It keeps what midPoint creates, modifies and deletes, answers midPoint's
searches, reconciliations and live synchronization from what it keeps, and goes back to the loaded state before every
test. A test fills it as the real system would hold the data (`t.source("HR").add(...)`) and reads what midPoint wrote
to it (`t.target("LDAP").account(...)`). A resource exported from midPoint carries its schema; a resource definition
written by hand has none, so the mirror takes the attributes its `schemaHandling` names, and the manifest says which
one names the accounts (`targets` above). A resource can run on the real CSV or LDAP connector instead, with one line
in the manifest ([Target systems and the environment](docs/guide/environment.md#real-connectors)).

**Tests branch from a saved state.** When the setup that tests share has run (a nested class's `@BeforeAll`, the
code of a Kotest container before its first test), the tester saves midPoint's state: its database, the target
systems' content, the clock and the mails sent. Before each test it restores that state, in a few tens of
milliseconds, so every test starts from the same point whatever the test before it did. John requests VPN Access
once; one test has alice approve the request, the next has her reject it, a third moves the clock past the
deadline, where midPoint rejects it: each from the same open request. The clock is the tests' too: `t.clock().advanceTo(start)`
moves midPoint's clock and the JVM's to a start date, an end date or a deadline, and `runDue()` runs what is due by
then (the validity and trigger scanners, the recurring tasks), in a test that takes a second.

A test with JUnit (`src/test/java`). Every verb that makes midPoint work returns what midPoint did;
`verifyAll(c -> ...)` checks that nothing changed beyond what the test claims.

```java
@MidPointConfigTest
class HrLifecycleTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    static void hireJohn(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
    }

    @Test
    void joinerAndMover(MidPointConfigTester t) {
        hireJohn(t);
        t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP");

        t.source("HR").modify("1001", "department", "IT");
        t.task("Reconcile HR").run().verifyAll(c -> {   // these, and nothing else
            c.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
            c.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("ou");
        });
    }

    @Test
    void aFutureJoinerStartsOnHerStartDate(MidPointConfigTester t) {
        LocalDate start = t.clock().today().plusDays(10);
        t.source("HR").add("1003", Map.of("firstName", "Kate", "lastName", "Smith", "department", "IT",
                "startDate", start.toString()));
        t.task("Reconcile HR").run();
        t.user("e1003").isDisabled().hasNoAssignment("Dept IT").hasNoAccountOn("LDAP");

        t.clock().advanceTo(start).advance("PT1H").runDue();   // the scanners and the due tasks at the new time
        t.user("e1003").isEnabled().hasAssignment("Dept IT").hasAccountOn("LDAP");
    }

    @Test
    void aLeaverIsDisabledOnHisEndDate(MidPointConfigTester t) {
        hireJohn(t);
        LocalDate end = t.clock().today().plusDays(5);
        t.source("HR").modify("1001", "endDate", end.toString());
        t.task("Reconcile HR").run();
        t.user("e1001").isEnabled();

        t.clock().advanceTo(end).advance("PT1H").runDue();
        t.user("e1001").isDisabled();
        t.target("LDAP").account(JOHN).isDisabled();
    }

    @Test
    void vpnAccessFor30DaysEndsOnItsDate(MidPointConfigTester t) {
        hireJohn(t);
        LocalDate end = t.clock().today().plusDays(30);
        t.actor("e1001").request().assign("VPN Access", a -> a.validTo(end));   // the assignment's validTo
        t.workItems().assignedTo("alice").single().approve(t.actor("alice"));
        t.target("LDAP").account(JOHN).hasAttribute("businessCategory", "vpn");

        t.clock().advanceTo(end).advance("PT1H").runDue();
        t.target("LDAP").account(JOHN).hasNoAttribute("businessCategory");
        t.user("e1001").hasAccountOn("LDAP");   // his department keeps the account
    }

    /** Both tests start from John's request: midPoint as the @BeforeAll left it, restored for each. */
    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class JohnHasRequestedVpnAccess {

        @BeforeAll
        void request(MidPointConfigTester t) {
            hireJohn(t);
            t.actor("e1001").request().assign("VPN Access");
            t.mails().to("alice@example.com").withSubject("Approval needed: VPN Access").single();
        }

        @Test
        void aliceApproves(MidPointConfigTester t) {
            t.workItems().assignedTo("alice").single().approve(t.actor("alice")).verifyAll(c -> {
                c.changes().of("e1001").single().only("assignment", "roleMembershipRef");
                c.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("businessCategory");
                c.changes().ofType(CaseType.class).exactly(2);   // the request and its approval, closed
                c.changes().ofType(TaskType.class).added().single();   // the task that executes the approved change
                c.mails().to("e1001@example.com").withSubject("Request approved: VPN Access").single();
            });
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isApproved();
        }

        @Test
        void aliceRejects(MidPointConfigTester t) {
            t.workItems().assignedTo("alice").single().reject(t.actor("alice")).verifyAll(c -> {
                c.changes().ofType(CaseType.class).exactly(2);   // the request and its approval, closed
                c.changes().of("e1001").none();                  // John as he was
                c.connectorCalls().to("LDAP").writes().none();   // his LDAP account as it was
                c.mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single();
            });
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected();
            t.workItems().assignedTo("alice").none();
        }

        @Test
        void undecidedFor3DaysTheRequestIsRejected(MidPointConfigTester t) {
            t.clock().advance("P2D").runTriggerScanner().none();   // still waiting
            t.workItems().assignedTo("alice").single();

            t.clock().advance("P1D").runTriggerScanner().verifyAll(c -> {
                c.changes().ofType(CaseType.class).exactly(2);   // the request and its approval, closed
                c.changes().of("e1001").none();
                c.connectorCalls().to("LDAP").writes().none();
                c.mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single();
            });
            t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected();
        }
    }
}
```

The same with Kotest (`src/test/kotlin`). A container's code before its first test runs once and each of its tests
starts from what it left, and Kotest adds what JUnit has no counterpart for:

- **`precondition`**: the first test of a container checks the state its code made. When it fails, the container's
  tests fail without running, each by its name, instead of each failing for a reason it does not test.
- **Rows as tests**: `withData` makes one test per row, each from the container's state.
- **`steps`**: tests that continue each other, in order, stopping at the first failure
  ([Writing tests with Kotest](docs/guide/writing-tests-kotest.md)).

```kotlin
@MidPointConfigTest
class HrLifecycleSpec : MidPointConfigSpec({
    "John joins Sales" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()

        precondition("he is in Sales, with an account on LDAP") {
            t.user("e1001").hasFullName("John Doe").hasAssignment("Dept Sales").hasAccountOn("LDAP")
        }

        "moving to IT changes his role and his LDAP account's ou, nothing else" {
            t.source("HR").modify("1001", "department", "IT")
            t.task("Reconcile HR").run().verifyAll {
                changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef")
                connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single().only("ou")
            }
        }

        withData(nameFn = { "moving to ${it.first}" }, "IT" to true, "Finance" to false) { (department, known) ->
            t.source("HR").modify("1001", "department", department)
            t.task("Reconcile HR").run()
            if (known) t.user("e1001").hasAssignment("Dept $department").hasAccountOn("LDAP")
            else t.user("e1001").hasNoAssignment("Dept Sales").hasNoAccountOn("LDAP")   // no role for Finance
        }

        "John has VPN Access for 30 days" - {
            val end = t.clock().today().plusDays(30)
            t.actor("e1001").request().assign("VPN Access") { it.validTo(end) }
            t.workItems().assignedTo("alice").single().approve(t.actor("alice"))

            precondition("his LDAP account has the VPN") {
                t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasAttribute("businessCategory", "vpn")
            }

            "on the 30th day the VPN goes from his LDAP account, which his department keeps" {
                t.clock().advanceTo(end).advance("PT1H").runDue()
                t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").hasNoAttribute("businessCategory")
                t.user("e1001").hasAccountOn("LDAP")
            }
        }

        "John leaves in 5 days" - {
            val end = t.clock().today().plusDays(5)
            t.source("HR").modify("1001", "endDate", end.toString())
            t.task("Reconcile HR").run()

            precondition("until then he is enabled") { t.user("e1001").isEnabled() }

            "on his end date, his user and his LDAP account are disabled" {
                t.clock().advanceTo(end).advance("PT1H").runDue()   // the scanners and the due tasks at the new time
                t.user("e1001").isDisabled()
                t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").isDisabled()
            }
        }

        "John requests VPN Access" - {
            t.actor("e1001").request().assign("VPN Access")

            precondition("the request waits for alice, who got a mail") {
                t.workItems().assignedTo("alice").single()
                t.mails().to("alice@example.com").withSubject("Approval needed: VPN Access").single()
                t.user("e1001").hasNoAssignment("VPN Access")
            }

            "alice approves: his LDAP account gets the VPN" {
                t.workItems().assignedTo("alice").single().approve(t.actor("alice")).verifyAll {
                    changes().of("e1001").single().only("assignment", "roleMembershipRef")
                    connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single()
                        .only("businessCategory")
                    changes().ofType(CaseType::class.java).exactly(2)   // the request and its approval, closed
                    changes().ofType(TaskType::class.java).added().single()   // the task that executes the approved change
                    mails().to("e1001@example.com").withSubject("Request approved: VPN Access").single()
                }
                t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isApproved()
            }

            "alice rejects: John and his LDAP account stay as they were" {
                t.workItems().assignedTo("alice").single().reject(t.actor("alice")).verifyAll {
                    changes().ofType(CaseType::class.java).exactly(2)   // the request and its approval, closed
                    changes().of("e1001").none()                        // John as he was
                    connectorCalls().to("LDAP").writes().none()         // his LDAP account as it was
                    mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single()
                }
                t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected()
                t.workItems().assignedTo("alice").none()
            }

            "2 days on, the request still waits" {
                t.clock().advance("P2D").runTriggerScanner().none()
                t.workItems().assignedTo("alice").single()
            }

            "undecided for 3 days, the request is rejected" {
                t.clock().advance("P3D").runTriggerScanner().verifyAll {
                    changes().ofType(CaseType::class.java).exactly(2)   // the request and its approval, closed
                    changes().of("e1001").none()
                    connectorCalls().to("LDAP").writes().none()
                    mails().to("e1001@example.com").withSubject("Request rejected: VPN Access").single()
                }
                t.cases().forUser("e1001").forRole("VPN Access").single().isClosed().isRejected()
            }
        }
    }

    "Kate joins in 10 days" - {
        val start = t.clock().today().plusDays(10)
        t.source("HR").add("1003", mapOf("firstName" to "Kate", "lastName" to "Smith", "department" to "IT",
                "startDate" to start.toString()))
        t.task("Reconcile HR").run()

        precondition("before her start date she has no role and no account") {
            t.user("e1003").isDisabled().hasNoAssignment("Dept IT").hasNoAccountOn("LDAP")
        }

        "on her start date she gets her role and her LDAP account" {
            t.clock().advanceTo(start).advance("PT1H").runDue()
            t.user("e1003").isEnabled().hasAssignment("Dept IT").hasAccountOn("LDAP")
        }
    }
})
```

`mvn test` starts midPoint once per JVM, loads the configuration as midPoint's first start does, and runs every test from
that state; the load report is `target/tester-deployment/<name>/load-report.txt`.

## Documentation

- [Setting up a config repository](docs/guide/setup.md): the POM, the parent, required properties, the manifest.
- [Writing tests with JUnit](docs/guide/writing-tests-junit.md): the API and its rules, users, roles and orgs, tasks,
  values, `t.raw()`, nested classes.
- [Writing tests with Kotest](docs/guide/writing-tests-kotest.md): specs in Kotlin, containers and their prefixes,
  preconditions, steps, rows as tests.
- [What a block did, and the configuration at rest](docs/guide/effects.md): claiming midPoint's changes, recomputing
  without a change.
- [Typed names](docs/guide/typed-names.md): constants generated from the configuration and shipped for midPoint's own.
- [The GUI and authorizations](docs/guide/gui-and-authorizations.md): what a user sees, may open and may change.
- [Mails](docs/guide/mails.md): the mails midPoint sends, notifications.
- [Target systems and the environment](docs/guide/environment.md): target systems, real connectors, custom beans,
  constants, HTTP services, other databases.
- [Files scripts read and write](docs/guide/files.md): mapped directories.
- [Approvals, reports, activation, scripts and the audit log](docs/guide/midpoint-features.md).
- [The low-level API](docs/guide/low-level-api.md): resets, drivers, selecting mapping evaluations.
- [Static checks](docs/guide/static-checks.md): checking configuration XML against a line without starting midPoint.
- [Supported lines](docs/guide/lines.md): the lines, their JDKs, what differs between them.
- [Limitations, properties and output files](docs/guide/reference.md).
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
