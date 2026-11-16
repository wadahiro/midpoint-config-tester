# Files scripts read and write

Bulk actions, task scripts, hooks and mappings often read input files (`/opt/midpoint/var/import/hr.csv`) and write
output files, themselves or through Java utilities; reports write their exports. A test maps the directories the
configuration names to directories of its own, provides the input files there and asserts what was written. The real
directories are never touched.

```java
@Test
void export(MidPointConfigTester t) {
    MidPoint mp = t.midpoint();
    mp.files().map("/opt/midpoint/var")                                    // and everything under it
            .write("/opt/midpoint/var/import/hr.csv", "id,name\n1001,John Doe\n");
    Answer<BulkActionOutput> out = mp.bulkAction(xml);  // new File('/opt/midpoint/var/export/users.csv').withWriter {...}
    WrittenFile users = out.run().fileWritten("/opt/midpoint/var/export/users.csv");
    assertThat(users.lines()).containsExactly("1001;JOHN DOE");
    assertThat(mp.files().read("/opt/midpoint/var/export/users.csv")).isEqualTo(users.text());
}
```

| Call | What it does |
|---|---|
| `mp.files().map(dir)` | maps the directory (absolute, as the configuration names it) to `<workDir>/tester-files/<dir>` |
| `write(path, text \| bytes)`, `read(path)`, `lines(path)`, `bytes(path)`, `exists(path)`, `list(dir)` | the files by the paths the configuration uses; `write` creates parents, `list` gives every file below |
| `physical(path)`, `mapped()`, `exportDirectory()` | where a file really is; the mapped directories; midPoint's report output directory |
| `run.filesWritten()`, `run.fileWritten(path)`, `run.filesDeleted()` | what one driver call created, changed or deleted (`WrittenFile`: `path()`, `name()`, `created()`, `text()`, `lines()`, `bytes()`) |

The directories the deployment's tasks write in, as its volume has them, are mapped by the manifest for every test, with
the tree they start with (files, and empty directories kept in git by a `.gitkeep`, which is not copied):

```yaml
files:
  map: [/opt/midpoint/var]
  initial: { /var/lib/midpoint: files/var-lib-midpoint }   # also mapped; the tree comes back before every test
```

**How far the mapping reaches.** The tester redirects with a Java agent, so the redirection holds for **any code in
the JVM**: Groovy scripts, Java utilities they call (custom jars, Apache Commons IO or CSV, midPoint's own code), the
test itself.

- `java.io.File` and everything built on it (`FileInputStream`, `FileWriter`, `PrintWriter`..., the GDK's `text`,
  `eachLine`, `withWriter`).
- `java.nio` paths of the default file system on Linux and macOS (`Path.of`, `Paths.get`, `File.toPath` and thus all
  of `java.nio.file.Files`).
- The processes the JVM starts (a report's post-report script, Groovy's `"...".execute()`, `Runtime.exec`): a mapped
  directory written in the command's arguments is replaced by the physical one. A path the command builds itself
  (`cd /opt; ls midpoint/var`, a variable or the environment) is not seen.

Only absolute paths under a mapped directory are rewritten, per path segment (`/opt/midpoint/var` does not hold
`/opt/midpoint/variable`), and the innermost mapped directory wins.

**The agent** (also the one that shifts the JVM's clock for `t.clock()`). Nothing is needed by default: the tester attaches the agent itself (`ByteBuddyAgent.install()`), which
works on JDK 11, 17 and 21; JDK 21 prints a warning about a dynamically loaded agent (and a future JDK will refuse it
without `-XX:+EnableDynamicAgentLoading`). The parents of 4.8 and later give it to the JVM in the `argLine`; without
the parent, add the agent jar of the tester's `byte-buddy-agent` dependency to the surefire `argLine`:

```xml
<plugin>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions><execution><goals><goal>properties</goal></goals></execution></executions>
</plugin>
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>... -javaagent:${net.bytebuddy:byte-buddy-agent:jar}</argLine>
    </configuration>
</plugin>
```

If the agent cannot be installed, the tester logs why at its start and `mp.files().map(...)` fails with that reason.

**midPoint's own files.** Report exports are written by midPoint's Java code to `<midpoint.home>/export`
(`ReportUtils.getExportDir()` on every line), which is already a directory of the tester (`<workDir>/midpoint-home`).
It is watched like a mapped directory, by its real path: an export's CSV is in `run.filesWritten()` of the
`mp.reports().export(...)` call (or of the task run that exported it), next to `Reports.Output.lines()`.

Report imports (`mp.reports().importFrom(report, file)`) copy their CSV to `<midpoint.home>/import/` themselves; a
mapped input file can be given by its configured path (`new File("/opt/midpoint/var/import/hr.csv")`), since the
test's own code is redirected too.

The files of CSV resources on the real connector (`mp.csv()`: `filePath` moved to `<workDir>/tester-csv/`) and the
`redirectToFile` and `logToFile` files of mail transports (moved to `<workDir>/tester-mail/files/`, see
[Mail notifications](mails.md#mail-notifications)) are moved by rewriting the configuration when it is imported; they are not
mapped directories and need no mapping.

**What a run wrote** is found by comparing the content of the mapped directories and the export directory before and
after the driver call, so files written by Java code (midPoint, a custom library) into them count too.

**Lifecycle.** Mapped directories and their content are part of the reset: what `@BeforeAll` methods mapped and wrote
(input files) is there in every test method of the class; what a test method mapped, wrote or deleted, and the report
exports, are gone after it (the database snapshot and the files are restored together). The next test class starts
with no mapped directory.

**Policy for other paths.** Only mapped directories are redirected; every other path is left as it is, so a script
reading a file outside them reads the real file system. Map
every directory your scripts use. `write` and the other `mp.files()` calls refuse paths outside mapped directories (and
the export directory), and a directory holding the tester's own `<workDir>` cannot be mapped.

**Limits.**

- Code sees the physical path: `file.path`, `absolutePath`, `Path.toString()` or a path written into an attribute or a
  log shows `<workDir>/tester-files/...`.
- Only absolute paths are mapped. A relative path is resolved against the test's working directory (not midPoint's);
  `..` is not resolved before the comparison (`/opt/midpoint/../midpoint/var` is not under `/opt/midpoint/var`).
- `java.nio` paths are redirected on the Unix file systems of the JDK (Linux, macOS); on Windows only `java.io.File`
  (and what is built from it) is. Paths of other file systems (zip, Jimfs) are not touched.
- Not redirected: native code, processes started with `ProcessBuilder` or `Runtime.exec` (they get the physical path
  only if the Java code passes a `File` or `Path` as text), and a `File` or `Path` constructed before the directory was
  mapped (e.g. kept in a static field), which keeps the path it had.
- File names are compared in UTF-8 bytes for `java.nio` paths (the JDK's encoding of file names on Linux and macOS).
- Report exports go to midPoint's own `<midpoint.home>/export`; midPoint has no setting for another directory, so map
  nothing for them.
