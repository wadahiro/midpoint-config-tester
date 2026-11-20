package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.EditObjectForm;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * The values of a multi-valued item in the GUI's forms ({@link EditObjectForm#set} with a collection,
 * {@link EditObjectForm#add}, {@link EditObjectForm#remove}): typed into the item's value rows, which its add and
 * remove buttons make, and saved once (fixtures/forms/multi-valued.xml: an org's multi-valued extension item).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class MultiValuedFormScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "multi-valued.xml");
    private static final String TAGS = "extension/tags";

    @Test
    void aUserWhoMayChangeTheItemSetsItsValuesInOneSave(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        // the stored value kept, two rows added
        t.actor("form-tagger").editForm(OrgType.class, "form-unit").set(TAGS, List.of("alpha", "beta", "gamma"))
                .submit().saves();
        t.org("form-unit").hasItem(TAGS, "alpha", "beta", "gamma");

        // exactly these: the rows of the others removed
        t.actor("form-tagger").editForm(OrgType.class, "form-unit").set(TAGS, List.of("beta", "delta"))
                .submit().saves();
        t.org("form-unit").hasItem(TAGS, "beta", "delta");

        t.actor("form-tagger").editForm(OrgType.class, "form-unit").set(TAGS, List.of()).submit().saves();
        t.org("form-unit").hasItem(TAGS);
    }

    @Test
    void valuesAreAddedAndRemovedAsTheUserDoesIt(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.actor("form-tagger").editForm(OrgType.class, "form-unit").add(TAGS, "beta").add(TAGS, "gamma")
                .submit().saves();
        t.org("form-unit").hasItem(TAGS, "alpha", "beta", "gamma");

        t.actor("form-tagger").editForm(OrgType.class, "form-unit").remove(TAGS, "alpha").add(TAGS, "delta")
                .submit().saves();
        t.org("form-unit").hasItem(TAGS, "beta", "gamma", "delta");

        assertThatThrownBy(() -> t.actor("form-tagger").editForm(OrgType.class, "form-unit").remove(TAGS, "omega")
                .submit()).hasMessageContaining("Cannot remove omega from " + TAGS);
    }

    @Test
    void aNewObjectGetsSeveralValues(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.newObjectForm(OrgType.class).set("name", "form-unit-2").set(TAGS, List.of("one", "two")).submit().saves();
        t.org("form-unit-2").hasItem(TAGS, "one", "two");
    }

    @Test
    void aUserWhoMayOnlyReadTheItemCannotChangeItsValues(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        assertThatThrownBy(() -> t.actor("form-reader").editForm(OrgType.class, "form-unit")
                .set(TAGS, List.of("alpha", "beta")).submit()).hasMessageContaining("Cannot fill in " + TAGS);
        t.org("form-unit").hasItem(TAGS, "alpha");
    }

    @Test
    void aSingleValuedItemTakesOneValue(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        assertThatThrownBy(() -> t.editForm(OrgType.class, "form-unit").set("costCenter", List.of("CC1", "CC2"))
                .submit()).hasMessageContaining("costCenter is single-valued");
        t.editForm(OrgType.class, "form-unit").set("costCenter", List.of("CC1")).submit().saves();
        t.org("form-unit").hasItem("costCenter", "CC1");
    }
}
