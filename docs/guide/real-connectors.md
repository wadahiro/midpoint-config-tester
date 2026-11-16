# Real connectors (experimental)

Experimental: the manifest's keys and the API may still change.

By default every resource runs on the [mirror connector](environment.md#configuration-and-target-systems), which
checks what midPoint sends and reads. A resource can run on its real connector instead, for the behaviour the mirror
does not reproduce: how the connector parses and writes the target system's data, the system's own checks. Today the
real connectors are Evolveum's CSV and LDAP connectors, against stand-ins the tester provides and restores with
midPoint's state between tests: a file of its own and an in-memory LDAP server.

## CSV and LDAP against the tester's stand-ins


`ResourceOptions.defaults().realConnector()` keeps a resource on its real connector instead of the mirror, for the
behaviour the mirror does not reproduce: CSV parsing and quoting, LDAP schema checks, DN handling, how the connector
names attributes (the CSV connector shows the unique column as `ri:<column>`, not `icfs:name`). Supported: the
Evolveum CSV connector (`com.evolveum.polygon:connector-csv`) and LDAP connector (`connector-ldap`, `LdapConnector`;
not `AdLdapConnector`). The connectors are the versions the midPoint line ships; they are dependencies of the tester artifact (bundle jars, their
libraries inside, discovered by midPoint on the classpath).

In the manifest, a target names its connector; the example [`examples/hr-ldap`](../../examples/hr-ldap) runs its HR
and LDAP resources so:

```yaml
targets:
  HR:   { connector: csv }
  LDAP: { connector: ldap, initialData: initial-data/directory.ldif, referentialIntegrity: true }  # the server's content
```

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
  attribute the schema has (e.g. `employeeType`). The server keeps a
  change log (`cn=changelog`, the root DSE's `changeLog`, `firstChangeNumber` and `lastChangeNumber`), which the
  connector's live synchronization reads by change number (`synchronizationStrategy` `auto` finds it; `sunChangeLog`
  names it). The in-memory server's change log differs from those the connector is written for in two ways
 : a deletion has no `targetUniqueID`, so the connector (with `uidAttribute`
  `entryUUID`) skips it and the shadow stays; a rename is `changeType: moddn`, which the connector does not know (it
  knows `modrdn`), so the run fails and midPoint suspends the task.
- Resets restore the file and the server content like a mirror's, with the CSV connector's copies for live
  synchronization and the LDAP server's change log, so the token in the restored database finds its copy or its change
  number; a class that no longer uses the resource stops its server.
- Additional connectors (an SSH connector that runs the provisioning scripts) each go to a mirror of their own that
  accepts scripts and nothing else, as such a connector has, so midPoint keeps the real connector's schema and
  accounts; the scripts are recorded and fail as on a mirror
  ([Provisioning scripts and additional connectors](environment.md#provisioning-scripts-and-additional-connectors)).
