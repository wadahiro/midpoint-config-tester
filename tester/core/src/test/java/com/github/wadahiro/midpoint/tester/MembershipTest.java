package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * Memberships as the target system keeps them (mirror options): a computed back-reference (LDAP {@code memberOf},
 * SCIM {@code groups}) and referential integrity, for references by name (LDAP DNs) and by identifier (SCIM ids), and
 * DNs that match regardless of case. Each method imports its resource with the options it needs.
 */
@MidPointConfigTest(Fixtures.BARE)
class MembershipTest {

    static final File DIR = Fixtures.dir("provisioning");
    static final String STAFF_ROLE = "10000000-0000-0000-0000-00000000c002";
    static final String STAFF = "cn=staff,ou=groups,dc=example,dc=com";
    static final String JDOE = "uid=jdoe,ou=people,dc=example,dc=com";
    static final String JOHN = "uid=john,ou=people,dc=example,dc=com";
    static final String SCIM = "10000000-0000-0000-0000-00000000a003";
    static final String SCIM_STAFF_ROLE = "10000000-0000-0000-0000-00000000c003";

    static final ResourceOptions LDAP_SERVER = ResourceOptions.defaults()
            .computed("memberOf", "groupOfNames", "member")
            .referentialIntegrity();
    static final ResourceOptions SCIM_SERVICE = ResourceOptions.defaults()
            .computed("groups", "Group", "members")
            .referentialIntegrity();

    @Test
    void ldapMemberOfIsComputedAndSeenByMidPoint(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        TargetSystem ldap = ldapWithJdoe(mp, LDAP_SERVER);

        mp.assign(UserType.class, "jdoe", STAFF_ROLE);

        assertThat(ldap.groupValues(STAFF, "member")).containsExactly(JDOE);       // written by midPoint
        assertThat(ldap.attributeValues(JDOE, "memberOf")).containsExactly(STAFF); // computed by the "server"
        String shadowOid = mp.user("jdoe").getLinkRef().get(0).getOid();
        // read through the model, which fetches the account (the repository keeps only some attributes)
        String shadow = mp.prismContext().xmlSerializer().serialize(
                mp.actor("administrator").get(ShadowType.class, shadowOid).value().asPrismObject());
        assertThat(shadow).contains("memberOf").contains(STAFF);
        assertThatThrownBy(() -> ldap.modifyAccount(JDOE, "memberOf", STAFF)).hasMessageContaining("computed");
    }

    @Test
    void ldapRenameAndDeleteKeepMembersValid(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem ldap = ldapWithJdoe(mp, LDAP_SERVER);
        mp.assign(UserType.class, "jdoe", STAFF_ROLE);

        ldap.renameAccount(JDOE, JOHN);                     // e.g. renamed by an LDAP administrator
        assertThat(ldap.groupValues(STAFF, "member")).containsExactly(JOHN);
        assertThat(ldap.attributeValues(JOHN, "memberOf")).containsExactly(STAFF);

        mp.modify(UserType.class, "jdoe", UserType.F_NAME, "jsmith");   // midPoint renames it through its dn mapping
        String jsmith = "uid=jsmith,ou=people,dc=example,dc=com";
        assertThat(ldap.accountNames()).containsExactly(jsmith);
        assertThat(ldap.groupValues(STAFF, "member")).containsExactly(jsmith);

        ldap.deleteAccount(jsmith);
        assertThat(ldap.groupValues(STAFF, "member")).isEmpty();
    }

    @Test
    void withoutReferentialIntegrityMembersGoStale(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem ldap = ldapWithJdoe(mp, ResourceOptions.defaults());
        mp.assign(UserType.class, "jdoe", STAFF_ROLE);

        ldap.renameAccount(JDOE, JOHN);

        assertThat(ldap.groupValues(STAFF, "member")).containsExactly(JDOE);
    }

