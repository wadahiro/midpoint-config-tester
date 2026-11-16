# Static checks

`midpoint-config-tester-static-<line>` (every supported line) checks a directory of configuration XML against that midPoint line
without starting midPoint
(no Spring, no database; a few seconds, most of it initializing prism). Every finding carries the identity the tester
gives evaluations of the same place.

| Check | Finds | Severity |
|---|---|---|
| `COMPILE` | every Groovy place (a script without `<language>` in the deployment's default language: [Scripting on 4.11](lines.md#scripting-on-411)) compiled on the line's classpath (model-impl, notifications, report, workflow, certification; no GUI, connector or custom jars, which `classpath(...)` adds): missing or removed classes, syntax errors. Missing methods are found only at run time | error |
| `SCHEMA` | prism parsing of each file, strict and in compatibility mode | error / warning |
| `UPGRADE` | ninja verify's `ObjectUpgradeValidator` per object, each item path located in the file | error for `CRITICAL`, warning for `NECESSARY` |
| `LINT` | evaluator elements the line does not know; mappings sharing a name in one object; Groovy that reads the JVM's clock rather than midPoint's `Clock` (`jdk-time`: `new Date()`, `Instant.now()`, `basic.fromNow`...: fine with the tester's `t.clock()`, which moves both, not with a clock override of midPoint alone); elements told apart by position only | error / warning / warning / note |

```java
StaticReport r = StaticCheck.of(Path.of("config")).schemaDir(Path.of("config/schema"))
        .classpath(Path.of("lib"), Path.of("ext/target/ext.jar"))   // the deployment's own jars (a directory: its jars)
        .run();
r.write(Path.of("target/static-check.md"));
r.assertNoErrors(Finding.Check.COMPILE);   // AssertionError: one line per failing place
assertThat(r.errors()).isEmpty();          // also findings(check[, severity]), compileErrors(), places()
```

Without writing a test, in a clone of the tester's repository: `mvn test -pl tester/static/midpoint-4.8 -am -Dstatic.dir=<dir> [-Dstatic.schemaDir=<dir>]
[-Dstatic.classpath=<jar,dir,...>]` writes `target/static-check/<dir name>.md` and fails on compile errors; for
another build of the line's midPoint (a support build, a fork), add `-Dmidpoint48.version=<version>`. Use the module of your line, with
Maven on its JDK.
