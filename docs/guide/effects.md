# What a block did, and the configuration at rest

## What a block did

`t.effectsOf { ... }` runs a block and returns what midPoint did meanwhile, with the work the block waited for (a
task's background work): `changes()`, the objects it added, modified or deleted in its repository, one change per
object with the items changed; `connectorCalls()`, the calls it made to the connectors of its resources, writes
(`add`, `modify`, `delete`, `executeScript`) and reads (`fetch`, `search`, `count`, `fetchChanges`...), mirror and
real connectors alike; `mails()`, `logs()` and `audit()` of the block; and `repositoryCalls()`, the repository calls
below midPoint's cache, for looking into what a configuration costs. Selections narrow and check like `t.mails()`.

Every verb that makes midPoint work returns the `Effects` of its call: a focus's `add`/`modify`/`delete`/`assign`/
`unassign`/`recompute`, `modify(m -> m.password(...))`, `Task.run`/`resume`, `t.reconcile`, `t.importAccounts`, the clock's `runDue` and scanners, a request's
`assign`/`unassign`, the decisions on work items and reviews, and a campaign's stages. Claim what midPoint derived
(the accounts and their attributes, the mails, the changes of other objects), not what the test passed in: after
`assign("Auditor")` succeeded, `hasAssignment("Auditor")` holds by construction and says nothing about the configuration, so
an assertion does not chain onto a verb. `hasAssignment` and `hasItem` are for a precondition and for what midPoint derived
elsewhere (a role an org membership brings). `effectsOf` is for a block of several calls. Calls that are no work of
midPoint keep returning their subject: the clock's `advance`/`advanceTo`/`stopAt`, the writes to a source or target
system (`t.source(...).add`) and `t.raw()`. Two verbs return what they make instead: `t.certification(name).start()`
its `Campaign`, `t.report(name).run()` its `ReportOutput` (an export task runs: in a Kotest container's body it is a
change, which a precondition then checks); `effectsOf { }` gives their effects.

```kotlin
t.user("jack").recompute().none()
t.user("helpdesk").assign("Accountant").verifyAll {
    changes().of("helpdesk").single().only("assignment", "roleMembershipRef")
}
t.actor("jack").request().assign("Privileged Access").verifyAll {    // a role with approvers
    changes().ofType(CaseType::class.java).added().exactly(2)          // the request and its approval
    mails().to("role-owner@example.com").single()                     // the work item
    mails().to("jack@example.com").withSubject("Request submitted").single()
}
t.user("jack").hasNoAssignment("Privileged Access")                         // not before the approvers decide
```

A check that something did not happen, and a check of what happened that also proves nothing else did, are
verifications: in `effects.verifyAll(c -> ...)` (Kotest: `effects.verifyAll { ... }`, from
`midpoint-config-tester-kotest`) each check claims what it selects (`exactly`, `none`, `single`, and `ignored()` for
what the test does not care about), and the verification fails listing every changed object, connector write, mail
and error logged that no check claimed. Several checks are one verification (`verifyAll(c -> ..., ldapGroups)`: a
`Consumer<Claims>` reused across tests beside one written here); `effects.claims()` then `verifyAll()` is the same in
steps. In Kotlin the lambda goes after the parentheses, `verifyAll { ... }`. `none()` is a verification without
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
t.task("Reconcile HR").run().verifyAll(c -> {
    c.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
    c.changes().ofType(ShadowType.class).added().ignored();             // the group shadows midPoint discovered
    c.connectorCalls().to("LDAP").modifies().of("uid=e1001,ou=people,dc=example,dc=com").single().only("ou");
    c.connectorCalls().to("LDAP").modifies().exactly(3);
});
```

What counts (every line alike): a change is what midPoint wrote to its repository
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

## The configuration at rest

A configuration whose mappings fight (two mappings writing one item, a template against a role, a script that is no
fixed point: a value normalized again comes out different) gives the expected values on the first pass and changes
them on the next one, which a test of the values does not see. A recompute of what a verb changed claims that the
configuration is at rest; `changes().focuses()` are the users, roles, orgs and services the block added or modified,
as handles by OID (a deleted focus, shadows, cases, tasks and archetypes are left out):

```java
Effects hired = t.task("Reconcile HR").run();
hired.changes().focuses().forEach(f -> f.recompute().none());   // recomputing what the run changed changes nothing
t.task("Reconcile HR").run().none();                            // the inbounds too: a second run changes nothing
```

- The law is for focuses a verb changed. A focus loaded raw (`importObjectRaw`, `t.raw()`) can change on its first
  recompute (no activation yet).
- A mapping waiting for its time (`timeFrom`) is at rest too: its trigger is not added again.
- A strong outbound to an attribute midPoint cannot read back (the schema gives no read access) is sent on every
  recompute and every reconcile: midPoint cannot see that the account has the value. The law fails on it, as it should
  when the configuration does not mean it.
- A password outbound is evaluated only when the projection is added, reconciled or the password changes: a recompute
  skips it whatever its strength. When the resource does not return passwords (an LDAP server), a reconcile cannot
  compare the password with the account's, so a **strong** one sends it on every reconcile (it changes the account's
  password time on every run); a normal one only when the password changes. A resource that
  returns them (the CSV connector's password column) is compared and not sent again (seen on 4.8.12). The law does not see this one: `recompute()` does not reconcile.
- Claim a value sent again where the configuration means it, with `verifyAll` instead of `none()` (an item the
  configuration changes on every recompute on purpose, a timestamp of its own, the same way):

```java
t.user("jdoe").modify(Modification::reconcile).verifyAll(c ->
        c.connectorCalls().to("LDAP").modifies().of(dn).single().only("credentials/password/value"));
```

How midPoint recomputes a user decides what it sends (every line alike). A recompute reads
the account only when something asks for it: a strong or weak outbound (of an attribute, or of the activation status), an outbound with `target/set`, a strong inbound (4.8 source); with normal mappings only it uses the repository shadow and sends nothing for a value changed
on the account. A reconcile always reads it. What differs between the ways is whether the projections are reconciled:

| Way | Projections reconciled | Strong value removed on the account | Normal value changed on the account | Strong outbound it cannot read | Strong password |
|---|---|---|---|---|---|
| `recompute()`: `ModelService.recompute` without options, the role/org member panel's Recompute of one member from its row, the details page's save | no | added back | left | sent every time | not sent |
| `reconcileFocus` | no (the focus only) | added back | left | sent every time | not sent |
| `modify(Modification::reconcile)`: `reconcile`, the user list's Reconcile, the details page's save with Reconcile | yes | added back | left | sent every time | sent every time |
| A recomputation task without `executionOptions`, the member panel's Recompute of several or all members | yes: it defaults to `reconcile` | added back | left | sent every time | sent every time |
| A recomputation task with `<reconcile>false</reconcile>` | no | added back | left | sent every time | not sent |

The activation status follows the same rules, native and simulated: an account disabled
on the target is enabled again by a strong status outbound on every way above, a recompute included, and left
disabled by a normal one, a reconcile included; midPoint reconciles the activation in a code path of its own on
reconcile, which changes nothing here.

In the GUI, the member panel's Recompute of one member from its row is the only Recompute that does not reconcile:
the user, role and org lists offer Reconcile only. The bulk action `recompute` reconciles on every line, even with
`<reconcile>false</reconcile>` in its options (it adds `reconcile` to them); the validity scanner reconciles the users it processes too. A strong value is added back, not
replaced: a multi-valued attribute keeps the other values unless the attribute is not `tolerant`.
`reconcileAffected` (the users of a changed role, possibly in a task of its own) is not covered here;
`reconcileAll` is not an execution option but a synchronization reaction's (import, reconciliation and live sync
reconcile every projection of the user, not only the synchronized one).
