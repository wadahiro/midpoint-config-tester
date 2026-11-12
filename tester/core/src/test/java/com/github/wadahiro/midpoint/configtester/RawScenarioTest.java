package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.github.wadahiro.midpoint.tester.Shadow;

/**
 * Writes past the model ({@link Raw}): items as they are, and the three records of a linked account (the object on the
 * target system, the shadow, the link), each alone for the inconsistent states. The LDAP resource of the HR sample on
 * the mirror connector ({@code scenarios/hr-mirror}) and on the in-memory LDAP server ({@code scenarios/hr}): the
 * shadow's identifiers are the target system's (the mirror's generated uid, LDAP's entryUUID), and midPoint reads the
 * linked account through them ({@link RawMirrorScenarioTest}, {@link RawLdapScenarioTest}).
 */
abstract class RawScenarioTest {

    static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    static final String LDAP = "LDAP";
    static final String LEGACY = "uid=legacy," + HrLifecycleScenarioTest.PEOPLE;
    static final String ORPHAN = "uid=orphan," + HrLifecycleScenarioTest.PEOPLE;

    static void addUser(MidPointConfigTester t, String name) {
        t.midpoint().addObjectXml("<user xmlns='" + NS + "'><name>" + name + "</name>"
                + "<givenName>John</givenName><familyName>Doe</familyName></user>");
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
        t.raw().user("jdoe").add("organization", "A", "B").delete("organization", "A");
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
        assertThat(t.user("jdoe").accounts(LDAP)).containsExactly(LEGACY);
        t.user("jdoe").account(LDAP).shadow().exists().hasTag("legacy").hasKind("account").hasIntent("default");
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
        assertThat(t.midpoint().shadows(LDAP, LEGACY)).hasSize(1);
        t.target(LDAP).account(LEGACY).exists();
    }

    @Test
    void linkedButGoneFromTheTargetSystem(MidPointConfigTester t) {
        addUser(t, "jdoe");
        t.raw().user("jdoe").link(LDAP, LEGACY, shadow -> shadow.attribute("cn", "Legacy").attribute("sn", "L"));
        t.target(LDAP).delete(LEGACY);

        t.user("jdoe").hasAccountOn(LDAP);
        t.target(LDAP).account(LEGACY).doesNotExist();
        // the reads leave the inconsistency as it is: midPoint's provisioning would find and act on it
        ShadowType before = t.user("jdoe").account(LDAP).shadow().asObject();
        t.user("jdoe").account(LDAP).onTarget().doesNotExist();
        t.user("jdoe").account(LDAP).shadow().exists();
        ShadowType after = t.midpoint().shadows(LDAP, LEGACY).get(0);
        assertThat(before.asPrismObject().diff(after.asPrismObject()).isEmpty()).isTrue();
        assertThat(after.isDead()).isNotEqualTo(Boolean.TRUE);
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
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no item noSuchItem");
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
