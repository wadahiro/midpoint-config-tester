package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.ObjectView;

/**
 * An object as a user gets it ({@code t.actor(u).read(...)}, {@link ObjectView}): midPoint removes the items the
 * user's authorizations do not let them read (fixtures/actor/read-items.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class ReadAsUserScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("actor"), "read-items.xml");

    @Test
    void theUserReadsTheItemsTheirAuthorizationsLetThemRead(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        ObjectView view = t.actor("view-reader").read(t.user("view-target"));
        view.hasItem("givenName", "Vic").hasItem("familyName", "Target").hasItem("extension/site", "Prague")
                .hasNoItem("costCenter").hasNoItem("telephoneNumber");
        assertThat(view.values("costCenter")).isEmpty();
        assertThat(((UserType) view.asObject()).getCostCenter()).isNull();
        assertThat(t.actor("view-reader").read(UserType.class, "view-target").name()).isEqualTo("view-target");
        // the administrator reads them all
        t.user("view-target").hasItem("costCenter", "CC-7").hasItem("telephoneNumber", "+1 555 0199");
    }

    @Test
    void aFailedClaimShowsWhatTheObjectHas(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        ObjectView view = t.actor("view-reader").read(t.user("view-target"));
        assertThatThrownBy(() -> view.hasItem("costCenter", "CC-7"))
                .hasMessageContaining("view-reader reading UserType view-target: costCenter expected [CC-7] but the"
                        + " user reads []")
                .hasMessageContaining("the object has [CC-7]");
        assertThatThrownBy(() -> view.hasNoItem("givenName")).hasMessageContaining("but the user reads [Vic]");
    }

    @Test
    void aUserWhoMayNotGetTheObjectCannotReadIt(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        assertThatThrownBy(() -> t.actor("view-nobody").read(t.user("view-target")))
                .hasMessageContaining("view-nobody cannot get UserType");
    }
}
