package com.github.wadahiro.midpoint.tester.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.common.MidPointLine;
import com.github.wadahiro.midpoint.tester.check.Finding.Check;
import com.github.wadahiro.midpoint.tester.check.Finding.Severity;

/**
 * The static checks on their fixtures ({@code tester/static/core/src/test/resources/static}). Results that differ
 * between lines are asserted per line ({@link #LINE}). With {@code -Dstatic.dir=<dir>} (and
 * {@code -Dstatic.schemaDir=<dir>}), the checks run on a configuration of your own: the report goes to
 * {@code target/static-check/<dir name>.md}.
 */
class StaticCheckTest {

    private static final Path FIXTURES = Path.of(System.getProperty("static.fixtures"));
    private static final String LINE = StaticCheck.line();
    private static final String LEGACY = "role:Legacy/inducement[construction=20000000-0000-0000-0000-00000000a001/"
            + "account/default]/construction/attribute[ri:dn]/outbound[legacy-dn]/expression";

    private static StaticReport report;

    @BeforeAll
    static void check() {
        report = StaticCheck.of(FIXTURES).run().write(Path.of("target/static-check/static.md"));
    }

    @Test
    void compileErrorsPerPlace() {
        // Groovy 2.5 (4.0) and the Parrot parser (Groovy 3+, 4.4+) word a syntax error differently
        String syntax = MidPointLine.isBefore(LINE, "4.4") ? "expecting ')', found '{'" : "Unexpected input: '{'";
        if (MidPointLine.isBefore(LINE, "4.8")) {
            // the class removed in 4.8 is there; the 4.8 name of the Spring holder is not
            assertThat(where(report.compileErrors())).containsExactly(
                    "role:Legacy/inducement[target=Mailer]/condition/expression",
                    "role:Legacy/inducement[target=Searcher]/condition/expression");
            assertThat(report.compileErrors().get(0).message()).isEqualTo("unable to resolve class com.evolveum."
                    + "midpoint.model.impl.expr.SpringApplicationContextHolder (script line 2, column 25)");
            assertThat(report.compileErrors().get(1).message()).contains(syntax);
            return;
        }
        assertThat(where(report.compileErrors())).containsExactly(LEGACY,
                "role:Legacy/inducement[target=Searcher]/condition/expression");
        Finding removed = report.compileErrors().get(0);
        assertThat(removed.message()).isEqualTo("unable to resolve class "
                + "com.evolveum.midpoint.schema.ResourceShadowDiscriminator (script line 2, column 33)");
        // the identity has no line number; file and line are for display
        assertThat(removed.identity().toString()).doesNotContain("role-legacy.xml").doesNotContainPattern(":\\d");
        assertThat(removed.position()).isEqualTo("role-legacy.xml:13");
        assertThat(report.compileErrors().get(1).message()).contains(syntax);
    }

