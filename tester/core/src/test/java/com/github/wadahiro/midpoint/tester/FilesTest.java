package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * Files scripts read and write under a directory the configuration names ({@code /opt/midpoint/var}), mapped to a
 * directory of the test: bulk actions, ad-hoc scripts, task scripts. The real directory is never touched.
 */
@MidPointConfigTest(Fixtures.BARE)
class FilesTest {

    static final String VAR = "/opt/midpoint/var";
    static final String INPUT = VAR + "/import/hr.csv";
    static final String OUTPUT = VAR + "/export/users.csv";
    static final File DIR = Fixtures.dir("files");

    @BeforeEach
    void configure(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        // what another test method mapped and wrote is gone
        assertThat(mp.files().mapped()).isEmpty();
        // mapped and written before each test method
        mp.files().map(VAR).write(INPUT, "id,name\n1001,John Doe\n1002,Jane Roe\n");
        assertThat(mp.files().list(VAR)).containsExactly(INPUT);
        // as midPoint's report output directory
        assertThat(mp.files().list(mp.files().exportDirectory())).isEmpty();
    }

    @Test
    void bulkActionReadsAndWritesCsvFiles(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        Answer<BulkActionOutput> out = mp.bulkAction(
                "<s:executeScript xmlns:s='http://midpoint.evolveum.com/xml/ns/public/model/scripting-3'"
                        + " xmlns:c='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                        + "<s:pipeline>"
                        + "<s:search><s:type>c:UserType</s:type></s:search>"
                        + "<s:action><s:type>execute-script</s:type>"
                        + "<s:parameter><s:name>script</s:name><c:value><c:code><![CDATA["
                        + "def out = new File('" + OUTPUT + "')\n"
                        + "out.parentFile.mkdirs()\n"
                        + "out.withWriter('UTF-8') { w ->\n"
                        + "    new File('" + INPUT + "').eachLine { line, n ->\n"
                        + "        if (n > 1) { def (id, name) = line.split(','); w << id << ';' << name.toUpperCase() << '\\n' }\n"
                        + "    }\n"
                        + "}\n"
                        + "new File('" + VAR + "/import/hr.csv').text.readLines().size()"
                        + "]]></c:code></c:value></s:parameter>"
                        + "<s:parameter><s:name>forWholeInput</s:name><c:value>true</c:value></s:parameter>"
                        + "</s:action>"
                        + "</s:pipeline></s:executeScript>");

        WrittenFile written = out.run().fileWritten(OUTPUT);
        assertThat(written.created()).isTrue();
        assertThat(written.lines()).containsExactly("1001;JOHN DOE", "1002;JANE ROE");
        assertThat(out.run().filesWritten()).extracting(WrittenFile::path).containsExactly(OUTPUT);
        assertThat(mp.files().lines(OUTPUT)).containsExactly("1001;JOHN DOE", "1002;JANE ROE");
        // the whole JVM sees the test's directory, the test's own code too; the real directory is not there
        assertThat(new File(OUTPUT)).isFile().hasContent("1001;JOHN DOE\n1002;JANE ROE");
        assertThat(FileUtility.existsOutsideTheJvm(OUTPUT)).isFalse();
        assertThat(mp.files().physical(OUTPUT)).isRegularFile()
                .startsWith(Path.of(System.getProperty("tester.workDir", "target")).toAbsolutePath());
    }

