# Target systems and the environment

## Configuration and target systems

| Call | What it does |
|---|---|
| `mp.importObject(file)` | imports an object as the GUI import does with "Full processing": references by filter resolved, added through the Clockwork, overwriting an existing one |
| `mp.importObjects(dir)` | every `*.xml` file of the directory, in file name order, as one run with one result |
| `mp.importObjectXml(xml)` | like `importObject`, for XML text |
| `mp.importObjectRaw(file)` | stored as is (raw), nothing evaluated |
| `mp.importResource(file)` / `mp.importResource(file, options)` | the resource definition as deployed, with only its connector replaced by the mirror connector; returns its `TargetSystem` |
| `mp.importResource(file, ResourceOptions.defaults().realConnector())` | the resource on its real CSV or LDAP connector, with a file or an in-memory LDAP server of the tester (see [Real connectors](real-connectors.md), experimental) |
| `mp.target(resourceNameOrOid)` | the `TargetSystem` of an imported resource |
| `mp.csv(resource)`, `mp.ldap(resource)` | the CSV file / the LDAP server of a resource on its real connector |

Files given to these calls are indexed: that is how evaluations get their identity and file location. XML given as
text is written to `<workDir>/tester-xml/` and indexed from there; a resource as imported (with the mirror connector) is
written to `<workDir>/tester-resources/`.

