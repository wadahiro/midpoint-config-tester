package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.SynchronizationSituationType;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * {@link LiveSyncScenarioTest} on the mirror connectors: a token stands for the content of the mirror at that moment, a
 * run reports what differs from it, as the CSV connector compares the file with its copy.
 */
@MidPointConfigTest(Fixtures.BARE)
class LiveSyncMirrorTest extends LiveSyncScenarioTest {

    @BeforeEach
    void configure(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        configure(mp, ResourceOptions.defaults().namingAttribute("empno"), ResourceOptions.defaults());
        mp.target("LDAP").addGroup(SALES, Map.of("cn", "sales"));
        mp.target("LDAP").addGroup(IT, Map.of("cn", "it"));
    }

    /** A deletion in the directory is a change of the mirror (the LDAP connector does not see it on the server). */
    @Test
    void directoryDeletionIsAChange(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.target("HR").addAccount("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
        mp.importAccounts("HR");
        mp.liveSync("LDAP");                                           // the token

        mp.target("LDAP").deleteAccount(JOHN);
        Run synced = mp.liveSync("LDAP");

        assertThat(synced.evaluations()).isEmpty();                     // no reaction to deleted
        Shadow shadow = mp.shadow("e1001", "LDAP");
        assertThat(shadow.dead()).isTrue();
        assertThat(shadow.situation()).isEqualTo(SynchronizationSituationType.DELETED);
        // 4.0 keeps the dead shadow as the projection (linkRef default), 4.4+ as related
        assertThat(shadow.linked()).isEqualTo(!Lines.atLeast(mp, "4.4"));
    }
}
