package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.NewObjectForm;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * The GUI's form for a new object ({@link NewObjectForm}): an archetype's object template validates the items as only the GUI's
 * form does (the model does not), with the value, the parent and the items already filled in; evaluated as the GUI
 * does (fixtures/forms/forms.xml says which item shows what).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class NewObjectFormScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "forms.xml");

    private static NewObjectForm category(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        return t.newObjectForm(OrgType.class).archetype("form-category").parent("form-site");
    }

    @Test
    void theFormValidatesAnItemByTheArchetypesTemplate(MidPointConfigTester t) {
        NewObjectForm form = category(t);

        form.validate("identifier", "CAT1").passes();
        NewObjectForm.Check lower = form.validate("identifier", "cat1").fails("The identifier is upper case letters and digits.");
        assertThat(lower.validated()).isTrue();
        assertThat(lower.passed()).isFalse();
    }

    @Test
    void theValidationReadsTheParentAndTheItemsFilledIn(MidPointConfigTester t) {
        NewObjectForm form = category(t);

        form.set("identifier", "CAT1").validate("displayName", "Category 1").passes();
        form.set("identifier", "CAT1").validate("displayName", "CAT1").fails("The display name is not the identifier.");
        t.newObjectForm(OrgType.class).archetype("form-category").validate("displayName", "Category 1")
                .fails("A category is created in a site.");
    }

    @Test
    void anItemWithoutAValidationTakesAnyValue(MidPointConfigTester t) {
        NewObjectForm form = category(t);

        NewObjectForm.Check check = form.validate("tenant", true);
        assertThat(check.validated()).isFalse();
        assertThat(check.passed()).isTrue();
        assertThatThrownBy(check::passes).hasMessageContaining("no validation of tenant");
        // without the archetype the form has no template, so no validation
        assertThat(t.newObjectForm(OrgType.class).parent("form-site").validate("identifier", "cat1").validated()).isFalse();
    }

    @Test
    void theModelDoesNotValidate(MidPointConfigTester t) {
        category(t);

        // what the form refuses, the model API adds: the validation is the GUI's only
        t.midpoint().addObjectXml("<org " + Fixtures.NS + "><name>cat1</name><identifier>cat1</identifier>"
                + "<assignment><targetRef oid=\"10000000-0000-0000-0000-0000000f0002\" type=\"ArchetypeType\"/></assignment>"
                + "<assignment><targetRef oid=\"10000000-0000-0000-0000-0000000f0003\" type=\"OrgType\"/></assignment></org>");
        t.org("cat1").exists();
    }

    @Test
    void theInputOfAPolyStringIsItsText(MidPointConfigTester t) {
        NewObjectForm form = category(t);

        form.validate("name", "cat1").passes();
        form.validate("name", new PolyString("cat1")).passes();
    }

    @Test
    void theFieldsValidatorDoesNotSeeTheValueInTheObject(MidPointConfigTester t) {
        NewObjectForm.Check check = category(t).validate("costCenter", "CC1");
        if (t.line().equals("4.0")) {
            // 4.0's text field validates twice on leaving it: the second sees the value the first put into the object
            check.fails("The cost center is in the object already.");
        } else {
            check.passes();
        }
    }

    @Test
    void savingValidatesAgainWithTheValueInTheObject(MidPointConfigTester t) {
        NewObjectForm form = category(t).set("identifier", "CAT1").set("displayName", "Category 1");
        form.validate("locality", "Tokyo").passes();
        form.set("name", "cat1").set("locality", "Tokyo").submit().rejects("locality")
                .rejectsWith("Saving sees the locality.");
    }

    @Test
    void anExpressionThatThrowsRejectsTheValue(MidPointConfigTester t) {
        NewObjectForm.Check check = category(t).validate("mailDomain", "boom").fails();
        assertThat(check.message()).startsWith("Cannot evaluate expression: ").contains("No mail domain service");
        category(t).validate("mailDomain", "example.com").passes();
    }

    @Test
    void aUserFriendlyMessageIsTranslated(MidPointConfigTester t) {
        category(t).validate("displayOrder", 0).fails("The display order is a positive number.");
        category(t).validate("displayOrder", 1).passes();
    }

    @Test
    void theValidationRunsAsTheUser(MidPointConfigTester t) {
        category(t);

        t.actor("form-clerk").newObjectForm(OrgType.class).archetype("form-category").parent("form-site")
                .validate("description", "Notes").passes();
        t.newObjectForm(OrgType.class).archetype("form-category").parent("form-site")
                .validate("description", "Notes").fails("Only the clerk documents categories.");
    }

    @Test
    void anItemTheUserMayNotAddHasNoValidation(MidPointConfigTester t) {
        category(t);

        assertThat(t.newObjectForm(OrgType.class).archetype("form-category").validate("riskLevel", "high").validated())
                .isTrue();
        assertThat(t.actor("form-clerk").newObjectForm(OrgType.class).archetype("form-category")
                .validate("riskLevel", "high").validated()).isFalse();
    }

    @Test
    void anExtensionItemByItsPath(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        NewObjectForm person = t.newObjectForm(UserType.class).archetype("form-person");

        person.validate("extension/site", "TOKYO").passes();
        person.validate("extension/site", "tokyo").fails("The site is upper case.");
    }

    @Test
    void savingTheFormValidatesEveryItem(MidPointConfigTester t) {
        NewObjectForm form = category(t);

        form.set("name", "cat1").set("identifier", "CAT1").set("displayName", "Category 1").submit().saves();
        t.org("cat1").exists();
        form.set("name", "cat2").set("identifier", "cat2").set("displayName", "Category 2").submit().rejects("identifier");
        NewObjectForm.Submit untouched = form.set("name", "cat3").set("displayName", "Category 3").submit();
        untouched.rejects("identifier");
        assertThat(untouched.rejected()).containsOnlyKeys("identifier");
    }
}
