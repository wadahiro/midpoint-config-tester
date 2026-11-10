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

    /** Hires John and reads what midPoint made of it. */
    private static List<String> joinerOutcome(Deployment deployment) {
        deployment.reset();
        MidPointConfigTester t = new MidPointConfigTester(deployment);
        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
                .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales")
                .set(HrAttrs.START_DATE, LocalDate.of(2020, 4, 1).toString()));
        t.task(Tasks.RECONCILE_HR).run();
        t.user("e1001").hasRole(Roles.DEPT_SALES);
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
