package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Roles;
import com.github.wadahiro.midpoint.configtester.sample.hr.UserExtension;

/**
 * Focuses added through the model ({@link Focus#add()}, {@link User#add}...) over the HR sample
 * ({@code scenarios/hr}): the user template fills in what the test does not give, a role assigned provisions the
 * account; the values of the items are read as their types, extension items too.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class FocusWriteScenarioTest {

    static final String NS = "http://example.com/xml/ns/tester-extension";

    @Test
    void aUserAddedByItsNameOnly(MidPointConfigTester t) {
        Effects added = t.user("jdoe").add();

        added.changes().of("jdoe").added().single();
        t.user("jdoe").exists().hasItem("emailAddress", "jdoe@example.com").hasNoAccountOn(Resources.LDAP);
        assertThat(t.user("jdoe").oid()).isNotBlank();
    }

    @Test
    void aUserAddedWithItemsAndARoleGetsItsAccount(MidPointConfigTester t) {
        Effects added = t.user("jdoe").add(u -> u.givenName("John").familyName("Doe").set("employeeNumber", "1001")
                .assign(Roles.DEPT_SALES).validTo(Instant.parse("2030-04-01T00:00:00Z")));

        added.connectorCalls().to("LDAP").adds().of("uid=jdoe,ou=people,dc=example,dc=com").single();
        t.user("jdoe").hasFullName("John Doe").hasAssignment(Roles.DEPT_SALES).hasAccountOn(Resources.LDAP)
                .hasItem("activation/validTo", "2030-04-01T09:00:00+09:00");
    }

    @Test
    void extensionItemsAreReadAsTheirTypes(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.set("extension/headcount", "5").set(UserExtension.SITE, "Prague")
                .set("extension/vip", "true").set("extension/since", "2030-04-01T09:00:00+09:00")
                .set("extension/tags", "a", "b").set("extension/level", "high"));

        t.user("jdoe").hasItem("extension/headcount", 5).hasItem("extension/headcount", "5")
                .hasItem(UserExtension.SITE, "Prague")
                .hasItem("extension/vip", true).hasItem("extension/since", Instant.parse("2030-04-01T00:00:00Z"))
                .hasItem("extension/tags", "b", "a").hasItem("extension/level", "high");
        assertThat(extension(t, "headcount")).isEqualTo(5);
        assertThat(extension(t, "vip")).isEqualTo(true);

        t.raw().user("jdoe").set("extension/headcount", "7");
        assertThat(extension(t, "headcount")).isEqualTo(7);
    }

    @Test
    void aValueTheItemCannotTakeIsAMistakeOfTheTest(MidPointConfigTester t) {
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/vip", "yes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension/vip (xsd:boolean): not a value: yes");
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/headcount", "five")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension/headcount (xsd:int): not a value: five");
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/level", "medium")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("values: [low, high]");
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/noSuch", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No item noSuch of extension/noSuch");
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.assign("Nobody")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No role, org, service or archetype Nobody");
        t.user("jdoe").doesNotExist();
    }

    @Test
    void anObjectOfANameThatIsTakenIsRefused(MidPointConfigTester t) {
        t.user("jdoe").add();
        t.expectFailure(() -> t.user("jdoe").add()).isAlreadyExists();
    }

    @Test
    void anOidTheTestGives(MidPointConfigTester t) {
        String oid = "aaaaaaaa-0000-0000-0000-000000000001";
        t.user("jdoe").add(u -> u.oid(oid));
        assertThat(t.user("jdoe").oid()).isEqualTo(oid);
    }

    @Test
    void rolesOrgsAndServicesAreAddedAlike(MidPointConfigTester t) {
        t.role("Proofreader").add(r -> r.set("description", "proofreads").object(role -> role.setRiskLevel("low")));
        t.role("Proofreader").isEnabled().hasItem("description", "proofreads").hasItem("riskLevel", "low");
        t.org("rnd").add();
        t.org("apollo").add(o -> o.parent("rnd"));
        t.org("apollo").hasParent("rnd");
        t.service("Directory").add(s -> s.set("description", "the LDAP"));
        t.service("Directory").exists().hasItem("description", "the LDAP");
    }

    @Test
    void aFocusMidPointNamesIsAddedWithoutANameAndTakenByItsOid(MidPointConfigTester t) {
        t.org("rnd").add();
        Org project = t.addOrg(o -> o.set("identifier", "099").parent("rnd"));    // the org template names it

        assertThat(project.name()).isEqualTo("P-099");
        project.hasItem("identifier", "099").hasParent("rnd");
        t.org("P-099").hasParent("rnd");
        t.org("child").add(c -> c.parent(project.oid()));
        t.org("child").hasParent("P-099");
        t.user("jdoe").add(u -> u.assign(project.oid()));
        t.user("jdoe").hasAssignment(project.oid()).hasAssignment("P-099");
        project.hasMember("jdoe");

        Org renamed = t.addOrg(o -> o.set("name", "t99").set("identifier", "100"));   // a name the template replaces
        assertThat(renamed.name()).isEqualTo("P-100");
        t.org("t99").doesNotExist();
        assertThatThrownBy(() -> t.org("t99").hasParent("rnd"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("t.addOrg(...)");

        Effects added = t.effectsOf(() -> t.addOrg(o -> o.set("identifier", "101")));
        added.changes().of("P-101").added().single();
        String oid = "aaaaaaaa-0000-0000-0000-000000000003";
        assertThat(t.addOrg(o -> o.oid(oid).set("identifier", "102")).oid()).isEqualTo(oid);
        assertThatThrownBy(() -> t.org(oid).add())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is an OID, not a name");
    }

    @Test
    void aModificationIsOneDeltaThroughTheModel(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.givenName("John").familyName("Doe").set("extension/tags", "a", "b"));

        Effects modified = t.user("jdoe").modify(m -> m.set("telephoneNumber", "+1 555")
                .addValues("extension/tags", "c").removeValues("extension/tags", "a").set("extension/headcount", 8L)
                .assign(Roles.DEPT_SALES));
        modified.changes().of("jdoe").single().only("telephoneNumber", "extension/tags", "extension/headcount",
                "assignment", "roleMembershipRef", "linkRef");
        modified.connectorCalls().to("LDAP").adds().of("uid=jdoe,ou=people,dc=example,dc=com").single();
        t.user("jdoe").hasItem("telephoneNumber", "+1 555").hasItem("extension/tags", "b", "c")
                .hasItem("extension/headcount", 8).hasAccountOn(Resources.LDAP);

        t.user("jdoe").modify(m -> m.unassign(Roles.DEPT_SALES)).connectorCalls().to("LDAP").deletes().single();
        t.user("jdoe").hasNoAccountOn(Resources.LDAP);
        assertThatThrownBy(() -> t.user("jdoe").modify(m -> m.assign("Nobody")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No role, org, service or archetype");
        assertThatThrownBy(() -> t.role("Dept Sales").modify(m -> m.password("x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("A password is a user's");
    }

    @Test
    void aFocusDeletedThroughTheModelLosesItsAccount(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.givenName("John").familyName("Doe").assign(Roles.DEPT_SALES));
        t.user("jdoe").hasAccountOn(Resources.LDAP);

        Effects deleted = t.user("jdoe").delete();
        deleted.changes().of("jdoe").deleted().single();
        deleted.connectorCalls().to("LDAP").deletes().of("uid=jdoe,ou=people,dc=example,dc=com").single();
        t.user("jdoe").doesNotExist();
        assertThatThrownBy(() -> t.user("jdoe").delete())
                .isInstanceOf(AssertionError.class).hasMessageContaining("Expected a user jdoe");
    }

    @Test
    void aReconcileOfTheFocusSetsTheAccountAsTheMappingsSay(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.givenName("John").familyName("Doe").assign(Roles.DEPT_SALES));
        String dn = "uid=jdoe,ou=people,dc=example,dc=com";
        t.target(Resources.LDAP).modify(dn, "mail");                     // removed on the target system

        t.user("jdoe").modify(Modification::reconcile).connectorCalls().to("LDAP").modifies().of(dn).single();
        t.target(Resources.LDAP).account(dn).hasAttribute("mail", "jdoe@example.com");
    }

    private static Object extension(MidPointConfigTester t, String item) {
        UserType user = t.midpoint().user("jdoe");
        PrismProperty<?> property = user.asPrismObject().findProperty(ItemPath.create(UserType.F_EXTENSION,
                new javax.xml.namespace.QName(NS, item)));
        return property != null ? property.getRealValue() : null;
    }
}
