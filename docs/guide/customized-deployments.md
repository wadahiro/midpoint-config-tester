# Customized midPoint deployments

A deployment does not always run midPoint as Evolveum releases it. This page covers three kinds of customization, and
how the tester runs each one as production runs it:

- [A custom build of midPoint](#a-custom-build-of-midpoint): a support build or a fork of the line's midPoint.
- [An overlay](#an-overlay): the deployment's own code and files built into midPoint's web application.
- [Customized DDL of the repository](#customized-ddl-of-the-repository): indexes, constraints and tables of the
  deployment's own on midPoint's database.

What the tester does not run at all (the web application, the GUI) is in
[Limitations](reference.md#limitations).

## A custom build of midPoint

A deployment may run a build of midPoint other than the release: a support build with fixes not yet released, or a
fork with patches of its own. The tests should run on the same jars.

Install the build into the local Maven repository (`mvn install` in its source tree) or deploy it to a repository the
project reads, then set its version in the test module's POM:

```xml
<properties>
    <!-- the build production runs: the parent manages midPoint's own modules at this version -->
    <midpoint48.version>4.8.12-patch1</midpoint48.version>
</properties>
```

The property is `midpoint.version`, or the one of the parent's line (`midpoint40.version`, `midpoint44.version`,
`midpoint48.version`...). The parent manages midPoint's own modules at that version (midPoint's BOM does not), so
midPoint's jars on the test classpath are those of the build, the GUI classes too.

The tester itself stays the one built for the line's release. It calls midPoint's internal APIs, so the build must stay
binary compatible with the release it is based on: fixes and patches inside methods are fine, a changed signature of a
service the tester calls fails at the first call (`NoSuchMethodError`). A build of another line (a 4.8 fork based on
4.9) needs the parent of that line.

## An overlay

An overlay is a Maven project that adds the deployment's own files to midPoint's web application (`midpoint.war`, or
the `lib/` and `WEB-INF/classes` of a distribution). The tester does not start the web application: it runs midPoint's
services in a Spring context of its own. Each part of an overlay goes to the tests in its own way.

| What the overlay holds | In production | In the tests |
|---|---|---|
| Java classes the scripts call (utilities, services) | a jar in `WEB-INF/lib` | a test dependency |
| Spring beans (hooks, services) | the overlay's Spring configuration | `tester/ctx-extension*.xml` on the test classpath |
| Classes that replace midPoint's (same name) | `WEB-INF/classes`, read before the jars | test resources, read before the jars |
| Initial objects that replace midPoint's | `WEB-INF/classes/initial-objects` | a directory of `config.dirs` |
| Extension schemas (`*.xsd`) | `midpoint.home/schema` | `tester.extensionSchemaDir` |
| Texts of messages (`Midpoint_ja.properties`, `schema_ja.properties`) | `midpoint.home/localization` | `config.localization` |
| Patches applied by a Java agent (Byteman) | `-javaagent` of the server | `-javaagent` of the test JVM |
| Servers of plugins (gRPC, custom REST endpoints) | started by the web application | not started: the tests call their services |

### Java classes and Spring beans

Add the overlay's jar as a test dependency, at the version production runs. A jar shaded with its dependencies is best
added without its transitive dependencies, which would clash with midPoint's (Jackson, Spring):

```xml
<dependency>
    <groupId>com.example.idm</groupId>
    <artifactId>idm-extensions</artifactId>
    <version>1.4.0</version>
    <classifier>shaded</classifier>
    <scope>test</scope>
    <exclusions>
        <exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>
    </exclusions>
</dependency>
```

Its Spring beans are not in the tester's context until a `tester/ctx-extension*.xml` imports them (a component scan of
its packages, the properties the beans need): see [Extension beans](environment.md#extension-beans). What the beans
call outside midPoint is stood in for through [HTTP services](environment.md#http-services) and
[Other systems' databases](environment.md#other-systems-databases-and-the-scripts-environment).

### Classes that replace midPoint's

An overlay may put a class of midPoint's own name in `WEB-INF/classes`, which the web application reads before the
jars of `WEB-INF/lib`: a patched copy of one midPoint class. Surefire reads `target/test-classes` before the
dependencies, so the compiled classes go there as test resources, limited to the packages the deployment patches:

```xml
<testResources>
    <testResource>
        <directory>src/test/resources</directory>
    </testResource>
    <testResource>
        <directory>${project.basedir}/../overlay/target/classes</directory>
        <includes>
            <include>com/evolveum/midpoint/model/impl/lens/**</include>
        </includes>
    </testResource>
</testResources>
```

Build the overlay first. Leave out classes of the web layer (security configuration, GUI pages): the tester has no web
application to use them, and they may need classes it does not have.

### Initial objects that replace midPoint's

An overlay may replace one of midPoint's initial objects (most often the system configuration) with a file of the same
OID in `WEB-INF/classes/initial-objects`. Add that directory to `config.dirs` of the manifest: the tester imports the
line's initial objects, then the post-initial import, in which a file with the OID of an initial object replaces it as
a whole. The result is the overlay's object, as in production. The post-initial import takes the files in its own file
order, so give such a file a name that sorts before the rest (`000-system-configuration.xml`): the objects after it
are then imported with it in place, as they are with the overlay's initial object.

```yaml
config:
  dirs: [../overlay/src/main/webapp/WEB-INF/classes/initial-objects, post-initial-objects]
```

When the overlay replaces them on one line only, put the directory in that line's overlay of the manifest
(`config.overlays."4.8".dirs`).

### Java agents

Patches applied by a Java agent at the server's start (Byteman rules, a profiler, a security agent) are applied to the
test JVM the same way. Keep the tester's own arguments, `${tester.argLine}`, and add the agent after them:

```xml
<dependency>
    <groupId>org.jboss.byteman</groupId>
    <artifactId>byteman</artifactId>
    <version>4.0.20</version>
    <scope>test</scope>
</dependency>
```

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>${tester.argLine} -javaagent:${org.jboss.byteman:byteman:jar}=script:${project.basedir}/../byteman/patch.btm</argLine>
    </configuration>
</plugin>
```

`${org.jboss.byteman:byteman:jar}` is the path of the dependency's jar (set by the parent's `dependency:properties`).
Use the agent version and the rule files production uses. A rule on a class the tests do not load (a GUI page) has no
effect.

### Plugins with servers of their own

midPoint's own REST API is called with `t.rest()` (4.8 and later, [midPoint's REST
API](environment.md#midpoints-rest-api)). A plugin may start a server of its own inside midPoint's web application: a
gRPC API, a custom REST endpoint. The tester starts
no server. Add the plugin's jar as a test dependency and call its service classes in the test: create them with
midPoint's beans injected (`t.midpoint().createBean(type)`) and call them as a driver call
(`t.midpoint().invoke(label, (task, result) -> ...)`), on a caller's behalf when the server would log one in
(`t.callAs(...)`): see [Code of the deployment the test calls itself](environment.md#extension-beans). How the server
authenticates its clients and builds the call stays in the project's tests.

## Customized DDL of the repository

A deployment whose production database has DDL of its own on midPoint's repository (an index, a unique constraint, a
table, a function, a trigger) names the SQL files in the manifest:

```yaml
repository:
  ddl: [repository/indexes.sql, repository/extra-tables.sql]
```

The tester runs them in order on midPoint's database after the line's schema and before the post-initial import, so
the import may already depend on them.

- **Which DDL matters.** A unique index or constraint matters most: without it, a test accepts a duplicate that
  production rejects. An ordinary index changes no result, only the speed. A table, a function or a trigger matters
  when a script, a bean or a trigger uses it.
- **Resets.** Each test starts from the state after the DDL. What a test writes into a table of the deployment's own is
  dropped at the reset, as midPoint's own tables are.
- **The deployment cache.** The files are part of the key of the
  [deployment cache](writing-tests-junit.md#cache-of-the-loaded-configuration-and-parallel-test-jvms), so a changed
  file loads the deployment afresh. The cache keeps the data of every table, the deployment's own tables included, but
  not their definitions: a restore from the cache runs the DDL first, then copies the data back. A restore into a
  database whose tables or columns differ from the cached ones is refused, and the deployment is loaded afresh.
- **Seed data.** The files may also insert rows (a lookup table of the deployment's own): they are part of the state
  every test starts from.
- **pgmem.** midPoint's repository is PostgreSQL compiled to WebAssembly (pgmem). An extension that midPoint itself
  does not use may be missing: try the DDL early.

