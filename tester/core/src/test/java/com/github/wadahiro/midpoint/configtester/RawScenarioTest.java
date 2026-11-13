package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.tester.Shadow;

/**
 * Writes past the model ({@link Raw}): items as they are, and the three records of a linked account (the object on the
 * target system, the shadow, the link), each alone for the inconsistent states. The LDAP resource of the HR sample on
 * the mirror connector ({@code scenarios/hr-mirror}) and on the in-memory LDAP server ({@code scenarios/hr}): the
 * shadow's identifiers are the target system's (the mirror's generated uid, LDAP's entryUUID), and midPoint reads the
 * linked account through them ({@link RawMirrorScenarioTest}, {@link RawLdapScenarioTest}).
 */
abstract class RawScenarioTest {

    static final String LDAP = "LDAP";
    static final String LEGACY = "uid=legacy," + HrLifecycleScenarioTest.PEOPLE;
    static final String ORPHAN = "uid=orphan," + HrLifecycleScenarioTest.PEOPLE;
    static final String LEGACY_B = "uid=legacy-b," + HrLifecycleScenarioTest.PEOPLE;

    static void addUser(MidPointConfigTester t, String name) {
        t.user(name).add(u -> u.givenName("John").familyName("Doe"));
    }

    @Test
    void setWritesTheValuesAsTheyAreTheModelMakesThemAgain(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.user("jdoe").hasFullName("John Doe");

        t.raw().user("jdoe").set("fullName", "Legacy Name");
        t.user("jdoe").hasFullName("Legacy Name");

        t.user("jdoe").recompute();
        t.user("jdoe").hasFullName("John Doe");
    }

    @Test
    void addAndDeleteValues(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.raw().user("jdoe").addValues("organization", "A", "B").removeValues("organization", "A");
        t.user("jdoe").hasItem("organization", "B");
        t.raw().user("jdoe").set("organization");
        t.user("jdoe").hasItem("organization");
    }

    @Test
    void linkWritesTheAccountItsShadowAndTheLink(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.raw().user("jdoe").link(LDAP, LEGACY,
                shadow -> shadow.tag("legacy").attribute("cn", "Legacy").attribute("sn", "Legacy"));

        t.target(LDAP).account(LEGACY).exists().hasAttribute("cn", "Legacy");
        t.user("jdoe").hasAccountOn(LDAP);
        assertThat(t.user("jdoe").accounts(LDAP).names()).containsExactly(LEGACY);
        t.user("jdoe").account(LDAP).shadow().exists().hasTag("legacy").hasKind("account").hasIntent("default")
                .hasSituation("linked");
        // as midPoint keeps a projection it links: its next recompute has nothing of its own to add to the shadow
        assertThat(t.user("jdoe").account(LDAP).shadow().asObject().getIteration()).isEqualTo(0);
        assertThat(t.user("jdoe").account(LDAP).shadow().asObject().getIterationToken()).isEmpty();
        assertThat(t.user("jdoe").account(LDAP).shadow().tag()).isEqualTo("legacy");
        assertThat(t.user("jdoe").account(LDAP).shadow().kind()).isEqualTo("account");
        t.user("jdoe").account(LDAP).onTarget().hasAttribute("cn", "Legacy");
        Shadow shadow = t.midpoint().shadow("jdoe", LDAP);
        assertThat(shadow.name()).isEqualTo(LEGACY);
        assertThat(shadow.kind()).isEqualToIgnoringCase("account");
        assertThat(shadow.intent()).isEqualTo("default");
        assertThat(shadow.asObject().getTag()).isEqualTo("legacy");
        // midPoint finds the account on the target system by the shadow's identifiers
        ShadowType read = t.midpoint().actor("administrator").get(ShadowType.class, shadow.oid()).value();
        assertThat(attribute(read, "cn")).containsExactly("Legacy");
    }

    @Test
    void linkKeepsAnAccountThatIsThere(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.target(LDAP).add(LEGACY, java.util.Map.of("cn", "There", "sn", "There"));
        t.raw().user("jdoe").link(LDAP, LEGACY);
        t.target(LDAP).account(LEGACY).hasAttribute("cn", "There");
        t.user("jdoe").hasAccountOn(LDAP);
    }

    @Test
    void unlinkLeavesTheShadowAndTheAccount(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.raw().user("jdoe").link(LDAP, LEGACY, shadow -> shadow.attribute("cn", "Legacy").attribute("sn", "L"));
        t.raw().user("jdoe").unlink(LDAP, LEGACY);

        t.user("jdoe").hasNoAccountOn(LDAP);
        t.user("jdoe").account(LDAP, LEGACY).shadow().doesNotExist();
        assertThat(t.midpoint().shadows(LDAP, LEGACY)).hasSize(1);
        t.target(LDAP).account(LEGACY).exists();
    }

