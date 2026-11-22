package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.wadahiro.midpoint.configtester.names.midpoint.UserItems;
import com.github.wadahiro.midpoint.configtester.sample.hr.HrAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.LdapAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Roles;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;
import com.github.wadahiro.midpoint.tester.internal.Deployment;

/**
 * The deployment cache: a deployment restored from the cache behaves as the fresh load it was written from; a changed
 * file of the configuration is a new entry; a damaged entry is loaded afresh. Each test works on its own copy of the
 * sample deployment {@code scenarios/hr}, with a cache directory of its own.
 */
class DeploymentCacheTest {

    private static String cacheDir;
    private static String cacheSwitch;

    @TempDir
    Path tmp;

    @BeforeAll
    static void keep() {
        cacheDir = System.getProperty("tester.cache.dir");
        cacheSwitch = System.getProperty("tester.cache");
    }

    @AfterAll
    static void restore() {
        set("tester.cache.dir", cacheDir);
        set("tester.cache", cacheSwitch);
    }

    @Test
    void aRestoredDeploymentGivesTheResultsOfTheFreshLoad() {
        Path manifest = copyOfTheSample();
        Deployment fresh = Deployment.reload(manifest);
        assertThat(fresh.restoredFromCache()).isNull();
        assertThat(fresh.cacheEntry()).isNotNull();
        List<String> freshOutcome = joinerOutcome(fresh);

        Deployment restored = Deployment.reload(manifest);
        assertThat(restored.restoredFromCache()).isEqualTo(fresh.cacheEntry());
        assertThat(restored.importOrder()).isEqualTo(fresh.importOrder());
        assertThat(restored.initialObjectsImported()).isEqualTo(fresh.initialObjectsImported());
        assertThat(read(restored.prepared().getParent().resolve("load-report.txt")))
                .contains("Restored from the deployment cache");
        assertThat(joinerOutcome(restored)).isEqualTo(freshOutcome);
        // and every test starts from it again
        restored.reset();
        MidPointConfigTester t = new MidPointConfigTester(restored);
        t.user("e1001").doesNotExist();
        assertThat(t.target(Resources.LDAP).accounts()).isEmpty();
    }

    @Test
    void aChangedFileOfTheConfigurationIsLoadedAfresh() throws IOException {
        Path manifest = copyOfTheSample();
        Deployment first = Deployment.reload(manifest);
        Path roles = manifest.getParent().resolve("post-initial-objects/roles/030-roles.xml");
        Files.writeString(roles, read(roles).replace("<!-- Department roles", "<!-- Changed. Department roles"),
                StandardCharsets.UTF_8);

        Deployment changed = Deployment.reload(manifest);
        assertThat(changed.restoredFromCache()).isNull();
        assertThat(changed.cacheEntry()).isNotNull().isNotEqualTo(first.cacheEntry());
        // the old entry is gone, the new one is used next
        assertThat(first.cacheEntry()).doesNotExist();
        assertThat(Deployment.reload(manifest).restoredFromCache()).isEqualTo(changed.cacheEntry());
    }

    @Test
    void aDamagedEntryIsLoadedAfresh() throws IOException {
        Path manifest = copyOfTheSample();
        Path entry = Deployment.reload(manifest).cacheEntry();
        Path table;
        try (Stream<Path> files = Files.list(entry.resolve("db"))) {
            table = files.filter(f -> f.toString().endsWith(".bin")).filter(f -> f.toFile().length() > 100)
                    .findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(table);
        bytes[bytes.length / 2] ^= 0x55;
        Files.write(table, bytes);

        Deployment again = Deployment.reload(manifest);
        assertThat(again.restoredFromCache()).isNull();
        assertThat(joinerOutcome(again)).contains("roles [Dept Sales]");
        // written anew
        assertThat(Deployment.reload(manifest).restoredFromCache()).isEqualTo(again.cacheEntry());
    }

    @Test
    void withTheCacheOffEveryLoadIsFresh() {
        Path manifest = copyOfTheSample();
        System.setProperty("tester.cache", "false");
        try {
            Deployment first = Deployment.reload(manifest);
            assertThat(first.cacheEntry()).isNull();
            assertThat(Deployment.reload(manifest).restoredFromCache()).isNull();
        } finally {
            set("tester.cache", cacheSwitch);
        }
    }

    @Test
    void theDeploymentsOwnDdlIsInTheFreshLoadTheResetsAndTheRestore() throws Exception {
        Path manifest = copyOfTheSample();
        Path ddl = manifest.getParent().resolve("repository/extra.sql");
        Files.createDirectories(ddl.getParent());
        Files.writeString(ddl, "CREATE TABLE tester_extra (code VARCHAR(10) NOT NULL);\n"
                + "CREATE UNIQUE INDEX tester_extra_code ON tester_extra (code);\n"
                + "INSERT INTO tester_extra VALUES ('seed');\n", StandardCharsets.UTF_8);
        Files.writeString(manifest, read(manifest) + "repository:\n  ddl: [repository/extra.sql]\n",
                StandardCharsets.UTF_8);

        Deployment fresh = Deployment.reload(manifest);
        assertThat(fresh.restoredFromCache()).isNull();
        fresh.reset();
        assertThat(extraCodes(fresh)).containsExactly("seed");
        // the unique index holds, as in production
        execute(fresh, "INSERT INTO tester_extra VALUES ('added')");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> execute(fresh, "INSERT INTO tester_extra VALUES ('added')")))
                .isInstanceOf(java.sql.SQLException.class);
        // a reset drops what a test wrote into the deployment's own table
        fresh.reset();
        assertThat(extraCodes(fresh)).containsExactly("seed");

