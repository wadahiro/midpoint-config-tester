package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * An identifier without a native name ({@code icfs:uid}) that the target system issues on create, apart from the
 * name: given as {@code identifier("icfs:uid")}, the account's identifier is a UUID that a rename by midPoint keeps.
 * Without it the identifier is the name.
 */
@MidPointConfigTest(Fixtures.BARE)
class GeneratedIdentifierTest {

    static final File DIR = Fixtures.dir("provisioning");
    static final String ROLE = "10000000-0000-0000-0000-00000000c004";

    @Test
    void theTargetSystemIssuesTheIdentifier(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem plain = plainWithJdoe(mp, ResourceOptions.defaults().identifier("icfs:uid"));
        mp.assign(UserType.class, "jdoe", ROLE);

        String uid = plain.identifier("AccountObjectClass", "jdoe");
        assertThat(uid).isNotEqualTo("jdoe").hasSize(36);

        mp.modify(UserType.class, "jdoe", UserType.F_NAME, "jsmith");

        assertThat(plain.accountNames()).containsExactly("jsmith");
        assertThat(plain.identifier("AccountObjectClass", "jsmith")).isEqualTo(uid);
        assertThat(mp.user("jsmith").getLinkRef()).hasSize(1);
    }

    @Test
    void withoutItTheIdentifierIsTheName(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem plain = plainWithJdoe(mp, ResourceOptions.defaults());
        mp.assign(UserType.class, "jdoe", ROLE);

        assertThat(plain.identifier("AccountObjectClass", "jdoe")).isEqualTo("jdoe");
    }

    private static TargetSystem plainWithJdoe(MidPoint mp, ResourceOptions options) {
        TargetSystem plain = mp.importResource(new File(DIR, "resource-plain.xml"), options);
        mp.importObject(new File(DIR, "role-plain.xml"));
        mp.addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName></user>");
        return plain;
    }
}
