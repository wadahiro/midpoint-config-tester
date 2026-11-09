package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * Guards the reset every scenario starts from (the deployment's snapshot) and that the deployment cache's restore
 * reuses: midPoint's caches must not survive it. Each repetition first checks the original behaviour, then changes the
 * configuration differently; all changes bump the objects to the same version (base + 1), and a resource imported
 * again under the same OID starts at its first version, so a cache keyed by OID and version (midPoint 4.0's
 * provisioning ResourceCache) that survives the reset serves the previous repetition's definitions and the next
 * repetition fails. A new midPoint line with a new kind of cache shows up here.
 */
@MidPointConfigTest(Fixtures.BARE)
class ResetTest {

    static final File DIR = Fixtures.ROOT;

    @TempDir
    static Path tmp;

    @BeforeEach
    void configure(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.importObject(new File(DIR, "role-employee.xml"));
        mp.importObject(new File(DIR, "role-dept-it.xml"));
        mp.importObject(new File(DIR, "object-template-user.xml"));
        mp.importObject(new File(DIR, "system-configuration.xml"));
        mp.importResource(Fixtures.of(mp, new File(DIR, "resource-hr.xml")));
    }

    /** The object template and an imported resource, changed after the reset. */
    @RepeatedTest(3)
    void sameResultAfterEveryReset(MidPointConfigTester t, RepetitionInfo repetition) throws IOException {
        MidPoint mp = t.midpoint();
        int round = repetition.getCurrentRepetition();
        TargetSystem hr = mp.target("HR");

        // 1. original behaviour, identical in every repetition
        assertThat(mp.user("jdoe")).isNull();
        hr.addAccount("jdoe", Map.of("fullname", "John Doe"));
        mp.importAccount("HR", "jdoe");
        mp.modify(UserType.class, "jdoe", UserType.F_LOCALITY, "tokyo");
        UserType user = mp.user("jdoe");
        assertThat(user.getFullName().getOrig()).isEqualTo("John Doe");
        assertThat(user.getAssignment()).hasSize(1);
        assertThat(hr.attribute("jdoe", "title")).isEqualTo("Employee");
        assertThat(hr.attribute("jdoe", "location")).isEqualTo("TOKYO");

        // 2. change the configuration, differently in every repetition
        mp.importObjectXml(read(new File(DIR, "object-template-user.xml"))
                .replace("\"$givenName $familyName\"", "\"$givenName $familyName #" + round + "\""));
        Path resource = tmp.resolve("resource-hr-" + round + ".xml");
        Files.writeString(resource, read(Fixtures.of(mp, new File(DIR, "resource-hr.xml")))
                .replace("locality?.orig?.toUpperCase()", "locality?.orig?.toUpperCase() + '#" + round + "'"));
        mp.importResource(resource.toFile());
        mp.recompute(UserType.class, "jdoe");
        mp.modify(UserType.class, "jdoe", UserType.F_LOCALITY, "osaka");
        assertThat(mp.user("jdoe").getFullName().getOrig()).isEqualTo("John Doe #" + round);
        assertThat(hr.attribute("jdoe", "location")).isEqualTo("OSAKA#" + round);
    }

    /** A resource first imported in the test, under the same OID with another definition in every repetition. */
    @RepeatedTest(2)
    void sameOidDifferentDefinitionAfterReset(MidPointConfigTester t, RepetitionInfo repetition) throws IOException {
        MidPoint mp = t.midpoint();
        int round = repetition.getCurrentRepetition();
        Path resource = tmp.resolve("resource-ldap-" + round + ".xml");
        Files.writeString(resource, read(new File(MembershipTest.DIR, "resource-ldap.xml"))
                .replace("name + '@example.com'", "name + '@r" + round + ".example.com'"));
        TargetSystem ldap = mp.importResource(resource.toFile());
        ldap.addGroup(MembershipTest.STAFF, Map.of("cn", "staff"));
        mp.importObject(new File(MembershipTest.DIR, "role-ldap-staff.xml"));
        mp.addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName>"
                + "<familyName>Doe</familyName></user>");

        mp.assign(UserType.class, "jdoe", MembershipTest.STAFF_ROLE);

        assertThat(ldap.accountNames()).containsExactly(MembershipTest.JDOE);
        assertThat(ldap.attribute(MembershipTest.JDOE, "mail")).isEqualTo("jdoe@r" + round + ".example.com");
        assertThat(ldap.groupValues(MembershipTest.STAFF, "member")).containsExactly(MembershipTest.JDOE);
    }

    private static String read(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
