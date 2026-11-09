package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.internal.Deployment;
import com.github.wadahiro.midpoint.tester.internal.TesterRuntime;

/**
 * Loading a deployment: midPoint's post-initial import of a copy of the user's directory, in midPoint's order, stopped
 * at the first file that fails; the user's files are never changed or renamed.
 */
class DeploymentLoadTest {

    @Test
    void theSampleIsImportedInThePostInitialOrderFromAPreparedCopy() {
        Path manifest = resource("scenarios/hr/midpoint-config-tester.yaml");
        Path dir = manifest.getParent().resolve("post-initial-objects");
        Map<String, String> before = contents(dir);

        Deployment deployment = Deployment.load(manifest);

        // sorted by the numbers of the file names across the subdirectories, as midPoint's DataImport.sortFiles
        assertThat(deployment.importOrder()).containsExactly("000-system-configuration.xml",
                "010-object-template-user.xml", "resources/020-resource-hr.xml", "resources/021-resource-ldap.xml",
                "roles/030-roles.xml", "tasks/090-task-reconcile-hr.xml");
        assertThat(contents(dir)).isEqualTo(before);
        // the copy has the properties, the line's overlay and the resources on the tester's target systems
        String template = read(deployment.prepared().resolve("010-object-template-user.xml"));
        assertThat(template).contains("\"${name}@example.com\"").doesNotContain("{{");
        String ldap = read(deployment.prepared().resolve("resources/021-resource-ldap.xml"));
        assertThat(ldap).doesNotContain("ldap.example.com").contains("127.0.0.1");
        String task = read(deployment.prepared().resolve("tasks/090-task-reconcile-hr.xml"));
        assertThat(task).contains("4.0".equals(deployment.api().line()) ? "<handlerUri>" : "<activity>");
    }

    /**
     * The typed names generated from a configuration ({@code names.package}) are checked against the loaded
     * deployment: names an older configuration had fail the load, with every one of them.
     */
    @Test
    void generatedNamesThatTheDeploymentDoesNotHaveFailTheLoad() {
        assertThatThrownBy(() -> Deployment.load(resource("stale-names/midpoint-config-tester.yaml")))
                .isInstanceOf(DeploymentLoadException.class)
                .hasMessageContaining("2 generated name(s) of com.github.wadahiro.midpoint.configtester.sample.stale")
                .hasMessageContaining("role Internal Auditor: no such object")
                .hasMessageContaining("user jack: no such object with OID 30000000-0000-0000-0000-00000000e999")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Ghost").doesNotContain("role Auditor:"));
    }

    /**
     * Before the post-initial import, midPoint's initial import of the line's initial objects (from the GUI module's
     * jar on 4.0 and 4.4, from system-init on 4.8+), as at midPoint's first start; the line's versions replace the
     * tester's startup objects of the same OID (the system configuration, Superuser, administrator...).
     */
    @Test
    void theLinesInitialObjectsAreImportedFirst() {
        Deployment deployment = Deployment.load(resource("scenarios/hr/midpoint-config-tester.yaml"));

        if ("4.10".equals(deployment.api().line())) {
            // midPoint 4.10's own order (by file name, as 4.8) imports its 250-mark-* files before the archetype of
            // marks (701-...): the reference fails (PARTIAL_ERROR), as at a real first start of 4.10 (not targeted)
            assertThat(deployment.initialObjectsFailed()).isNotEmpty().allSatisfy(failed -> assertThat(failed)
                    .startsWith("250-mark-").contains("00000000-0000-0000-0000-000000000701"));
        } else {
            assertThat(deployment.initialObjectsFailed()).isEmpty();
        }
        assertThat(deployment.initialObjectsImported()).contains("000-system-configuration.xml",
                "050-user-administrator.xml", "040-role-enduser.xml", "010-value-policy.xml",
                "015-security-policy.xml");
        if ("4.0".equals(deployment.api().line())) {
            // 4.0's reports are Jasper reports: no Jasper libraries in the tester
            assertThat(deployment.initialObjectsSkipped()).hasSize(7)
                    .allMatch(skipped -> skipped.contains("-report-") && skipped.contains("Jasper"));
            // 39 files of 4.0.3's admin-gui (another 4.0 patch version may ship another set)
            assertThat(deployment.initialObjectsImported()).hasSize(32);
        } else {
            assertThat(deployment.initialObjectsSkipped()).isEmpty();
            assertThat(deployment.initialObjectsImported()).contains("550-task-cleanup.xml",
                    "310-dashboard-admin.xml");
        }
        String report = read(deployment.prepared().getParent().resolve("load-report.txt"));
        assertThat(report).contains("Initial objects of midPoint " + deployment.api().line());
    }

    /**
     * A test class of the PoC API resets midPoint to the startup baseline, which shuts the deployment's LDAP server
     * down; the next scenario's reset brings the deployment back, its target systems included.
     */
    @Test
    void theDeploymentComesBackAfterAResetToTheStartupBaseline() {
        Deployment deployment = Deployment.load(resource("scenarios/hr/midpoint-config-tester.yaml"));
        deployment.reset();
        TesterRuntime.get().resetToBaseline();
        deployment.reset();

        MidPointConfigTester t = new MidPointConfigTester(deployment);
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        t.task("Reconcile HR").run();
        t.target("LDAP").account("uid=e1001,ou=people,dc=example,dc=com").exists()
                .memberOf("cn=sales,ou=groups,dc=example,dc=com");
        deployment.reset();
    }

    @Test
    void aFileThatFailsStopsTheLoadWithTheFileAndMidPointsErrors() {
        Path manifest = resource("scenarios/broken/midpoint-config-tester.yaml");
        Path dir = manifest.getParent().resolve("post-initial-objects");
        Map<String, String> before = contents(dir);

        assertThatThrownBy(() -> Deployment.load(manifest))
                .isInstanceOf(DeploymentLoadException.class)
                .hasMessageContaining("the post-initial import failed on 020-role-broken.xml")
                .hasMessageContaining("after 1 file(s)")
                .hasMessageContaining("costCentre")
                .hasMessageContaining("load-failure.txt");
        // a second load of the manifest fails the same, without a second attempt
        assertThatThrownBy(() -> Deployment.load(manifest))
                .isInstanceOf(DeploymentLoadException.class)
                .hasMessageContaining("020-role-broken.xml");
        // nothing renamed (no .done) or changed
        assertThat(contents(dir)).isEqualTo(before);
    }

    /** The resources the tester cannot stand in for and the manifest's mistakes are reported at once, not one a run. */
    @Test
    void everyResourceThatCannotBeStoodInForIsReportedAtOnce() {
        assertThatThrownBy(() -> Deployment.load(resource("scenarios/unmovable/midpoint-config-tester.yaml")))
                .isInstanceOf(DeploymentLoadException.class)
                .hasMessageContaining("3 problems with the deployment")
                .hasMessageContaining("021-resource-crm1.xml: resource CRM 1 cannot be moved")
                .hasMessageContaining("022-resource-crm2.xml: resource CRM 2 cannot be moved")
                .hasMessageContaining("targets.Nowhere: no resource of that name");
    }

    static Path resource(String name) {
        try {
            return Path.of(DeploymentLoadTest.class.getClassLoader().getResource(name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Relative path to content of every file under the directory. */
    static Map<String, String> contents(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).collect(Collectors.toMap(
                    f -> dir.relativize(f).toString(), DeploymentLoadTest::read, (a, b) -> a, TreeMap::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
