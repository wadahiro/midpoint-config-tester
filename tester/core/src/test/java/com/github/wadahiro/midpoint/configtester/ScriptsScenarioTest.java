package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A resource's provisioning scripts ({@code scenarios/scripts}): the LDAP resource has an additional SSH connector
 * that makes a home directory after an account is added and archives it before one is deleted. On the mirror the
 * additional connector goes; the mirror accepts the scripts, does not run them, and the effects have what midPoint
 * asked for: the language, the code and the arguments as midPoint evaluated them. A test can make them fail.
 */
@MidPointConfigTest("classpath:scenarios/scripts/midpoint-config-tester.yaml")
class ScriptsScenarioTest {

    static final String JOHN = HrLifecycleScenarioTest.JOHN;

    /** midPoint 4.0 gives the scripts to the account's operation, not as calls of their own. */
    static void notOn40(MidPointConfigTester t) {
        assumeFalse("4.0".equals(t.midpoint().line()), "midPoint 4.0 runs provisioning scripts within the operation");
    }

    @BeforeEach
    void groups(MidPointConfigTester t) {
        notOn40(t);
        // no initial data on a mirror: the groups the roles refer to are added by the test
        t.target("LDAP").system().addGroup(HrLifecycleScenarioTest.SALES, Map.of("cn", "sales"));
        t.target("LDAP").system().addGroup(HrLifecycleScenarioTest.IT, Map.of("cn", "it"));
    }

    static Effects hire(MidPointConfigTester t) {
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", LocalDate.of(2020, 4, 1).toString()));
        return t.task("Reconcile HR").run();
    }

    @Test
    void aJoinersHomeDirectoryIsMadeAfterHisAccount(MidPointConfigTester t) {
        Effects hired = hire(t);

        ConnectorCall script = hired.connectorCalls().to("LDAP").scripts().single()
                .hasLanguage("bash").hasArgument("user", "e1001").hasArgument("department", "Sales")
                .codeContains("mkdir -p /home/$department/$user").only("user", "department");
        assertThat(script.host()).isEqualTo("resource");
        assertThat(script.failed()).isFalse();   // a test's scriptsFail() does not outlive it
        List<ConnectorCall> writes = hired.connectorCalls().to("LDAP").writes().list();
        assertThat(writes.indexOf(hired.connectorCalls().to("LDAP").adds().of(JOHN).single()))
                .isLessThan(writes.indexOf(script));   // after the add
        assertThat(hired.connectorCalls().to("LDAP").scripts().withArgument("user", "e1001").count()).isOne();
        assertThat(script.toString()).contains("bash", "mkdir", "e1001");
    }

    @Test
    void aLeaversHomeIsArchivedBeforeHisAccountGoes(MidPointConfigTester t) {
        hire(t);
        t.source("HR").delete("1001");

        Effects left = t.task("Reconcile HR").run();

        ConnectorCall script = left.connectorCalls().to("LDAP").scripts().single()
                .hasArgument("user", "e1001").codeContains("tar czf /archive/$user.tgz");
        List<ConnectorCall> writes = left.connectorCalls().to("LDAP").writes().list();
        assertThat(writes.indexOf(script))
                .isLessThan(writes.indexOf(left.connectorCalls().to("LDAP").deletes().of(JOHN).single()));
    }

    @Test
    void aScriptThatFailsFailsTheOperationAsItsCriticalitySays(MidPointConfigTester t) {
        t.target("LDAP").scriptsFail();

        Effects hired = t.task("Reconcile HR").allowPartialErrors().run();   // nothing to do yet
        hired.connectorCalls().to("LDAP").scripts().none();
        t.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", LocalDate.of(2020, 4, 1).toString()));
        Effects failed = t.task("Reconcile HR").allowPartialErrors().run();

        // fatal, the default criticality: the account is added, the script after it fails, so does the operation
        assertThat(failed.connectorCalls().to("LDAP").scripts().single().failed()).isTrue();
        failed.connectorCalls().to("LDAP").adds().of(JOHN).single();
    }
}
