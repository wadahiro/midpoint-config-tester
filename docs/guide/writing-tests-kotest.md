# Writing tests with Kotest

In Kotlin a test is a [Kotest](https://kotest.io) spec, a `MidPointConfigSpec` annotated `@MidPointConfigTest`
(`midpoint-config-tester-kotest`). Its `t` is the `MidPointConfigTester` of [Writing tests with
JUnit](writing-tests-junit.md): the same API, rules and assertions. A container of tests shares its prefix through
checkpoints: a container's code before its first test is its prefix, run once, and each test of the container
starts from midPoint as the prefix left it. The tree reads like the flow, values the prefix computed are plain local
values, and tests are named by sentences.

```kotlin
@MidPointConfigTest
class CampaignSpec : MidPointConfigSpec({
    "the campaign has started" - {
        val campaign = t.certification("Role assignments").start()          // once
        precondition("the campaign is in its first stage") { campaign.atStage(1) }
        "a reminder 22 days before the deadline" { ... }
        "an undecided case stays after the deadline" { ... }
        "a revocation past the deadline" - {
            campaign.reviews().forUser("jdoe").forRole("Auditor").single().revoke(t.actor("sec-officer"))
            t.clock().advance("P30D").runDue()                               // once, on the started campaign
            precondition("the revocation is decided") {
                campaign.reviews().forUser("jdoe").forRole("Auditor").single().response() shouldBe "revoke"
            }
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

- Each root starts from the loaded configuration, as a JUnit test method does; `t` is its tester. A failed assertion gets the
  mappings behind the values it read, as in JUnit.
- A container whose body changes midPoint (a driver call, a `t.raw()` write, a `t.config()` change, a target system, a file, the clock...)
  checks what it made in its first test, a `precondition("...") { }`: the test only reads, and its name says what the
  tests after it depend on. When it fails, the container's other tests fail without running (they are not skipped),
  each by its name, the nested ones too: a test of a rule cannot pass on a state the body never made. A first test
  that is no precondition after such a body fails, as a precondition that is not the first test or that changes
  midPoint. A body that only reads needs none; `steps` follow the same rule. The test is named
  `precondition: <name>` in the reports and for a filter.
- A body holds what its tests share, the given; the precondition claims what midPoint derived from it that the tests
  depend on, never what the body passed in (`assign("Auditor")` then `hasAssignment("Auditor")` holds by
  construction). If the precondition would say what the first test says, the body's act is what the container tests:
  make it a test (or `steps`, when tests continue it), and the body only the given before it.
- Work in a container between its tests would be undone after the next test: the next test fails and says so (put it
  before the first test, in a container of its own, or use `steps`).
- Kotlin values the prefix computed (`campaign` above) are not checkpointed, as the fields of a nested class are not;
  the checkpoint covers what it covers for nested classes, and saves as much.
- Set up: the parents manage Kotlin and Kotest and run `*Spec` classes; a repository adds, as test dependencies,
  `midpoint-config-tester-kotest` and `io.kotest:kotest-runner-junit5-jvm` (which brings the Kotlin stdlib), and the
  `kotlin-maven-plugin` ([`examples/getting-started`](../../examples/getting-started)). IntelliJ needs the Kotest plugin
  to run one spec or test from the gutter; Maven's `-Dtest=` selects specs by class name. `precondition`, `steps` and
  `verifyAll { }` are functions of the Kotest layer, imported from `com.github.wadahiro.midpoint.configtester.kotest`
  as `MidPointConfigSpec` is.
- Run some tests of a spec: name the tests by an ID first (`"CASE-012: the reminder is sent" { }`) and list the IDs
  in `-Dtester.only=CASE-012,CASE-015` (or `TESTER_ONLY`). A test runs when its name, or a container's around it,
  starts with one of them followed by no letter or digit (`CASE-01` does not select `CASE-012`); an ID ending with
  `*` is a plain prefix (`CASE-01*` selects `CASE-010` to `CASE-019`). The others are reported as skipped. The containers' bodies and the preconditions still run, since the selected tests start from
  them, and so do all the tests of a `steps` container, which continue each other. Kotest's own
  `-Dkotest.filter.tests` matches the whole path, containers included, so a pattern for one test skips its container.

Rows of a source feed are tests: Kotest's `withData` in a container makes one test per row, each from the container's
prefix. Examples with `John Doe, Sales, 2020-04-01` never meet what production feeds hold; give a
feed's scenario rows such as these:

- an empty optional cell (a script's `null`), blanks and the case the export has;
- names with an apostrophe, a hyphen, a particle (`O'Brien`, `Smith-Jones`, `van der Berg`) and letters outside ASCII:
  a `capitalize()` gives `O'brien`, a DN needs its escaping;
- the longest value the source allows, and a duplicate full name (an iterated name, a second `uid`);
- a date on the day boundary in the source's zone (a start date read at 23:59 local time is another day in UTC);
- a code the configuration does not know (a department without a role or a lookup table row).

```kotlin
"Ann is in the HR feed" - {
    t.hire("1000", "Ann", "Early", "Sales")
    precondition("Ann has her LDAP account") { t.user("e1000").hasAccountOn("LDAP") }
    withData(
        nameFn = { "last name '${it.lastName}', department '${it.department}'" },
        Row(" DOE ", "Sales", "Doe", true),
        Row("MÜLLER", "IT", "Müller", true),
        Row("Doe", "", "Doe", false),                   // no department: no role, no account
    ) { row ->
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to row.lastName,
                "department" to row.department, "startDate" to "2020-04-01"))
        val hired = t.task("Reconcile HR").run()
        t.user("e1001").hasItem("familyName", row.familyName)
        if (row.account) t.user("e1001").hasAccountOn("LDAP") else t.user("e1001").hasNoAccountOn("LDAP")
        hired.changes().focuses().forEach { it.recompute().none() }
    }
}
```
