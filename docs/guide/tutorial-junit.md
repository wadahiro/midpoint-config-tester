# Tutorial: testing a configuration with JUnit

The steps test the configuration of [`examples/getting-started`](../../examples/getting-started), one step a
file of its tests: HR (a CSV export) feeds users, enabled from their start date to their end date; the user
template gives them the role of their department from their start date on; the roles provision LDAP accounts; a
user may request VPN Access, which alice approves; mails tell her of a request and the requester of the decision.
Every resource runs on the mirror connector, so a test writes HR's rows and reads LDAP's accounts itself. Set the
repository up first ([Getting started](../../README.md#getting-started)).

Each test starts from the configuration as loaded: what an earlier test did is gone.

## Step 1: a joiner

A row added to HR, the HR reconciliation run as deployed, then what midPoint made of it: the user, the role of the
department, the LDAP account and the welcome mail. Names are strings here; [Typed names](typed-names.md) makes them
constants that stop compiling when the configuration loses them.

```java
package com.example.idm;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

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

## Step 2: a mover, and nothing else

A check of what should happen does not say that nothing else did. Every verb that makes midPoint work returns what
midPoint did, and `verifyAll(c -> ...)` claims it: each check claims what it selects, and the verification fails
listing every changed object, connector write, mail and error logged that no check claimed. `none()` is a
verification without checks. More in [What a block did](effects.md).

```java
@MidPointConfigTest
class Step2MoverTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    @BeforeEach
    void hireJohn(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
    }

    @Test
    void aMoverChangesHisRoleAndHisAccountsOuAndNothingElse(MidPointConfigTester t) {
        t.source("HR").modify("1001", "department", "IT");

        t.task("Reconcile HR").run().verifyAll(c -> {
            c.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
            c.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("ou");
        });
    }

    @Test
    void reconcilingAgainChangesNothing(MidPointConfigTester t) {
        t.task("Reconcile HR").run().none();
    }
}
```

## Step 3: start and end dates

`t.clock()` moves midPoint's clock and the JVM's; `runDue()` then runs what is due by that time, the validity and
trigger scanners and the recurring tasks. A test of a start date 10 days on takes a second.

```java
@MidPointConfigTest
class Step3DatesTest {

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
        LocalDate end = t.clock().today().plusDays(5);
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01", "endDate", end.toString()));
        t.task("Reconcile HR").run();
        t.user("e1001").isEnabled();

        t.clock().advanceTo(end).advance("PT1H").runDue();
        t.user("e1001").isDisabled();
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").isDisabled();
    }
}
```

## Step 4: a request, approved or rejected

A nested class shares its prefix: its `@BeforeAll` runs once, the tester saves midPoint's state (the database, the
target systems' content, the clock, the mails), and each of its tests starts from that state, restored in a few tens
of milliseconds. John requests VPN Access once; one test has alice approve it, the other reject it, each from the same
open request, whichever runs first. A rejection is checked as what it is: the cases closed and rejected, John and his
LDAP account as they were, the mail to him.

```java
@MidPointConfigTest
class Step4ApprovalTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    /** Both tests start from John's request: midPoint as the @BeforeAll left it, restored for each. */
    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class JohnHasRequestedVpnAccess {

        @BeforeAll
        void request(MidPointConfigTester t) {
            t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                    "startDate", "2020-04-01"));
            t.task("Reconcile HR").run();
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
    }
}
```

## Step 5: the deadline

The approval stage has a deadline of 3 days, after which midPoint rejects the request. From the same open request,
the clock moves 2 days (nothing happens) or 3 (rejected).

```java
@MidPointConfigTest
class Step5DeadlineTest {

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class JohnHasRequestedVpnAccess {

        @BeforeAll
        void request(MidPointConfigTester t) {
            t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                    "startDate", "2020-04-01"));
            t.task("Reconcile HR").run();
            t.actor("e1001").request().assign("VPN Access");
        }

        @Test
        void twoDaysOnTheRequestStillWaits(MidPointConfigTester t) {
            t.clock().advance("P2D").runTriggerScanner().none();
            t.workItems().assignedTo("alice").single();
        }

        @Test
        void undecidedFor3DaysTheRequestIsRejected(MidPointConfigTester t) {
            t.clock().advance("P3D").runTriggerScanner().verifyAll(c -> {
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

## Step 6: access for 30 days

An assignment may have its own validity: `request().assign("VPN Access", a -> a.validTo(end))` (also a user's
`assign` and `modify`). On the day it ends, the VPN goes from the LDAP account, while the role of the department
keeps the account.

```java
@MidPointConfigTest
class Step6TemporaryAccessTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";

    @Test
    void vpnAccessFor30DaysEndsOnItsDate(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
        LocalDate end = t.clock().today().plusDays(30);
        t.actor("e1001").request().assign("VPN Access", a -> a.validTo(end));   // the assignment's validTo
        t.workItems().assignedTo("alice").single().approve(t.actor("alice"));
        t.target("LDAP").account(JOHN).hasAttribute("businessCategory", "vpn");

        t.clock().advanceTo(end).advance("PT1H").runDue();
        t.target("LDAP").account(JOHN).hasNoAttribute("businessCategory");
        t.user("e1001").hasAccountOn("LDAP");   // his department keeps the account
    }
}
```

## Next

[Writing tests with JUnit](writing-tests-junit.md) has the rest of the API: users, roles and orgs, tasks, values,
starting states midPoint would not make (`t.raw()`), nested classes. The other pages of the guide cover the
GUI's authorizations, mails, files, reports, certification and the audit log.
