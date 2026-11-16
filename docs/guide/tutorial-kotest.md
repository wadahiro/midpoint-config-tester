# Tutorial: testing a configuration with Kotest

The steps test the configuration of [`examples/getting-started`](../../examples/getting-started), one step a
file of its tests: HR (a CSV export) feeds users, enabled from their start date to their end date; the user
template gives them the role of their department from their start date on; the roles provision LDAP accounts; a
user may request VPN Access, which alice approves; mails tell her of a request and the requester of the decision.
Every resource runs on the mirror connector, so a test writes HR's rows and reads LDAP's accounts itself. Set the
repository up first ([Getting started](../../README.md#getting-started)).

The same steps as [the JUnit tutorial](tutorial-junit.md), as Kotest specs. A container (`"..." - { }`) shares
its code before its first test: it runs once, the tester saves midPoint's state, and each test of the container
starts from that state, restored in a few tens of milliseconds. A root test starts from the configuration as loaded.

## Step 1: a joiner

A row added to HR, the HR reconciliation run as deployed, then what midPoint made of it: the user, the role of the
department, the LDAP account and the welcome mail.

```kotlin
package com.example.idm

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest
import com.github.wadahiro.midpoint.configtester.kotest.MidPointConfigSpec

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

## Step 2: a mover, nothing else, and rows as tests

The container hires John once. Its first test is a `precondition`: it checks the state the container's code made,
and when it fails, the container's tests fail without running, each by its name, rather than each failing for a
reason it does not test. `verifyAll { }` claims what the verb did, and fails listing whatever no check claimed;
`none()` is a verification without checks ([What a block did](effects.md)). `withData` makes a test of each row, each
from the container's state.

```kotlin
@MidPointConfigTest
class Step2MoverSpec : MidPointConfigSpec({
    "John has joined Sales" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()

        precondition("he is in Sales, with an account on LDAP") {
            t.user("e1001").hasAssignment("Dept Sales").hasAccountOn("LDAP")
        }

        "moving to IT changes his role and his LDAP account's ou, nothing else" {
            t.source("HR").modify("1001", "department", "IT")
            t.task("Reconcile HR").run().verifyAll {
                changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef")
                connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single().only("ou")
            }
        }

        "reconciling again changes nothing" {
            t.task("Reconcile HR").run().none()
        }

        withData(nameFn = { "moving to ${it.first}" }, "IT" to true, "Finance" to false) { (department, known) ->
            t.source("HR").modify("1001", "department", department)
            t.task("Reconcile HR").run()
            if (known) t.user("e1001").hasAssignment("Dept $department").hasAccountOn("LDAP")
            else t.user("e1001").hasNoAssignment("Dept Sales").hasNoAccountOn("LDAP")   // no role for Finance
        }
    }
})
```

## Step 3: start and end dates

`t.clock()` moves midPoint's clock and the JVM's; `runDue()` then runs what is due by that time, the validity and
trigger scanners and the recurring tasks.

```kotlin
@MidPointConfigTest
class Step3DatesSpec : MidPointConfigSpec({
    "Kate joins in 10 days" - {
        val start = t.clock().today().plusDays(10)
        t.source("HR").add("1003", mapOf("firstName" to "Kate", "lastName" to "Smith", "department" to "IT",
                "startDate" to start.toString()))
        t.task("Reconcile HR").run()

        precondition("before her start date she has no role and no account") {
            t.user("e1003").isDisabled().hasNoAssignment("Dept IT").hasNoAccountOn("LDAP")
        }

        "on her start date she gets her role and her LDAP account" {
            t.clock().advanceTo(start).advance("PT1H").runDue()   // the scanners and the due tasks at the new time
            t.user("e1003").isEnabled().hasAssignment("Dept IT").hasAccountOn("LDAP")
        }
    }

    "John leaves in 5 days" - {
        val end = t.clock().today().plusDays(5)
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01", "endDate" to end.toString()))
        t.task("Reconcile HR").run()

        precondition("until then he is enabled") { t.user("e1001").isEnabled() }

        "on his end date, his user and his LDAP account are disabled" {
            t.clock().advanceTo(end).advance("PT1H").runDue()
            t.user("e1001").isDisabled()
            t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").isDisabled()
        }
    }
})
```

## Step 4: a request, approved or rejected

John requests VPN Access once, in the container; one test has alice approve it, the other reject it, each from the
same open request. A rejection is checked as what it is: the cases closed and rejected, John and his LDAP account as
they were, the mail to him.

```kotlin
@MidPointConfigTest
class Step4ApprovalSpec : MidPointConfigSpec({
    "John requests VPN Access" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()
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
    }
})
```

## Step 5: the deadline

The approval stage has a deadline of 3 days, after which midPoint rejects the request. From the same open request,
the clock moves 2 days (nothing happens) or 3 (rejected).

```kotlin
@MidPointConfigTest
class Step5DeadlineSpec : MidPointConfigSpec({
    "John requests VPN Access" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()
        t.actor("e1001").request().assign("VPN Access")

        precondition("the request waits for alice") { t.workItems().assignedTo("alice").single() }

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
})
```

## Step 6: access for 30 days

An assignment may have its own validity: `request().assign("VPN Access") { it.validTo(end) }` (also a user's
`assign` and `modify`). On the day it ends, the VPN goes from the LDAP account, while the role of the department
keeps the account.

```kotlin
@MidPointConfigTest
class Step6TemporaryAccessSpec : MidPointConfigSpec({
    "John has VPN Access for 30 days" - {
        t.source("HR").add("1001", mapOf("firstName" to "John", "lastName" to "Doe", "department" to "Sales",
                "startDate" to "2020-04-01"))
        t.task("Reconcile HR").run()
        val end = t.clock().today().plusDays(30)
        t.actor("e1001").request().assign("VPN Access") { it.validTo(end) }   // the assignment's validTo
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
})
```

## Next

[Writing tests with Kotest](writing-tests-kotest.md) has the containers, `steps` (tests that continue each other) and
rows from a feed; [Writing tests with JUnit](writing-tests-junit.md) the API both share.