    @Test
    void ldapMatchesDnsRegardlessOfCase(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem ldap = ldapWithJdoe(mp, ResourceOptions.defaults()
                .computed("memberOf", "groupOfNames", "member").caseInsensitiveNames());
        mp.assign(UserType.class, "jdoe", STAFF_ROLE);
        String upper = JDOE.toUpperCase(Locale.ROOT);

        assertThat(ldap.account(upper)).isNotNull();
        assertThatThrownBy(() -> ldap.addAccount(upper, Map.of())).hasMessageContaining("already exists");

        ldap.modifyGroup(STAFF, "member", upper);           // written in another case, e.g. by an LDAP tool
        assertThat(ldap.attributeValues(JDOE, "memberOf")).containsExactly(STAFF);
        mp.recompute(UserType.class, "jdoe");               // the membership is there: nothing added
        assertThat(ldap.groupValues(STAFF, "member")).containsExactly(upper);

        mp.unassign(UserType.class, "jdoe", STAFF_ROLE);   // midPoint removes uid=jdoe,...
        assertThat(ldap.accountNames()).isEmpty();
        assertThat(ldap.groupValues(STAFF, "member")).isEmpty();
    }

    @Test
    void dnsAreCaseSensitiveByDefault(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem ldap = ldapWithJdoe(mp, ResourceOptions.defaults().computed("memberOf", "groupOfNames", "member"));
        mp.assign(UserType.class, "jdoe", STAFF_ROLE);
        String upper = JDOE.toUpperCase(Locale.ROOT);

        assertThat(ldap.account(upper)).isNull();
        ldap.modifyGroup(STAFF, "member", upper);
        assertThat(ldap.attributeValues(JDOE, "memberOf")).isEmpty();
        mp.recompute(UserType.class, "jdoe");
        assertThat(ldap.groupValues(STAFF, "member")).containsExactlyInAnyOrder(upper, JDOE);
    }

    /**
     * SCIM ids. On 4.0 the identifier is {@code scimId} ({@code fixtures-4.0}): 4.0 cannot read back a shadow with an
     * attribute named {@code id} (the repository's parser takes it for the container id, a number).
     */
    @Test
    void scimMembersByIdAndComputedGroups(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem scim = mp.importResource(Fixtures.of(mp, new File(DIR, "resource-scim.xml")), SCIM_SERVICE);
        scim.addGroup("staff", Map.of());
        mp.importObject(new File(DIR, "role-scim-staff.xml"));
        mp.addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName></user>");

        mp.assign(UserType.class, "jdoe", SCIM_STAFF_ROLE);

        String jdoeId = scim.identifier("User", "jdoe");
        String staffId = scim.identifier("Group", "staff");
        assertThat(jdoeId).isNotEqualTo("jdoe");
        assertThat(scim.attribute("jdoe", "displayName")).isEqualTo("John Doe");
        assertThat(scim.groupValues("staff", "members")).containsExactly(jdoeId);
        assertThat(scim.attributeValues("jdoe", "groups")).containsExactly(staffId);

        // ids do not change on rename; a delete removes the member
        scim.renameAccount("jdoe", "john");
        assertThat(scim.groupValues("staff", "members")).containsExactly(jdoeId);
        scim.deleteAccount("john");
        assertThat(scim.groupValues("staff", "members")).isEmpty();
    }

    @Test
    void scimInboundFromComputedGroups(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem scim = mp.importResource(Fixtures.of(mp, new File(DIR, "resource-scim.xml")), SCIM_SERVICE);
        scim.addGroup("staff", Map.of());
        scim.addAccount("asmith", Map.of("displayName", "Alice Smith"));
        scim.modifyGroup("staff", "members", scim.identifier("User", "asmith"));

        mp.importAccount(SCIM, "asmith");                  // found by userName, not by id

        UserType user = mp.user("asmith");
        assertThat(user.getFullName().getOrig()).isEqualTo("Alice Smith");
        assertThat(user.getOrganization()).extracting(o -> o.getOrig())
                .containsExactly(scim.identifier("Group", "staff"));
    }

    private static TargetSystem ldapWithJdoe(MidPoint mp, ResourceOptions options) {
        TargetSystem ldap = mp.importResource(new File(DIR, "resource-ldap.xml"), options);
        ldap.addGroup(STAFF, Map.of("cn", "staff"));
        mp.importObject(new File(DIR, "role-ldap-staff.xml"));
        mp.addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName>"
                + "<familyName>Doe</familyName></user>");
        return ldap;
    }
}