    @Test
    void scriptWithJavaIoAndNio(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        Answer<List<Object>> out = mp.script(
                "import java.nio.file.Files\n"
                        + "import java.nio.file.Path\n"
                        + "import java.nio.file.Paths\n"
                        + "def dir = Paths.get('" + VAR + "', 'out')\n"
                        + "Files.createDirectories(dir)\n"
                        + "Files.write(dir.resolve('nio.txt'), ['a', 'b'])\n"
                        + "def w = Files.newBufferedWriter(Path.of('" + VAR + "/out/nio-writer.txt'))\n"
                        + "w.write('x'); w.close()\n"
                        + "def pw = new PrintWriter('" + VAR + "/out/print-writer.txt'); pw.println('p'); pw.close()\n"
                        + "def fw = new FileWriter(\"${'" + VAR + "'}/out/file-writer.txt\"); fw.write('f'); fw.close()\n"
                        + "def os = new FileOutputStream(new File('" + VAR + "/out/stream.bin')); os.write(7); os.close()\n"
                        + "def reader = new BufferedReader(new FileReader('" + INPUT + "'))\n"
                        + "def header = reader.readLine(); reader.close()\n"
                        + "def scanner = new Scanner(new File('" + INPUT + "')); scanner.nextLine(); def first = scanner.nextLine(); scanner.close()\n"
                        + "def text = new Scanner('not a file').nextLine()\n"
                        + "[header, first, text, Files.readAllLines(Paths.get('" + INPUT + "')).size(),"
                        + " ('" + VAR + "/out' as File).list().sort().join(',')]")
                .evaluate();

        // the output of a script is a set of values: no order
        assertThat(out.value()).containsExactlyInAnyOrder("id,name", "1001,John Doe", "not a file", "3",
                "file-writer.txt,nio-writer.txt,nio.txt,print-writer.txt,stream.bin");
        assertThat(out.run().filesWritten()).extracting(WrittenFile::path).containsExactly(
                VAR + "/out/file-writer.txt", VAR + "/out/nio-writer.txt", VAR + "/out/nio.txt",
                VAR + "/out/print-writer.txt", VAR + "/out/stream.bin");
        assertThat(out.run().fileWritten(VAR + "/out/nio.txt").lines()).containsExactly("a", "b");
        assertThat(out.run().fileWritten(VAR + "/out/stream.bin").bytes()).containsExactly(7);
        assertThat(FileUtility.existsOutsideTheJvm(VAR)).isFalse();
    }

    @Test
    void javaCodeCalledByAScript(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        // Java code (here FileUtility, like a custom library or Commons IO) builds its paths from strings itself
        Answer<List<Object>> out = mp.script(
                "def names = com.github.wadahiro.midpoint.tester.FileUtility.writeAll(dir, 'from java')\n"
                        + "names.join(',') + '|' + com.github.wadahiro.midpoint.tester.FileUtility.firstLine(input)")
                .variable("dir", VAR + "/java")
                .variable("input", INPUT)
                .evaluate();

        assertThat(out.value()).containsExactly("commons-io.txt,file-writer.txt,nio.txt,random-access.txt,resolved.txt,"
                + "stream.txt|id,name");
        assertThat(out.run().filesWritten()).extracting(WrittenFile::path).containsExactly(
                VAR + "/java/commons-io.txt", VAR + "/java/file-writer.txt", VAR + "/java/nio.txt",
                VAR + "/java/random-access.txt", VAR + "/java/resolved.txt", VAR + "/java/stream.txt");
        assertThat(out.run().filesWritten()).allSatisfy(f -> assertThat(f.text()).isEqualTo("from java"));
        assertThat(FileUtility.existsOutsideTheJvm(VAR)).isFalse();
    }

    @Test
    void taskScriptWritesAFile(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name></user>");

        // a single-run task is due when imported: the import waits for it
        Run run = mp.importObject(Fixtures.of(mp, new File(DIR, "task-export-users.xml")));

        assertThat(run.fileWritten(VAR + "/export/user-names.txt").lines()).containsExactly("administrator", "jdoe");
        // the script parameter of an execute-script action (the bulk action of the task's activity; on 4.0 in the
        // task's extension), among the scripts the task's run evaluated (also midPoint's own, e.g. of an actor)
        assertThat(run.select().evaluator("script").list()).filteredOn(e -> "export user names".equals(e.task()))
                .extracting(e -> e.identity().toString())
                .contains("task:export user names/" + (Lines.atLeast(mp, "4.4")
                        ? "activity/work/nonIterativeScripting/scriptExecutionRequest" : "extension/executeScript")
                        + "/pipeline/action/parameter[script]");
    }

