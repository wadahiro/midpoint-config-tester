package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.github.wadahiro.midpoint.configtester.provided.CsvFileSystem;
import com.github.wadahiro.midpoint.configtester.provided.InMemoryLdapSystem;

/**
 * Resources on their real connectors reaching systems the test provides ({@code scenarios/hr-provided},
 * {@code connector: real}): HR's CSV file and an LDAP server the test's {@code RealSystem}s start, which the tester
 * saves and restores with midPoint's state, so each test starts from the systems as loaded and a nested class's tests
 * from the systems as its prefix left them.
 */
@MidPointConfigTest("classpath:scenarios/hr-provided/midpoint-config-tester.yaml")
class ProvidedSystemsScenarioTest {

    static final String JOHN = HrLifecycleScenarioTest.JOHN;

    static void hire(MidPointConfigTester t) {
        CsvFileSystem.of("HR").append("1001;John;Doe;Sales;;2020-04-01;");
        t.task("Reconcile HR").run();
    }

    @Test
    void aJoinerGetsAnEntryOnTheProvidedServer(MidPointConfigTester t) {
        hire(t);

        t.user("e1001").hasAssignment("Dept Sales").hasAccountOn("LDAP");
        assertThat(InMemoryLdapSystem.of("LDAP").entry(JOHN).getAttributeValue("ou")).isEqualTo("Sales");
    }

    @Test
    void eachTestStartsFromTheSystemsAsLoaded(MidPointConfigTester t) {
        assertThat(CsvFileSystem.of("HR").lines()).hasSize(1);   // the header
        assertThat(InMemoryLdapSystem.of("LDAP").entry(JOHN)).isNull();
        assertThat(InMemoryLdapSystem.of("LDAP").entry("cn=sales,ou=groups,dc=example,dc=com")).isNotNull();
    }

    @Test
    void aSystemWithoutAViewGivesTheTestNone(MidPointConfigTester t) {
        assertThatThrownBy(() -> t.target("LDAP").account(JOHN).exists())
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("RealSystem.view()");
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class JohnHasJoined {

        @BeforeAll
        void hireJohn(MidPointConfigTester t) {
            hire(t);
        }

        @Test
        void aTestDeletesHisEntry(MidPointConfigTester t) {
            InMemoryLdapSystem.of("LDAP").delete(JOHN);
            assertThat(InMemoryLdapSystem.of("LDAP").entry(JOHN)).isNull();
        }

        @Test
        void theNextFindsItAgain(MidPointConfigTester t) {
            assertThat(InMemoryLdapSystem.of("LDAP").entry(JOHN)).isNotNull();   // the checkpoint's server
            assertThat(CsvFileSystem.of("HR").lines()).hasSize(2);
        }
    }
}
