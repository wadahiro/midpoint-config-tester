package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.SynchronizationSituationType;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * {@link LiveSyncScenarioTest} on the real connectors the line ships: the CSV connector compares the file with its
 * copy of the last run, the LDAP connector reads the change log of the in-memory LDAP server ({@code synchronizationStrategy}
 * {@code auto} finds it in the root DSE).
 */
@MidPointConfigTest(Fixtures.BARE)
class LiveSyncRealConnectorsTest extends LiveSyncScenarioTest {

    @BeforeEach
    void configure(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        configure(mp, ResourceOptions.defaults().realConnector(), ResourceOptions.defaults().realConnector());
        mp.ldap("LDAP").loadLdif(String.join("\n",
                "dn: ou=people,dc=example,dc=com", "objectClass: organizationalUnit", "ou: people", "",
                "dn: ou=groups,dc=example,dc=com", "objectClass: organizationalUnit", "ou: groups", "",
                "dn: " + SALES, "objectClass: groupOfNames", "cn: sales", "",
                "dn: " + IT, "objectClass: groupOfNames", "cn: it", ""));
    }

    /**
     * The LDAP connector identifies a deleted entry by the change log entry's {@code targetUniqueID} (with
     * {@code uidAttribute} {@code entryUUID}), which the in-memory server does not write: a deletion in the directory
     * is not seen by live synchronization (the mirror sees it): the shadow stays live and linked.
     */
    @Test
    void directoryDeletionIsNotInTheChangeLogForTheConnector(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.target("HR").addAccount("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
        mp.importAccounts("HR");
        mp.liveSync("LDAP");                                           // the token

        mp.target("LDAP").deleteAccount(JOHN);
        Run synced = mp.liveSync("LDAP");

        assertThat(synced.evaluations()).isEmpty();
        assertThat(mp.shadow("e1001", "LDAP").dead()).isFalse();
        assertThat(mp.situation("e1001", "LDAP")).isEqualTo(SynchronizationSituationType.LINKED);
    }

    /**
     * The in-memory server writes a rename as {@code changeType: moddn}; the LDAP connector knows {@code modrdn} (the
     * change log draft's name) only and fails the run, which midPoint then suspends. A deployment whose directory renames
     * entries needs a server with that change log (or reconciliation) for them.
     */
    @Test
    void directoryRenameFailsTheLiveSync(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.target("HR").addAccount("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
        mp.importAccounts("HR");
        mp.liveSync("LDAP");                                           // the token

        mp.target("LDAP").renameAccount(JOHN, "uid=john," + PEOPLE);
        Run run = mp.attempt(() -> mp.liveSync("LDAP"));

        assertThat(run.isSuccess()).isFalse();
        assertThat(run.failure()).hasMessageContaining("moddn");
        // suspended: the next call cannot run it (the GUI would resume it first)
        assertThat(mp.attempt(() -> mp.liveSync("LDAP")).failure()).hasMessageContaining("SUSPENDED");
    }
}