    @Test
    void reportExportWritesItsFile(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        assumeTrue(Lines.atLeast(mp, "4.4"), "midPoint 4.0 has no object collection reports");
        // here, not in @BeforeAll: the users would change what the other tests' scripts see on 4.4+ only
        mp.importObject(new File(Fixtures.dir("async"), "reported-users.xml"));
        mp.importObject(new File(Fixtures.dir("async"), "object-collection-employees.xml"));
        mp.importObject(Fixtures.of(mp, new File(Fixtures.dir("async"), "report-users.xml")));

        Reports.Output out = mp.reports().export("users-by-cost-center");

        // midPoint writes the export to <midpoint.home>/export, a directory of the tester
        WrittenFile file = out.run().filesWritten().stream().filter(f -> f.name().endsWith(".csv")).findFirst()
                .orElseThrow();
        assertThat(file.path()).startsWith(mp.files().exportDirectory());
        assertThat(file.lines()).isEqualTo(out.lines());
        assertThat(mp.files().list(mp.files().exportDirectory())).containsExactly(file.path());
    }

    @Test
    void scriptDeletesAFile(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        Run run = mp.script("new File('" + INPUT + "').delete()").evaluate().run();

        assertThat(run.filesDeleted()).containsExactly(INPUT);
        assertThat(mp.files().exists(INPUT)).isFalse();
        // the next test method gets it back (eachTestStartsFromTheFilesOfBeforeAll)
    }

    /**
     * A shell command the JVM starts (a report's post-report script, {@code "...".execute()}) does not go through
     * {@code File} or {@code Path}: the mapped directories written in its arguments become the physical ones. A sibling
     * with the same prefix ({@code /opt/midpoint/variable}) stays.
     */
    @Test
    void shellCommandsSeeTheMappedDirectory(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        Object out = mp.script("def p = ['/bin/sh', '-c', 'mkdir -p " + VAR + "/shell && { head -1 " + INPUT
                + "; echo /opt/midpoint/variable/x; } > \"" + VAR + "/shell/out.txt\" && cat " + VAR + "/shell/out.txt']"
                + ".execute()\n"
                + "p.waitFor()\n"
                + "p.exitValue() + ':' + p.text").evaluate().value().get(0);

        assertThat(out).isEqualTo("0:id,name\n/opt/midpoint/variable/x\n");
        assertThat(mp.files().lines(VAR + "/shell/out.txt")).containsExactly("id,name", "/opt/midpoint/variable/x");
    }

    @Test
    void pathsOutsideMappedDirectoriesAreLeftAlone(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        Answer<List<Object>> out = mp.script(
                "[new File('/nonexistent-tester-dir/x.txt').path, new File('/opt/midpoint/variable/x').path,"
                        + " java.nio.file.Paths.get('/nonexistent-tester-dir').toString(),"
                        + " new File('/nonexistent-tester-dir/x.txt').exists()]")
                .evaluate();

        // a sibling with the same prefix (/opt/midpoint/variable) is not under /opt/midpoint/var
        assertThat(out.value()).containsExactlyInAnyOrder("/nonexistent-tester-dir/x.txt", "/opt/midpoint/variable/x",
                "/nonexistent-tester-dir", "false");
        assertThat(out.run().filesWritten()).isEmpty();
    }

    @Test
    void mapAndWriteRules(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        // the test's own input files go under mapped directories only
        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class,
                () -> mp.files().write("/nonexistent-tester-dir/in.csv", "x"));
        assertThat(outside).hasMessageContaining("mp.files().map(\"/nonexistent-tester-dir\")");
        // nor can the tester's own directory be mapped
        assertThrows(IllegalArgumentException.class, () -> mp.files().map("/"));
        AssertionError missing = assertThrows(AssertionError.class, () -> mp.files().read(VAR + "/import/none.csv"));
        assertThat(missing).hasMessageContaining(INPUT);

        // a mapping made in a test method is gone after it (eachTestStartsFromTheFilesOfBeforeAll lists one root)
        mp.files().map("/srv/hr");
        assertThat(mp.files().mapped()).containsExactly("/opt/midpoint/var", "/srv/hr");
    }
}