    @Test
    void linkedButGoneFromTheTargetSystem(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.raw().user("jdoe").link(LDAP, LEGACY, shadow -> shadow.attribute("cn", "Legacy").attribute("sn", "L"));
        t.target(LDAP).delete(LEGACY);
        ShadowType shadowBefore = t.midpoint().shadows(LDAP, LEGACY).get(0);
        UserType userBefore = t.midpoint().user("jdoe");

        // every check of the account reads the repository and the target system as they are: midPoint's
        // provisioning would find the account gone and mark its shadow dead, the consistency a test is about
        Effects checks = t.effectsOf(() -> {
            // an account is the link, its live shadow and the object on the target system: the last one is gone
            assertThatThrownBy(() -> t.user("jdoe").hasAccountOn(LDAP)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("LDAP does not have what it links (" + LEGACY + ")");
            assertThatThrownBy(() -> t.user("jdoe").hasAccountOn(LDAP, "default")).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> t.user("jdoe").hasNoAccountOn(LDAP)).isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> t.user("jdoe").account(LDAP).onTarget().hasAttribute("cn", "Legacy"))
                    .isInstanceOf(AssertionError.class);
            t.target(LDAP).account(LEGACY).doesNotExist();
            t.user("jdoe").account(LDAP).onTarget().doesNotExist();
            t.user("jdoe").accounts(LDAP).single().shadow().exists().hasKind("account");
            t.user("jdoe").projections(LDAP).exactly(1);
        });