**Target systems.** The mirror connector is an in-memory ConnId connector that reproduces the real resource's schema
(from the stored `<schema>` of a definition exported from midPoint; without one, the object classes and attributes
`schemaHandling` uses, as strings) and its activation and password capabilities. A `TargetSystem` reads and changes it
with the real resource's names: objects by their naming attribute (`icfs:name`, or e.g. the DN), attributes by local
name. A test's write of an attribute the object class does not have (`t.target(...).add`, `modify`) is refused
(`IllegalArgumentException`): midPoint would never see it, so it is a typo of the test. Validity dates (ConnId's `__ENABLE_DATE__` / `__DISABLE_DATE__`, midPoint's `activation/validFrom` / `validTo`)
are there when the native capabilities list them, or, without those, when `schemaHandling` maps them; they are
attributes in milliseconds (`attribute(account, "__ENABLE_DATE__")`). A status simulated with an attribute
(`capabilities/configured/activation/status/attribute`) is that attribute, as on the real connector: the mirror then
has no native status and has the attribute even when `schemaHandling` does not name it; `enabled(account)` and
`setEnabled(account, enabled)` read and write the attribute with the enable and disable values (on the real CSV and
LDAP connectors too, see [Activation and validity](midpoint-features.md#activation-and-validity)). Passwords are stored (`password(account)`,
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
A multi-valued attribute the target system keeps as a map of `key=value` values (an application's settings) is
declared with its separator, `ResourceOptions.keyedValues("Connection", "parameters", "=")` (manifest:
`keyedValues: { Connection: { parameters: "=" } }`): a value added replaces the value of its key
(`theme=dark` replaces `theme=light`) and a value removed removes its key, with or without a stored
schema; a plain multi-valued attribute would keep both values. `keyedValues` is the shortcut of the general form, a
Groovy expression that makes the values the target system keeps when midPoint writes the attribute:
`ResourceOptions.store("Connection", "parameters", expression)`, in the manifest

```yaml
targets:
  App:
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
default added). An attribute has `keyedValues` or `store`, not both.
Classes named by different attributes take one each:
`ResourceOptions.namingAttribute("Group", "displayName")` (manifest: `namingAttribute: { User: userName, Group:
displayName }`). An identifier the target system issues on create (an order or ticket
number) is made by a Groovy expression of the created object's `name`, its other `attributes` (name -> list of
values) and `now` (the tester's clock, an `Instant`): `ResourceOptions.identifier("ticketId", expression)`
(manifest: `identifier: { attribute: ticketId, value: "name + '/' + now.toEpochMilli()" }`); without the expression a
generated identifier is a random UUID. Creating an object whose identifier, as the expression makes it, is already
there fails as an existing object; a target system that only hands back an identifier it issued before (a
certificate service given the identifier of a certificate it holds) takes `existing: adopt` (`identifier: {
attribute: certId, value: "…", existing: adopt }`, `ResourceOptions.identifier(attribute, expression, true)`): the
create adds nothing and returns that object. A connector that gives ConnId's `__UID__` no native name (a stored schema
shows it as `icfs:uid` beside `icfs:name`) may still issue it apart from the name, which the schema does not say:
`identifier: icfs:uid` (or with a `value`) makes the mirror issue it, so a rename keeps it; without it the
identifier is the name. Classes that each have an identifier of their own, issued apart from the name (SCIM's users and
groups: `userId`, `groupId`), take one each: `ResourceOptions.classIdentifier("User", "userId")` (manifest:
`identifier: { User: userId, Group: groupId }`); the mirror issues a random UUID for each. This is what a SCIM
connector does (it gives `__UID__` the native name `userId`; midPoint keeps it as the shadow's `ri:userId`), so it is the
way to write such a resource without a stored schema; keep a stored schema only for what it says beyond that (a
read-only `groups`, types, multi-valued attributes). Without the identifier, `userId` is an ordinary attribute of the mirror's users that
midPoint never sets: midPoint fails the assignment ("No value attribute ... in shadow") and reads no membership, and
a failed `memberOf` says to give the identifier. Give such a resource
`referentialIntegrity()` (manifest: `referentialIntegrity: true`) too: a SCIM service removes a deleted user from its
groups. Matching rules need not be given: midPoint compares the association's values by the association's own
`<matchingRule>`, not by the attributes' types. On 4.0, do not name the identifier `id` (4.0 cannot read back a shadow
with an attribute named so).

Memberships are read as midPoint reads them: `group(name).members()`, `hasMember` and `account(name).groups()` compare
the association's references with the `valueAttribute` of each account (of each group, for `subjectToObject`): the
account's identifier when the attribute is the class's identifier (or `icfs:uid`), its name when it is the naming
attribute (or `icfs:name`), else the attribute's values; an account with none is no member. A failed `memberOf`
shows what it looked for. On 4.9 and later, an `associationType` works the same: the tester reads its simulated
reference type (`capabilities/configured/references/type`: the subject's and the object's
`primaryBindingAttributeRef`, by `direction`) as it reads a legacy `association` (`associationAttribute`,
`valueAttribute`), for the mirror's attributes and referential integrity as for the memberships. The resources the tester cannot stand in for are reported together, with the
manifest's other mistakes, in one load failure. `TargetSystem` has the same calls for accounts, groups (the
first non-account object type) and any object class by name (`addObject(objectClass, ...)`, `identifier(...)` for
references by `__UID__`). Changes made there are seen by midPoint when it next reads the object, and by the next
live synchronization run: the mirror connector synchronizes as the CSV connector does, a token standing for the
mirror's content at that moment and a run reporting the objects added, changed (name, status, password or stored
attributes) and deleted since (see [Live synchronization](low-level-api.md#live-synchronization)).

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

A deployment whose lines need different schemas points each line module at its own directory.

### Provisioning scripts and additional connectors

A resource's provisioning scripts (its `<scripts>`: a home directory made after an account is added, archived before
one is deleted) usually run through an additional connector, an SSH connector to a file server. The tester runs no
script and reaches no such server: the resource's `additionalConnector`s go, and the mirror accepts the scripts
itself (a resource on its real connector: [Real connectors](real-connectors.md)). midPoint routes the scripts to the
mirror, and the effects of a verb have each script as a connector call of the resource (`executeScript`, a write):
its language, its code as the resource has it, where it runs (`resource` or `connector`), and its arguments as
midPoint evaluated them; its attributes are the argument names, so `only(...)` and an exhaustive `verifyAll` check
them like an account's.

```java
Effects hired = t.task("Reconcile HR").run();
hired.verifyAll(c -> {
    c.connectorCalls().to("LDAP").adds().of(dn).single();
    c.connectorCalls().to("LDAP").scripts().single()                    // after the add, as the resource says
            .hasLanguage("bash").hasArgument("user", "e1001").codeContains("mkdir").only("user", "department");
    // ...
});
hired.connectorCalls().to("LDAP").scripts().withArgument("user", "e1001").count();
```

A script's return value is not kept: midPoint only logs it. A script fails as its connector makes it fail: the SSH
connector fails on a broken connection only (a command's exit status is logged, not checked), and midPoint then
fails the operation or warns, as the script's `criticality` says (`fatal` by default). `t.target("LDAP").scriptsFail()`
makes the resource's scripts fail so, until the test ends (a reset or checkpoint undoes it); the call is then
`failed()`. What else an additional connector gives midPoint (a capability such as `references`, 4.9+) is not
tested.

### Real connectors

A resource can run on its real connector instead of the mirror: see [Real connectors](real-connectors.md)
(experimental).

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
  (to see it, import a task with `ownerRef` to a user with limited roles).
  No production path evaluates
  authorization filters with no principal at all (an anonymous request has only `ROLE_ANONYMOUS`, a privileged call
  only the unrestricted authorization), so there is no driver for it.

**Channels.** A hook or a script may act on the channel a change came through (`context.getChannel()`: REST for a service
that calls midPoint, a GUI page). `t.inChannel(channelUri, () -> ...)` runs the driver calls of the block
in that channel (their tasks carry it), e.g.
`t.inChannel("http://midpoint.evolveum.com/xml/ns/public/common/channels-3#rest", () -> t.midpoint().modifyAdd(...))`;
outside a block the calls run in midPoint's default user channel.

**Calls on a user's behalf.** A service that calls midPoint for its users (a self-service application over midPoint's REST API) logs a user
in, maybe privileged, owns the tasks for them and comes through its channel. `t.callAs(caller, () -> ...)` makes the
driver calls of the block that way, with a `Caller`: `Caller.user("jdoe")` (the user's own authorizations),
`.privileged()` (all authorizations on top, as midPoint's `runPrivileged` and a service account's "run privileged"
option), `.owner("jdoe")` (the owner of the tasks the calls create, the requester of the approvals they start; the
user by default) and `.channel(uri)`. Hooks, scripts, authorizations, approvals and the audit's initiator then see the
calls as in production; a call of the block that names its own user (`t.actor(...)`) keeps it.

```java
t.callAs(Caller.user("jdoe").privileged().channel(REST_CHANNEL), () -> t.user("jdoe").modify(m -> m.set("telephoneNumber", "123")));
t.callAs(Caller.user("api-client").owner("jdoe"), () -> t.midpoint().bulkAction(script));
```

**Code of the deployment the test calls itself.** A class of the deployment that midPoint does not run by itself in the
tester (a custom endpoint whose server needs midPoint's web application) is made with midPoint's beans
injected by `t.midpoint().createBean(type)`, and called as a driver call by `t.midpoint().invoke(label, (task, result)
-> ...)`: in the call's task (the `callAs` caller's), its evaluations recorded, the background work it starts waited
for, failing as a driver call fails. Its protocol (how its server authenticates and builds the call) stays in the
project's tests.

## midPoint's REST API

A client of midPoint's REST API (a portal, a script, another system) is tested with the request it sends:
`t.rest()` (midPoint 4.8 and later). The request goes through midPoint's own authentication (the security policy's
sequence of the REST channel, e.g. HTTP Basic), its authorizations (the REST ones and the model's), the proxy header
`Switch-To-Principal`, its controllers and its JSON, XML and YAML converters, without an HTTP server: in the test's
JVM, as a driver call (its mappings recorded, the background work it starts awaited, its effects in the response).

```java
t.user("portal").modify(m -> m.password("Portal-Secret-1"));
RestResponse r = t.rest().basic("portal", "Portal-Secret-1")
        .header("Switch-To-Principal", t.user("jdoe").oid())
        .patch("/ws/rest/users/" + jdoeOid, "{\"objectModification\": {\"itemDelta\": {\"modificationType\": \"replace\","
                + " \"path\": \"telephoneNumber\", \"value\": \"+420 123\"}}}");
assertThat(r.status()).isEqualTo(204);
r.effects().changes().of("jdoe").single().only("telephoneNumber");
assertThat(t.rest().basic("jdoe", "wrong").get("/ws/rest/self").status()).isEqualTo(401);
```

- `basic(user, password)`, `header(name, value)`, `accept(mediaType)` (JSON by default); `get`, `delete`, `post`,
  `put`, `patch` with a JSON body or one of another media type. `RestResponse`: `status()`, `header(name)`,
  `body()`, `json()` (Jackson's tree, e.g. a bulk action's output of `/rpc/executeScript`), `effects()`.
- The caller is who the request's headers say: the test's login and `t.callAs` do not apply. A password is set by the
  test (`modify(m -> m.password(...))`): the deployment's password policy applies to it.
- A successful login is recorded in the user (`behavior/authentication`, as in production): it is in the request's
  effects.
- The deployment's system configuration must reference a security policy (`globalSecurityPolicyRef`; midPoint's
  initial system configuration references its default one). Without it midPoint answers 500 with no body, which the
  tester turns into an error that names the reference.
- midPoint's own controllers only (`/ws/rest`, `/rest/model`, `/api/model`): a deployment's own endpoint is called
  as in [Customized midPoint deployments](customized-deployments.md#plugins-with-servers-of-their-own). Bearer tokens
  (an OIDC resource server) are not covered yet.
- The first request of a JVM starts the web layer (about 0.3 s), which then serves every test and deployment of the
  JVM. 4.0 and 4.4 serve REST through CXF: `t.rest()` fails there.

## midPoint's constants

`<const>` expressions and `midpoint.getConst(...)` read the `<constants>` of midPoint's config.xml, which a deployment
sets per environment. The manifest gives them for the tests:

```yaml
constants: { environment: test, mailDomain: example.com }
```

They are set before the import (a resource's configuration may use them) and again before every test.

## HTTP services

A script that calls an HTTP service (a webhook of a chat, a REST API) reaches nothing in a test. The manifest's
`http.redirect` lists URL prefixes the tester stands in for:

```yaml
http:
  redirect: ["https://hooks.example.com"]
```

In the tester's copy of the configuration, `https://hooks.example.com/x` becomes `http://tester-http.invalid/hooks.example.com/x`; the
JVM's proxy selector sends that host to an in-memory HTTP server, which records the request and answers `200` with an
empty body. A test reads them as the configuration wrote them, since the test began:

```java
assertThat(t.httpRequests()).singleElement().satisfies(r -> {      // mp.httpRequests()
    assertThat(r.url()).startsWith("https://hooks.example.com/");
    assertThat(r.body()).contains("archived");
});
```

The server's port is the JVM's and not in the database (a cached configuration works in every fork), and HTTPS needs no
certificate (the copy says `http`). It covers the clients that ask the default `ProxySelector`, `java.net.URL`'s
connections among them (a Groovy script's `new URL(url).openConnection()`); a client that does not (Apache HttpClient
by default, the JDK's `HttpClient` without a proxy) reaches the redirected host, which resolves nowhere, and fails.
Such a client is given the server's own address instead: the system property `tester.http.url`
(`http://127.0.0.1:<port>`, set before midPoint starts), e.g. `ticketing.base-url=${tester.http.url}/ticketing` in the
properties of the deployment's beans (see [Extension beans](#extension-beans)); its requests are recorded with that
address.

**Answers.** A test sets what the server answers, for what a script does with a service's error or result:

```java
t.httpServer().on("POST", "https://hooks.example.com/").respond(503, "{\"message\":\"unavailable\"}");
t.httpServer().on("GET", "/ticketing/api/tickets").respond(200, "{\"id\":\"T-1\"}");
```

`on` takes a method and a URL prefix as the configuration has it (with a scheme) or a path prefix (from `/`); the body
is sent as JSON when it is an object or an array, else as text (null: none). The last answer given for a request
wins; without one a request gets `200` and no body. The answers are forgotten after the test.

## Other systems' databases and the scripts' environment

A script may read or write another system's database over JDBC (an application's, a data warehouse's),
at a URL it reads from an environment variable. The manifest stands in for such a database with an in-memory
PostgreSQL of its own, created from SQL files (the other system's table definitions), and gives the scripts their
environment, which may name the stand-in (its URL is known only when the tester runs):

```yaml
databases:
  crm: { init: [databases/crm.sql] }
environment:
  CRM_JDBC_URL: "${database.crm.url}"
  CRM_JDBC_USER: "${database.crm.user}"
  CRM_JDBC_PASSWORD: test
```

```java
t.database("crm").execute("INSERT INTO customers (id, owner) VALUES (?, ?)", "C-1", "jdoe");
assertThat(t.database("crm").rows("SELECT * FROM orders")).isEmpty();
```

**Times.** The stand-in is part of the simulated world: a time a test writes for the other system is on the test's
clock, `t.clock().now()` (an `Instant` parameter is written as a timestamp), because the scripts read that clock too
(Groovy's `System.currentTimeMillis()`, `new Date()`, `basic.currentDateTime()`), also when they compare with the other
system's times on purpose without midPoint's clock: what they rely on is one time base for both. The stand-in's own
`now()`, `CURRENT_TIMESTAMP` and column defaults are the real time: give times as values. Groovy compiled statically
(`@CompileStatic`, an expression profile with type checking) and Java code read the real `System.currentTimeMillis()`
and do not follow the test's clock (see [Activation and validity](midpoint-features.md#activation-and-validity)).

Each test starts from the init scripts' content. `environment` is what Groovy's `System.getenv(name)` returns (the
process's environment for other names); Java code reads the process's environment, which Surefire's
`environmentVariables` sets for fixed values.

## Customized DDL of midPoint's repository

A deployment with DDL of its own on midPoint's repository (indexes, constraints, tables) names the SQL files in the
manifest (`repository.ddl`): see [Customized DDL of the repository](customized-deployments.md#customized-ddl-of-the-repository).

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

On 4.4 the GUI module is a war, whose classes are its `classes` jar: add `<classifier>classes</classifier>`.

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
stored system configuration is its own.