        Deployment restored = Deployment.reload(manifest);
        assertThat(restored.restoredFromCache()).isEqualTo(fresh.cacheEntry());
        restored.reset();
        assertThat(extraCodes(restored)).containsExactly("seed");

        // the DDL is part of the cache key
        Files.writeString(ddl, read(ddl).replace("'seed'", "'seed2'"), StandardCharsets.UTF_8);
        Deployment changed = Deployment.reload(manifest);
        assertThat(changed.restoredFromCache()).isNull();
        changed.reset();
        assertThat(extraCodes(changed)).containsExactly("seed2");
    }

    /**
     * A warning of the preparation (a mirrored resource whose simulated status attribute the stored schema does not
     * declare) is in the restored deployment's warnings and load report once, as in the fresh load's.
     */
    @Test
    void aRestoredDeploymentHasTheWarningsOfTheFreshLoadOnce() throws IOException {
        Path manifest = copyOfTheSample();
        String xml = read(resource("fixtures/provisioning/resource-ldap.xml"))
                .replace("10000000-0000-0000-0000-00000000a002", "20000000-0000-0000-0000-00000000a0f1")
                .replace("<name>LDAP</name>", "<name>Directory</name>");
        int close = xml.lastIndexOf("    <synchronization>");
        xml = xml.substring(0, close) + "    <capabilities><configured>"
                + "<cap:activation xmlns:cap='http://midpoint.evolveum.com/xml/ns/public/resource/capabilities-3'>"
                + "<cap:status><cap:attribute>ri:employeeType</cap:attribute><cap:enableValue>active</cap:enableValue>"
                + "<cap:disableValue>disabled</cap:disableValue></cap:status></cap:activation>"
                + "</configured></capabilities>\n" + xml.substring(close);
        Files.writeString(manifest.getParent().resolve("post-initial-objects/resources/029-resource-directory.xml"), xml,
                StandardCharsets.UTF_8);
        String warning = "resource Directory: the simulated activation status attribute ri:employeeType";

        Deployment fresh = Deployment.reload(manifest);
        assertThat(fresh.restoredFromCache()).isNull();
        assertThat(fresh.warnings()).filteredOn(w -> w.contains(warning)).hasSize(1);

        Deployment restored = Deployment.reload(manifest);
        assertThat(restored.restoredFromCache()).isEqualTo(fresh.cacheEntry());
        assertThat(restored.warnings()).isEqualTo(fresh.warnings());
        String report = read(restored.prepared().getParent().resolve("load-report.txt"));
        assertThat(report.indexOf(warning)).isNotNegative().isEqualTo(report.lastIndexOf(warning));
    }

    private static List<String> extraCodes(Deployment deployment) throws java.sql.SQLException {
        List<String> out = new ArrayList<>();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(deployment.repositoryJdbcUrl(),
                deployment.repositoryUser(), "");
                java.sql.ResultSet rs = c.createStatement().executeQuery("SELECT code FROM tester_extra ORDER BY 1")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void execute(Deployment deployment, String sql) throws java.sql.SQLException {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(deployment.repositoryJdbcUrl(),
                deployment.repositoryUser(), "")) {
            c.createStatement().execute(sql);
        }
    }

    /** Hires John and reads what midPoint made of it. */
    private static List<String> joinerOutcome(Deployment deployment) {
        deployment.reset();
        MidPointConfigTester t = new MidPointConfigTester(deployment);
        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
                .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales")
                .set(HrAttrs.START_DATE, LocalDate.of(2020, 4, 1).toString()));
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").hasAssignment(Roles.DEPT_SALES);
        List<String> out = new ArrayList<>();
        out.add("fullName " + t.user("e1001").values(UserItems.FULL_NAME));
        out.add("roles " + t.user("e1001").asObject().getAssignment().stream()
                .map(a -> t.nameOf(a.getTargetRef()))
                .filter(r -> r.startsWith("Dept"))
                .collect(Collectors.toList()));
        Account account = t.target(Resources.LDAP).account("uid=e1001,ou=people,dc=example,dc=com");
        for (com.github.wadahiro.midpoint.configtester.names.AttributeRef attribute
                : List.of(LdapAttrs.CN, LdapAttrs.OU, LdapAttrs.MAIL, LdapAttrs.EMPLOYEE_NUMBER)) {
            out.add(attribute.name() + " " + account.values(attribute));
        }
        out.add("mails " + t.mails().to("e1001@example.com").count());
        out.add("ldap " + t.target(Resources.LDAP).accounts());
        return out;
    }

    /** The sample (and the extension schema its manifest names) copied, with a cache directory of the test's own. */
    private Path copyOfTheSample() {
        Path classes = resource("scenarios/hr/midpoint-config-tester.yaml").getParent().getParent().getParent();
        copy(classes.resolve("scenarios/hr"), tmp.resolve("scenarios/hr"));
        copy(classes.resolve("schema"), tmp.resolve("schema"));
        System.setProperty("tester.cache.dir", tmp.resolve("cache").toString());
        return tmp.resolve("scenarios/hr/midpoint-config-tester.yaml");
    }

    private static void copy(Path from, Path to) {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.collect(Collectors.toList())) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path resource(String name) {
        try {
            return Path.of(DeploymentCacheTest.class.getClassLoader().getResource(name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void set(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