        assertThat(checks.connectorCalls().count()).isZero();
        checks.none();
        ShadowType shadowAfter = t.midpoint().shadows(LDAP, LEGACY).get(0);
        assertThat(shadowBefore.asPrismObject().diff(shadowAfter.asPrismObject()).isEmpty()).isTrue();
        assertThat(shadowAfter.isDead()).isNotEqualTo(Boolean.TRUE);
        assertThat(userBefore.asPrismObject().diff(t.midpoint().user("jdoe").asPrismObject()).isEmpty()).isTrue();
    }

    @Test
    void anAccountOfTheReadSideThatIsNotThere(MidPointConfigTester t) {
        addUser(t, "jdoe");
        assertThatThrownBy(() -> t.user("jdoe").account(LDAP))
                .isInstanceOf(AssertionError.class).hasMessageContaining("expected one account on LDAP, but links none");
        assertThatThrownBy(() -> t.user("jdoe").account(LDAP, LEGACY).shadow().exists())
                .isInstanceOf(AssertionError.class).hasMessageContaining("links none");
    }

    @Test
    void accountsOfOneIntentAreToldApartByTheirTag(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.raw().user("jdoe").link(LDAP, LEGACY, s -> s.tag("tenant-a").attribute("cn", "A").attribute("sn", "A"));
        t.raw().user("jdoe").link(LDAP, LEGACY_B, s -> s.tag("tenant-b").attribute("cn", "B").attribute("sn", "B"));

        t.user("jdoe").accounts(LDAP).exactly(2);
        t.user("jdoe").accounts(LDAP).intent("default").tag("tenant-b").single().onTarget().hasAttribute("cn", "B");
        assertThat(t.user("jdoe").accounts(LDAP).tag("tenant-a").single().name()).isEqualTo(LEGACY);
        t.user("jdoe").accounts(LDAP).tag("tenant-c").none();
        t.user("jdoe").hasAccountOn(LDAP, "default").hasNoAccountOn(LDAP, "admin");
        assertThatThrownBy(() -> t.user("jdoe").account(LDAP)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("User jdoe: expected one account on LDAP, but links 2 (narrow them: intent, tag"
                        + " or named)")
                .hasMessageContaining(LEGACY + " (account/default/tenant-a)");
        assertThatThrownBy(() -> t.user("jdoe").accounts(LDAP).tag("tenant-a").none())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected no account on LDAP with tag tenant-a, but links " + LEGACY)
                .hasMessageContaining(LEGACY_B + " (account/default/tenant-b; other tag)");
    }

    @Test
    void aRoleLinksItsGroup(MidPointConfigTester t) {
        t.raw().role("Dept Sales").link(LDAP, HrLifecycleScenarioTest.SALES,
                s -> s.kind("entitlement").intent("group").attribute("cn", "sales"));

        t.role("Dept Sales").hasEntitlementOn(LDAP).hasEntitlementOn(LDAP, "group").hasNoAccountOn(LDAP);
        t.role("Dept Sales").entitlement(LDAP).shadow().hasKind("entitlement").hasIntent("group");
        t.role("Dept Sales").entitlement(LDAP).onTarget().exists().hasAttribute("cn", "sales");
        t.role("Dept Sales").projections(LDAP).kind("entitlement").exactly(1);
        assertThat(t.role("Dept Sales").projections(LDAP).names()).containsExactly(HrLifecycleScenarioTest.SALES);
        assertThat(t.role("Dept Sales").projections(LDAP).single().onTarget()).isInstanceOf(Group.class);
        assertThatThrownBy(() -> t.role("Dept Sales").hasAccountOn(LDAP)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("Role Dept Sales: expected an account on LDAP, but links none; linked on LDAP: "
                        + HrLifecycleScenarioTest.SALES + " (entitlement/group; not an account)");
    }

    @Test
    void theClaimsOfAnAccountReadTheTargetSystem(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.raw().user("jdoe").link(LDAP, LEGACY, s -> s.attribute("cn", "Legacy").attribute("sn", "L"));

        assertThatThrownBy(() -> t.user("jdoe").account(LDAP).onTarget().hasAttribute("cn", "Other"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Account " + LEGACY + " on LDAP: cn expected [Other] but was [Legacy]");
        t.target(LDAP).delete(LEGACY);
        assertThatThrownBy(() -> t.user("jdoe").hasAccountOn(LDAP, "default")).isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected an account on LDAP with intent default, but LDAP does not have what it"
                        + " links (" + LEGACY + ")");
        assertThatThrownBy(() -> t.user("jdoe").account(LDAP).onTarget().hasAttribute("cn", "Legacy"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected an account " + LEGACY + " on LDAP, but there is none");
        assertThatThrownBy(() -> t.user("jdoe").hasAccountOn(LDAP, "admin")).isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected an account on LDAP with intent admin, but links none; linked on LDAP: "
                        + LEGACY + " (account/default; other intent)");
    }

    @Test
    void theClaimsOfAnEntitlementReadTheTargetSystem(MidPointConfigTester t) {
        String sales = HrLifecycleScenarioTest.SALES;
        t.role("Dept IT").hasNoEntitlementOn(LDAP);
        t.raw().role("Dept Sales").link(LDAP, sales,
                s -> s.kind("entitlement").intent("group").attribute("cn", "sales"));
        assertThatThrownBy(() -> t.role("Dept Sales").hasNoEntitlementOn(LDAP)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("Role Dept Sales: expected no entitlement on LDAP, but links " + sales);

        assertThatThrownBy(() -> t.role("Dept Sales").entitlement(LDAP).onTarget().hasAttribute("cn", "it"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Group " + sales + " on LDAP: cn expected [it] but was [sales]");
        t.target(LDAP).system().deleteGroup(sales);
        assertThatThrownBy(() -> t.role("Dept Sales").hasEntitlementOn(LDAP)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("Role Dept Sales: expected an entitlement on LDAP, but LDAP does not have what it"
                        + " links (" + sales + ")");
        assertThatThrownBy(() -> t.role("Dept Sales").hasEntitlementOn(LDAP, "group"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("does not have what it links");
        ShadowType shadowBefore = t.midpoint().shadows(LDAP, sales).get(0);
        Effects reads = t.effectsOf(() -> {
            t.role("Dept Sales").entitlement(LDAP).onTarget().doesNotExist();
            t.role("Dept Sales").entitlement(LDAP).shadow().exists();
        });
        // as for an account: the reads leave the shadow as it is
        assertThat(reads.connectorCalls().count()).isZero();
        reads.none();
        ShadowType shadowAfter = t.midpoint().shadows(LDAP, sales).get(0);
        assertThat(shadowBefore.asPrismObject().diff(shadowAfter.asPrismObject()).isEmpty()).isTrue();
        assertThat(shadowAfter.isDead()).isNotEqualTo(Boolean.TRUE);
        assertThatThrownBy(() -> t.role("Dept Sales").entitlement(LDAP).onTarget().hasAttribute("cn", "sales"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected a group " + sales + " on LDAP, but there is none");
    }

    @Test
    void aShadowNobodyLinks(MidPointConfigTester t) {
        t.raw().shadow(LDAP, ORPHAN).add(shadow -> shadow.tag("orphan"));
        List<ShadowType> shadows = t.midpoint().shadows(LDAP, ORPHAN);
        assertThat(shadows).hasSize(1);
        assertThat(shadows.get(0).getTag()).isEqualTo("orphan");
        assertThat(shadows.get(0).getPrimaryIdentifierValue()).isEqualTo(ORPHAN);

        t.raw().shadow(LDAP, ORPHAN).delete();
        assertThat(t.midpoint().shadows(LDAP, ORPHAN)).isEmpty();
    }

    @Test
    void mistakesOfTheTest(MidPointConfigTester t) {
        addUser(t, "jdoe");
        assertThatThrownBy(() -> t.raw().user("nobody").set("fullName", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no UserType nobody");
        assertThatThrownBy(() -> t.raw().user("jdoe").set("noSuchItem", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No item noSuchItem");
        assertThatThrownBy(() -> t.raw().user("jdoe").unlink(LDAP, LEGACY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("links no");
        assertThatThrownBy(() -> t.raw().user("jdoe").link(LDAP, LEGACY, shadow -> shadow.intent("nope")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no object type account/nope");
        assertThatThrownBy(() -> t.raw().shadow(LDAP, ORPHAN).delete())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no shadow");
        // the repository refuses the write (a name another user has): a failure of making the state, not an assertion
        addUser(t, "other");
        assertThatThrownBy(() -> t.raw().user("jdoe").set("name", "other"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("modify raw");
    }

    private static List<String> attribute(ShadowType shadow, String name) {
        return shadow.asPrismObject().findContainer(ShadowType.F_ATTRIBUTES).getValue().getItems().stream()
                .filter(item -> item.getElementName().getLocalPart().equals(name))
                .flatMap(item -> item.getRealValues().stream()).map(String::valueOf)
                .collect(Collectors.toList());
    }
}
