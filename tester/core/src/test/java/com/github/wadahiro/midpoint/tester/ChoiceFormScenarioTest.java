package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.EditObjectForm;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * The drop-down fields of the GUI's forms ({@link EditObjectForm#set}): a boolean item's three-state drop-down and an
 * enumeration's, which take the value as their choice's id (fixtures/forms/choices.xml: an org's boolean and
 * enumeration extension items; choices-old-design.xml: 4.4's old design).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class ChoiceFormScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "choices.xml");
    private static final String LISTED = "extension/listed";
    private static final String LEVEL = "extension/level";
    private static final String UNIT = "form-listed-unit";

    @Test
    void theAdministratorSetsAndClearsABoolean(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.editForm(OrgType.class, UNIT).set(LISTED, true).submit().saves();
        t.org(UNIT).hasItem(LISTED, true);
        t.editForm(OrgType.class, UNIT).set(LISTED, false).submit().saves();
        t.org(UNIT).hasItem(LISTED, false);
        t.editForm(OrgType.class, UNIT).set(LISTED, null).submit().saves();
        t.org(UNIT).hasItem(LISTED);
        // into the empty field ("show empty fields")
        t.editForm(OrgType.class, UNIT).set(LISTED, true).submit().saves();
        t.org(UNIT).hasItem(LISTED, true);
    }

    @Test
    void aUserWhoMayChangeTheItemSetsABooleanAsText(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.actor("form-lister").editForm(OrgType.class, UNIT).set(LISTED, "true").submit().saves();
        t.org(UNIT).hasItem(LISTED, true);
        t.actor("form-lister").editForm(OrgType.class, UNIT).set(LISTED, "false").submit().saves();
        t.org(UNIT).hasItem(LISTED, false);
        t.actor("form-lister").editForm(OrgType.class, UNIT).set(LISTED, null).submit().saves();
        t.org(UNIT).hasItem(LISTED);
    }

    @Test
    void aNewObjectGetsABoolean(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.newObjectForm(OrgType.class).set("name", "form-listed-unit-2").set(LISTED, "true").submit().saves();
        t.org("form-listed-unit-2").hasItem(LISTED, true);
    }

    @Test
    void aValueNoChoiceHasFailsNamingTheChoices(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        assertThatThrownBy(() -> t.editForm(OrgType.class, UNIT).set(LISTED, "maybe").submit())
                .hasMessageContaining("Cannot fill in " + LISTED + ": its field has no choice maybe")
                .hasMessageContaining("true").hasMessageContaining("false");
        assertThatThrownBy(() -> t.actor("form-lister").editForm(OrgType.class, UNIT).set(LISTED, "yes").submit())
                .hasMessageContaining("has no choice yes");
        t.org(UNIT).hasItem(LISTED, false);
    }

    @Test
    void anEnumerationIsSetByItsValue(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        t.actor("form-lister").editForm(OrgType.class, UNIT).set(LEVEL, "high").submit().saves();
        t.org(UNIT).hasItem(LEVEL, "high");
        t.editForm(OrgType.class, UNIT).set(LEVEL, "low").submit().saves();
        t.org(UNIT).hasItem(LEVEL, "low");
        assertThatThrownBy(() -> t.editForm(OrgType.class, UNIT).set(LEVEL, "medium").submit())
                .hasMessageContaining("has no choice medium").hasMessageContaining("high");
        t.org(UNIT).hasItem(LEVEL, "low");
    }

    @Test
    void theOldDesignOf44TakesTheSameChoices(MidPointConfigTester t) {
        assumeTrue(t.line().equals("4.4"), "only 4.4 has both designs (useNewDesign)");
        t.midpoint().importObject(FIXTURE);
        t.midpoint().importObject(new File(Fixtures.dir("forms"), "choices-old-design.xml"));

        t.actor("form-old-design-lister").editForm(OrgType.class, UNIT).set(LISTED, true).set(LEVEL, "high")
                .submit().saves();
        t.org(UNIT).hasItem(LISTED, true).hasItem(LEVEL, "high");
        t.actor("form-old-design-lister").editForm(OrgType.class, UNIT).set(LISTED, null).submit().saves();
        t.org(UNIT).hasItem(LISTED);
    }

    @Test
    void anEmptyFieldOfAUserWhoMayChangeSomeItems(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        t.midpoint().addObjectXml("<org " + Fixtures.NS + "><name>form-empty-unit</name></org>");

        EditObjectForm form = t.actor("form-lister").editForm(OrgType.class, "form-empty-unit").set(LISTED, true);
        if (!t.line().equals("4.8")) {
            form.submit().saves();
            t.org("form-empty-unit").hasItem(LISTED, true);
        } else {
            // 4.8.14 (not 4.8.12): "show empty fields" fails rendering the page for the user, the read-only reference
            // panel's name model casting an empty reference to ObjectReferenceType (LinkedReferencePanelFactory)
            assertThatThrownBy(form::submit).hasMessageContaining("pressing \"show empty fields\"")
                    .hasMessageContaining("PageError");
            t.org("form-empty-unit").hasItem(LISTED);
        }
    }
}
