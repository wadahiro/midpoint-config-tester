# Setting up a config repository

A config repository needs a POM with one of the tester's parents (one per line), its
`post-initial-objects`, the manifest `midpoint-config-tester.yaml` next to the POM, and tests. The example
[`examples/getting-started`](../../examples/getting-started) is such a repository:

```
pom.xml                         the parent of the line and the repository's coordinates
midpoint-config-tester.yaml     the manifest: config directories, target systems
post-initial-objects/           as deployed to midpoint.home/post-initial-objects
src/test/java/...               the tests with JUnit
src/test/kotlin/...             the tests with Kotest
```

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
```

The releases are in the Maven repository on the branch `mvn-repo` of the tester's GitHub repository; declare it in
the POM, where Maven also looks for the parent:

```xml
<repositories>
    <repository>
        <id>midpoint-config-tester</id>
        <url>https://raw.githubusercontent.com/wadahiro/midpoint-config-tester/mvn-repo</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

The parent `midpoint-config-tester-parent-<line>` brings:

- the tester artifact of the line (test scope) and JUnit, with the line's dependency management (midPoint's BOM, and
  the versions the tester pins over it: without them the repository's class path is not the one the tester is tested
  with);
- the generator of typed names, run at `generate-sources` on the manifest (its `names.package`), its output a test
  source root;
- Surefire: one JVM for all test classes (one midPoint), `-Dtester.forks=N` for more, the manifest as the default of
  `@MidPointConfigTest`, the agent of the file redirection (4.4: the tester attaches it itself), and the tests on the
  JDK that runs Maven, one the line's midPoint supports: the parent checks it (see [Supported lines](lines.md)); 4.4 compiles with
  `--release 11`;
- Evolveum's repository and the pgmem profiles: the in-memory PostgreSQL
  ([pgmem](https://github.com/shibukawa/pgmem)) is a native binary from `io.github.shibukawa.pgmem:pgmem-native`,
  classifier `linux-x86_64` (default), `darwin-arm64`, `linux-arm64` or `windows-x86_64` by OS; on another platform
  give a binary with `-Dpgmem.binary=<path>` (or `PGMEM_BINARY`).

The time zone of the midPoint server is required, `tester.timeZone` (an IANA ID): the parent passes it to the test JVM
(`-Dtester.timeZone`, `-Duser.timezone`), and the tester fails before midPoint starts without it (`tester.timeZone is
not set: ...`) or on one that is no time zone. midPoint and the scripts then run in it on every machine: a local date a
script reads (`basic.parseDateTime('yyyy-MM-dd', ...)`, `LocalDate.now()`) is the server's, so a start date begins at
midnight on the server whether the test runs on a laptop in Tokyo or a CI machine in UTC. The
start log and the load report say it (`time zone Asia/Tokyo (tester.timeZone)`, with the zone the JVM started in when an
argLine without the parent's left it to the tester). It is one per JVM: midPoint's database connections, Quartz and
the logging take it when they start; a manifest may state it (`timeZone: Asia/Tokyo`), and a load fails on another one.

On 4.11 a second property is required, `tester.safeScriptingLanguagesOnly`, as the server's `config.xml` has
`expressions/safeScriptingLanguagesOnly`: `true` (a fresh 4.11 installation's `config.xml`) runs only MEL and safe
Velocity, `false` every scripting language, Groovy too (a deployment upgraded to 4.11 keeps its `config.xml`, without
the setting: false). The 4.11 parent passes it; the tester fails before midPoint starts without it. See
[Scripting on 4.11](lines.md#scripting-on-411) for the system configuration, which decides the rest.

Properties a repository may set: `tester.manifest.file` (default `midpoint-config-tester.yaml` next to the POM),
`tester.names.skip`, `tester.forks`, and `midpoint.version` (or `midpoint48.version`... of the parent's line) for
another build of the line's midPoint, a support build or a fork in the local repository: the parent manages midPoint's
own modules at it (midPoint's BOM does not), so the tests run on those jars with the tester built for the line's
release. `mvn test` then loads the configuration once per JVM and runs the tests; the load
report is `target/tester-deployment/<name>/load-report.txt`.

To test one configuration on several lines (an upgrade from 4.4 to 4.8), use one module per line, each with the
parent of its line, the shared tests (`<testSourceDirectory>../src/test/java</testSourceDirectory>`) and
`<tester.manifest.file>${project.basedir}/../midpoint-config-tester.yaml</tester.manifest.file>`; overlays in the
manifest hold what a line needs differently. midPoint keeps static state, so a JVM holds one line.

## The manifest

The manifest `midpoint-config-tester.yaml` points the tester at the configuration:

```yaml
config:
  dirs: [post-initial-objects]          # as deployed to midpoint.home/post-initial-objects
  exclude: ["tasks/900-*.xml"]
  properties: { ldapBase: dc=example,dc=com }       # {{ldapBase}} in the files
  replace: { "@MAIL_HOST@": smtp.example.com }      # texts replaced as they are (as a deployment script's sed)
  overlays:
    "4.4": { dirs: [overlays/4.4], remove: [] }     # files that replace, add or remove files on one line
targets:                                # by resource name; every resource runs on a mirror of its schema
  HR:   { namingAttribute: { AccountObjectClass: empno } }  # no stored <schema>: the attribute naming the accounts
mail: { redirect: true }                # midPoint's mail servers -> the tester's SMTP server (the default)
timeZone: Asia/Tokyo                    # optional: the server's, checked against tester.timeZone
```

A file that midPoint exports, `<object xsi:type="c:RoleType">`, is read as the `<role>` it is (resources moved to
their stand-ins, typed names, expression places).

Once per JVM the tester starts the line's midPoint, imports **the line's initial objects** as midPoint does on its
first start (system configuration, administrator, End user and Approver roles, default security and password
policies, archetypes, collections, reports, the cleanup and scanner tasks; taken from midPoint's own jars), copies the
directories (overlays and properties applied, every resource moved to its stand-in, mail redirected; the user's files
are never changed), runs **midPoint's post-initial import** on the copy (a file with the OID of an initial object,
e.g. the system configuration, replaces it as a whole, as in production; so a deployment that replaces one of
midPoint's initial objects in its build puts that file first, e.g. in an overlay) (its file order, full processing, bulk action files executed, the first
failing file fails the load with midPoint's errors), waits for the background work and takes a snapshot. Every test
starts from that snapshot.
