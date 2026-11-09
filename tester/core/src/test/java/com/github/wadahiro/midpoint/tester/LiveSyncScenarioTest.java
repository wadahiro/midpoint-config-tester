package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.SynchronizationSituationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.tester.internal.Recorder;

/**
 * The HR to LDAP scenario (as {@code configtester.HrLifecycleScenarioTest}) driven by live synchronization instead of reconciliation
 * ({@code fixtures/livesync}): the changes of the HR export (CSV) since the last run become users, changed users and
 * deleted users; the directory is also a source (the phone number), synchronized from its own changes.
 *
 * As in production, the first run of a live synchronization task only fetches the token: what the source holds then is
 * not a change, the changes after it are. Each source keeps its changes its own way: the CSV connector compares the
 * file with its copy of the last run ({@code <file>.sync.<token>} in its {@code tmpFolder}), the LDAP connector reads
 * the server's change log ({@code cn=changelog}, by change number), the mirror compares its content with the content
 * of the last run. A source also shows midPoint's own changes: the directory's change log has the accounts midPoint
 * created.
 *
 * Run on the real CSV and LDAP connectors ({@link LiveSyncRealConnectorsTest}) and on the mirrors
 * ({@link LiveSyncMirrorTest}).
 */
abstract class LiveSyncScenarioTest {

    static final File SCENARIO = Fixtures.dir("scenario");
    static final File DIR = Fixtures.dir("livesync");
    static final String PEOPLE = "ou=people,dc=example,dc=com";
    static final String JOHN = "uid=e1001," + PEOPLE;
    static final String KATE = "uid=e1002," + PEOPLE;
    static final String SALES = "cn=sales,ou=groups,dc=example,dc=com";
    static final String IT = "cn=it,ou=groups,dc=example,dc=com";
    static final String TASK = "Live sync HR";
    static final String TASK_OID = "10000000-0000-0000-0000-00000000f511";

    /** The scenario's configuration with the directory of {@code fixtures/livesync}; no task. */
    static void configure(MidPoint mp, ResourceOptions hrOptions, ResourceOptions ldapOptions) {
        mp.importObject(new File(SCENARIO, "object-template-user.xml"));
        mp.importObject(new File(SCENARIO, "system-configuration.xml"));
        mp.importResource(Fixtures.of(mp, new File(SCENARIO, "resource-hr.xml")), hrOptions);
        mp.importResource(new File(DIR, "resource-ldap.xml"), ldapOptions);
        mp.importObject(new File(SCENARIO, "roles.xml"));
    }

    @Test
    void hrDrivesTheDirectoryThroughLiveSync(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem hr = mp.target("HR");
        TargetSystem ldap = mp.target("LDAP");

        // ---- deploy: the task's first run only fetches the token; a row already there is no change. The deployment
        // holds recurring tasks: the import does not run it, the test does
        hr.addAccount("1000", Map.of("firstName", "Ann", "lastName", "Early", "department", "Sales"));
        mp.step("deploy");
        int runs = Recorder.taskRuns(TASK_OID);
        mp.importObject(Fixtures.of(mp, new File(DIR, "task-livesync-hr.xml")));
        assertThat(Recorder.taskRuns(TASK_OID) - runs).isZero();
        Run deployed = mp.runTask(TASK);

        assertThat(deployed.evaluations()).isEmpty();
        assertThat(mp.user("e1000")).isNull();
        assertThat(Recorder.taskRuns(TASK_OID) - runs).isEqualTo(1);

        // ---- hire: two new rows are two changes -> unmatched -> users added, accounts and memberships provisioned
        hr.addAccount("1001", Map.of("firstName", "John", "lastName", " DOE ", "department", "Sales"));
        hr.addAccount("1002", Map.of("firstName", "Kate", "lastName", "Smith", "department", "IT"));
        mp.step("hire");
        Run hire = runOnce(mp);

        UserType john = mp.user("e1001");
        assertThat(john.getFullName().getOrig()).isEqualTo("John Doe");
        assertThat(john.getCostCenter()).isEqualTo("Sales");
        assertThat(mp.situation("e1001", "HR")).isEqualTo(SynchronizationSituationType.LINKED);
        assertThat(mp.situation("e1001", "LDAP")).isEqualTo(SynchronizationSituationType.LINKED);
        assertThat(mp.user("e1000")).isNull();                         // not changed since the token: not seen
        assertThat(ldap.accountNames()).containsExactlyInAnyOrder(JOHN, KATE);
        assertThat(ldap.attribute(JOHN, "cn")).isEqualTo("John Doe");
        assertThat(ldap.groupValues(SALES, "member")).contains(JOHN).doesNotContain(KATE);
        assertThat(ldap.groupValues(IT, "member")).contains(KATE).doesNotContain(JOHN);
        assertThat(hire.forFocus("e1001").mapping("hr-familyName").newValues()).containsExactly("Doe");
        assertThat(hire.forFocus("e1002").mapping("hr-familyName").newValues()).containsExactly("Smith");
        assertThat(hire.mappings("hr-familyName").list()).allSatisfy(e -> assertThat(e.task()).isEqualTo(TASK));
        assertThat(hire.forFocus("e1001").outbound("LDAP", "ri:cn").newValues()).containsExactly("John Doe");
        assertThat(hire.select().path("correlation").element("expression").list()).isNotEmpty();

        // ---- change: one changed row is one change; the unchanged rows are not processed
        hr.modifyAccount("1001", "lastName", "Brown");
        hr.modifyAccount("1001", "department", "IT");
        mp.step("change");
        Run change = runOnce(mp);

        john = mp.user("e1001");
        assertThat(john.getFullName().getOrig()).isEqualTo("John Brown");
        assertThat(john.getCostCenter()).isEqualTo("IT");
        assertThat(ldap.attribute(JOHN, "cn")).isEqualTo("John Brown");
        assertThat(ldap.groupValues(SALES, "member")).doesNotContain(JOHN);
        assertThat(ldap.groupValues(IT, "member")).contains(JOHN, KATE);
        assertThat(change.forFocus("e1001").mapping("hr-familyName").newValues()).containsExactly("Brown");
        assertThat(change.forFocus("e1001").mapping("ldap-cn").oldValues()).containsExactly("John Doe");
        assertThat(focusNames(change)).containsExactly("e1001");
        assertThat(mp.situation("e1001", "HR")).isEqualTo(SynchronizationSituationType.LINKED);

        // ---- leave: a deleted row is a deletion -> deleted -> the user and the account go
        hr.deleteAccount("1002");
        mp.step("leave");
        Run leave = runOnce(mp);

        assertThat(mp.user("e1002")).isNull();
        assertThat(ldap.accountNames()).containsExactly(JOHN);
        assertThat(leave.forFocus("e1002").evaluations()).isNotEmpty();
        assertThat(focusNames(leave)).containsExactly("e1002");
        // as in the reconciliation: 4.0 evaluates the attribute outbounds of her account being
        // deleted, 4.4+ her role's construction with a false condition
        Run kateLeft = leave.forFocus("e1002");
        assertThat(kateLeft.mappings("ldap-cn").stream().anyMatch(e -> e.oldValues().contains("Kate Smith")))
                .isEqualTo(!Lines.atLeast(mp, "4.4"));
        assertThat(kateLeft.mappings("it-group").stream().anyMatch(e -> Boolean.FALSE.equals(e.conditionNew())))
                .isEqualTo(Lines.atLeast(mp, "4.4"));

        // ---- nothing changed: a run without changes evaluates nothing
        mp.step("idle");
        assertThat(runOnce(mp).evaluations()).isEmpty();
    }