    /** A script that imports a class of the deployment's own jar compiles once the jar is on the check's classpath. */
    @Test
    void theDeploymentsOwnClassesOnTheClasspath(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/com/example/ext/Helper.java");
        java.nio.file.Files.createDirectories(src.getParent());
        java.nio.file.Files.writeString(src, "package com.example.ext; public class Helper {"
                + " public static String hello() { return \"hello\"; } }");
        Path classes = tmp.resolve("classes");
        java.nio.file.Files.createDirectories(classes);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                src.toString())).isZero();
        Path config = tmp.resolve("config");
        java.nio.file.Files.createDirectories(config);
        java.nio.file.Files.writeString(config.resolve("role.xml"), "<role oid='30000000-0000-0000-0000-00000000f001'"
                + " xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'><name>Ext</name>"
                + "<condition><expression><script><code>import com.example.ext.Helper\nHelper.hello() != null"
                + "</code></script></expression></condition></role>");

        assertThat(StaticCheck.of(config).checks(Check.COMPILE).run().compileErrors()).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("unable to resolve class com.example.ext.Helper"));
        assertThat(StaticCheck.of(config).checks(Check.COMPILE).classpath(classes).run().compileErrors()).isEmpty();
        assertThatThrownBy(() -> StaticCheck.of(config).classpath(tmp.resolve("missing")).run())
                .hasMessageContaining("classpath: no such file or directory");
    }

    @Test
    void assertionListsTheFailingPlaces() {
        Finding first = report.compileErrors().get(0);
        assertThatThrownBy(() -> report.assertNoErrors(Check.COMPILE)).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("2 static check errors in ")
                .hasMessageContaining("ERROR COMPILE groovy " + first.where() + ": unable to resolve class ");
        // a check without errors passes
        StaticCheck.of(FIXTURES.resolve("../static-clean")).run().assertNoErrors();
    }

    @Test
    void schema() {
        // prism 4.9+ names an item and a filter by local name
        String q = MidPointLine.isBefore(LINE, "4.9") ? "{http://midpoint.evolveum.com/xml/ns/public/query-3}" : "";
        String c = MidPointLine.isBefore(LINE, "4.9") ? "{http://midpoint.evolveum.com/xml/ns/public/common/common-3}"
                : "";
        assertThat(report.findings(Check.SCHEMA)).extracting(f -> f.severity() + " " + f.rule() + " " + f.where())
                .containsExactly(
                        "ERROR unreadable not-well-formed.xml",
                        "ERROR strict role:Typo",
                        "WARNING warning role:Typo",
                        // the filter fails prism whatever its evaluator (also with q:value); the lint names the
                        // evaluator where the line has none
                        "ERROR unreadable role:Searcher");
        assertThat(report.findings(Check.SCHEMA, Severity.WARNING).get(0).message())
                .isEqualTo("Item " + c + "descripton has no definition");
        assertThat(report.findings(Check.SCHEMA, Severity.ERROR).get(2).message())
                .isEqualTo("not parsed even in compatibility mode: Unsupported query filter " + q + "equal");
    }

    @Test
    void upgradeItemsAtTheirElements() {
        if (MidPointLine.isBefore(LINE, "4.4")) {
            assertThat(report.findings(Check.UPGRADE)).isEmpty();
            assertThat(report.skipped(Check.UPGRADE)).isEqualTo("midPoint " + LINE + " has no upgrade validator "
                    + "(ninja verify's ObjectUpgradeValidator is 4.4+)");
            return;
        }
        assertThat(report.skipped(Check.UPGRADE)).isNull();
        // the item paths of ninja verify, located in the file; 4.4 calls the items deprecated, not removed
        String state = removed();
        assertThat(report.findings(Check.UPGRADE))
                .extracting(f -> f.severity() + " " + f.where() + " " + f.position() + " " + f.message())
                .containsExactly(
                        "WARNING task:Recompute users/category task-legacy.xml:6 NECESSARY category: " + state,
                        "ERROR task:Recompute users/recurrence task-legacy.xml:8 CRITICAL recurrence: " + state);
    }

    @Test
    void lint() {
        // shadowOwnerReferenceSearch is 4.9+
        boolean known = !MidPointLine.isBefore(LINE, "4.9");
        assertThat(report.findings(Check.LINT)).extracting(f -> f.severity() + " " + f.rule() + " " + f.where())
                .containsExactlyElementsOf(Stream.of(
                        "WARNING jdk-time objectTemplate:Clock template/mapping[end-date]/expression",
                        known ? null : "ERROR no-evaluator role:Searcher/inducement/targetRef/filter/equal/expression",
                        "WARNING duplicate-mapping-name objectTemplate:User template",
                        "NOTE ordinal role:Mailer/inducement[#1]",
                        "NOTE ordinal role:Mailer/inducement[#2]").filter(f -> f != null)
                        .collect(Collectors.toList()));
        // the JVM's clock: one finding per place, every read named once
        assertThat(report.findings(Check.LINT, Severity.WARNING).get(0).message()).isEqualTo(
                "reads the JVM's clock (fromNow(), new Date()), not midPoint's Clock: the tester's mp.time() moves"
                        + " both, a clock override of midPoint alone (or a JVM without the tester's agent) only"
                        + " midPoint's; basic.currentDateTime() (and basic.addDuration(basic.currentDateTime(), 'P1D')"
                        + " for basic.fromNow('P1D')) always follows midPoint's Clock");
        assertThat(report.findings(Check.LINT, Severity.WARNING).get(1).message()).isEqualTo(
                "2 mappings named 'fullName' (selecting by name is ambiguous): "
                        + "objectTemplate:User template/mapping[fullName #1] (object-template-user.xml:5), "
                        + "objectTemplate:User template/mapping[fullName #2] (object-template-user.xml:12)");
        if (!known) {
            assertThat(report.findings(Check.LINT, Severity.ERROR).get(0).message())
                    .isEqualTo("midPoint " + LINE + " has no evaluator <shadowOwnerReferenceSearch>");
        }
    }

    @Test
    void jdkTimeReads() {
        assertThat(StaticCheck.jdkTime("def d = new Date(); def t = System.currentTimeMillis(); new Date ()"))
                .containsExactly("new Date()", "System.currentTimeMillis()");
        assertThat(StaticCheck.jdkTime("java.time.Instant.now(); LocalDate.now(); LocalDateTime.now(zone);"
                + " Calendar.getInstance(); new GregorianCalendar(); basic.fromNow('P1D')"))
                .containsExactly("Instant.now()", "LocalDate.now()", "LocalDateTime.now()", "Calendar.getInstance()",
                        "new GregorianCalendar()", "fromNow()");
        // midPoint's clock, and dates built from given values
        assertThat(StaticCheck.jdkTime("basic.currentDateTime(); basic.addDuration(validTo, 'P1D');"
                + " new Date(millis); new GregorianCalendar(2030, 0, 1); Instant.ofEpochMilli(0); knowNow()")).isEmpty();
    }

    @Test
    void evaluatorsOfTheLine() throws Exception {
        List<String> evaluators = List.of("value", "asIs", "const", "function", "proportional", "generate", "script",
                "path", "associationTargetSearch", "assignmentTargetSearch", "referenceSearch",
                "assignmentFromAssociation", "associationFromLink", "sequentialValue");
        // the association model of 4.9 (common-3.xsd, common-provisioning-3.xsd); <filter> is 4.10+
        if (!MidPointLine.isBefore(LINE, "4.9")) {
            evaluators = Stream.concat(evaluators.stream(), Stream.of("shadowOwnerReferenceSearch",
                    "associationConstruction", "associationSynchronization")).collect(Collectors.toList());
        }
        if (!MidPointLine.isBefore(LINE, "4.10")) {
            evaluators = Stream.concat(evaluators.stream(), Stream.of("filter")).collect(Collectors.toList());
        }
        assertThat(StaticLine.evaluators(StaticLine.prismContext(null)))
                .containsExactlyInAnyOrderElementsOf(evaluators);
    }

    @Test
    void markdown() {
        String md = report.markdown();
        assertThat(md).startsWith("# Static check of static (midPoint " + LINE + ")\n\n"
                + "8 files, 8 objects, 10 expression places (9 with Groovy): ");
        if (MidPointLine.isBefore(LINE, "4.4")) {
            assertThat(md).contains("| Upgrade validator (ninja verify) | skipped | | |\n")
                    .contains("Skipped: midPoint " + LINE + " has no upgrade validator");
            return;
        }
        assertThat(md).contains("| ERROR | CRITICAL | task:Recompute users/recurrence | task-legacy.xml:8 | "
                + "CRITICAL recurrence: " + removed() + " |");
    }

    /**
     * {@code -Dstatic.dir=<dir>}: a configuration of your own; fails on compile errors. {@code -Dstatic.classpath}:
     * jars, class directories or directories of jars its scripts need (separated by the path separator or commas).
     */
    @Test
    void configurationOfStaticDir() {
        String dir = System.getProperty("static.dir");
        assumeTrue(dir != null && !dir.isBlank(), "-Dstatic.dir not set");
        String schemaDir = System.getProperty("static.schemaDir");
        StaticCheck check = StaticCheck.of(Path.of(dir));
        if (schemaDir != null && !schemaDir.isBlank()) {
            check.schemaDir(Path.of(schemaDir));
        }
        String classpath = System.getProperty("static.classpath");
        if (classpath != null && !classpath.isBlank()) {
            for (String entry : classpath.split("[," + java.io.File.pathSeparator + "]")) {
                if (!entry.isBlank()) {
                    check.classpath(Path.of(entry.trim()));
                }
            }
        }
        StaticReport own = check.run();
        own.write(Path.of("target/static-check", Path.of(dir).getFileName() + ".md"));

        own.assertNoErrors(Check.COMPILE);
    }

    /** 4.4's upgrade validator calls the items it reports deprecated; 4.8+ removed. */
    private static String removed() {
        return MidPointLine.isBefore(LINE, "4.8") ? "deprecated" : "removed";
    }

    private static List<String> where(List<Finding> findings) {
        return findings.stream().map(Finding::where).collect(Collectors.toList());
    }
}
