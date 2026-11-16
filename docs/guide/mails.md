# Mails

`t.mails()` is every mail midPoint sent in the test, as the tester's SMTP server received it: the deployment's own
notifiers and transport, only the mail server redirected. Narrow by `to(address)`, `from(address)`,
`withSubject(text)` and `withBodyContaining(text)`; check with `exactly(n)`, `none()`, `single()` (a `Mail`:
`subject()`, `body()`, `hasBodyContaining(texts...)`, `from()`, `to()`, `attachments()`...), and with
`perRecipient()`, the number of mails each address got, for "these and nobody else". 4.8 sends one mail per
recipient (4.4 one to all recipients of a notification): `to(...)` and `perRecipient()` count per recipient and read
the same on every line; `count()` counts SMTP transactions. 4.8 and later append a subscription notice to the body: check
with `hasBodyContaining`, not equality.

Approval notifications of a sample configuration: `simpleWorkflowNotifier` handlers in the
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
`isApprovedBy(user)` / `isRejectedBy(user)`). What midPoint mails and what it does not (4.4 and 4.8 alike): a
work item is mailed to its assignees (and their deputies); a group approver left unexpanded (the default
`groupExpansion`) gives one work item offered to the group to claim, with no assignee, so no one is mailed for it
(`WorkItem.candidates()` shows such a group). Every new
work item also raises a `workItemAllocationEvent` add, so a handler of that event for escalations needs an
`expressionFilter` (`event.operationKind?.value() == 'escalate'`). 4.8's schema has a `simpleCaseManagementNotifier`
element that no notifier of its notification module handles (its `EventHandlerRegistry` throws "Unknown handler"): use `simpleWorkflowNotifier`, as on
4.4. The transport differs: a named `messageTransportConfiguration/mail` on 4.8 (in the sample an overlay of the system
configuration), `notificationConfiguration/mail` on 4.4.

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
each `messageTransportConfiguration/mail` (4.8 and later) and `notificationConfiguration/mail` (4.4; the legacy
`mail` transport on 4.8 and later). In each of their `server` elements:

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
  variable `recipient`) gives the address of a recipient that is a user.
- `notifications()` stays for the `dummy` transports; a notifier with both kinds of transport appears in both.
- TLS, authentication (basic and OAuth2) and the delivery to a real server are not tested: they are what the tester
  replaces.

**A mail that cannot be sent.** `t.mailServer().reject()` makes the tester's SMTP server refuse every mail from then
on in the test, `reject("jdoe@example.com")` the mails to those recipients, `accept()` takes them again: the server
answers `451` to the mail's data, so midPoint's transport (or a script's) fails as with a real server's error, and the
mail is not in `t.mails()`. The next test's server takes every mail.