    /** Without a deployed task: the driver creates a live synchronization task at its first call, then runs it. */
    @Test
    void liveSyncDriverKeepsItsToken(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem hr = mp.target("HR");
        hr.addAccount("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));

        Run first = mp.liveSync("HR");                                 // fetches the token only

        assertThat(first.operation()).isEqualTo("live sync HR");
        assertThat(first.evaluations()).isEmpty();
        assertThat(mp.user("e1001")).isNull();

        hr.addAccount("1002", Map.of("firstName", "Kate", "lastName", "Smith", "department", "IT"));
        Run second = mp.liveSync("HR");                                // the same task, from its token

        assertThat(second.operation()).isEqualTo("live sync HR");
        assertThat(mp.user("e1002").getFullName().getOrig()).isEqualTo("Kate Smith");
        assertThat(mp.user("e1001")).isNull();
        assertThat(second.mapping("hr-familyName").task()).isEqualTo("live sync HR (tester)");
        assertThat(focusNames(second)).containsExactly("e1002");

        // a row there before the first run is seen when it changes: unmatched as for an import
        hr.modifyAccount("1001", "department", "IT");
        Run third = mp.liveSync("HR");

        assertThat(mp.user("e1001").getCostCenter()).isEqualTo("IT");
        assertThat(focusNames(third)).containsExactly("e1001");
        assertThat(mp.situation("e1001", "HR")).isEqualTo(SynchronizationSituationType.LINKED);
    }

    /**
     * The directory as a source: a phone number changed there comes back to the user (inbound of the linked account);
     * an account created there for no user is unmatched and deleted. The run also sees the accounts midPoint created
     * after the token (linked: synchronized, nothing to change).
     */
    @Test
    void directoryChangesComeBackThroughLiveSync(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem hr = mp.target("HR");
        TargetSystem ldap = mp.target("LDAP");
        hr.addAccount("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
        mp.importAccounts("HR");
        assertThat(ldap.accountNames()).containsExactly(JOHN);

        Run first = mp.liveSync("LDAP");                               // fetches the token only
        assertThat(first.evaluations()).isEmpty();

        ldap.modifyAccount(JOHN, "telephoneNumber", "+1 555 0100");
        String intruder = "uid=intruder," + PEOPLE;
        ldap.addAccount(intruder, Map.of("cn", "Intruder", "sn", "Intruder", "uid", "intruder"));
        mp.step("directory changes");
        Run synced = mp.liveSync("LDAP");

        assertThat(mp.user("e1001").getTelephoneNumber()).isEqualTo("+1 555 0100");
        Evaluation phone = synced.forFocus("e1001").inbound("LDAP", "ri:telephoneNumber");
        assertThat(phone.newValues()).containsExactly("+1 555 0100");
        assertThat(phone.task()).isEqualTo("live sync LDAP (tester)");
        assertThat(mp.situation("e1001", "LDAP")).isEqualTo(SynchronizationSituationType.LINKED);
        // unmatched: correlated by uid to no user, deleted from the directory
        assertThat(ldap.accountNames()).containsExactly(JOHN);
        assertThat(synced.select().path("correlation").element("expression").list()).isNotEmpty();
    }

    /** The deployed task run now: exactly one run of it in the call. */
    static Run runOnce(MidPoint mp) {
        int before = Recorder.taskRuns(TASK_OID);
        Run run = mp.runTask(TASK);
        assertThat(Recorder.taskRuns(TASK_OID) - before).as("runs of " + TASK).isEqualTo(1);
        return run;
    }

    static java.util.List<String> focusNames(Run run) {
        return run.focuses().stream().map(Evaluation.Owner::name).collect(java.util.stream.Collectors.toList());
    }
}
