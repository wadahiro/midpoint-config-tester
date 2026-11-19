package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.github.wadahiro.midpoint.configtester.EditObjectForm;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * The GUI's form of an existing object ({@link EditObjectForm}) and what the GUI's pages decide as a user, run by
 * midPoint's GUI (fixtures/forms/forms.xml).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class EditObjectFormScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "forms.xml");

    /** A category, saved through its new form. */
    private static EditObjectForm saved(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        t.newObjectForm(OrgType.class).archetype("form-category").parent("form-site").set("name", "cat1")
                .set("identifier", "CAT1").set("displayName", "Category 1").submit().saves();
        return t.editForm(OrgType.class, "cat1");
    }

    @Test
    void theFieldsValidatorSeesTheStoredValues(MidPointConfigTester t) {
        // the cost center's validation refuses a value when the object has one: none stored (4.0 validates its text
        // field twice, the second time with the value in the object)
        EditObjectForm.Check none = saved(t).validate("costCenter", "CC1");
        if (t.line().equals("4.0")) {
            none.fails("The cost center is in the object already.");
        } else {
            none.passes();
        }
        // stored (through the model, which does not validate): the field's validator sees it in the object
        t.midpoint().addObjectXml("<org " + Fixtures.NS + "><name>cat2</name><identifier>CAT2</identifier>"
                + "<costCenter>CC1</costCenter>"
                + "<assignment><targetRef oid=\"10000000-0000-0000-0000-0000000f0002\" type=\"ArchetypeType\"/></assignment>"
                + "</org>");
        EditObjectForm stored = t.editForm(OrgType.class, "cat2");
        stored.validate("costCenter", "CC2").fails("The cost center is in the object already.");
        stored.set("costCenter", "CC2").submit().rejects("costCenter");
        assertThat(t.midpoint().find(OrgType.class, "cat2").getCostCenter()).isEqualTo("CC1");
    }

    @Test
    void aValueTypedIsInTheObjectWhenSaving(MidPointConfigTester t) {
        // leaving the field puts the value into the object: saving validates the field again, and sees it there
        saved(t).set("costCenter", "CC1").submit().rejects("costCenter")
                .rejectsWith("The cost center is in the object already.");
    }

    @Test
    void anInvalidChangeIsNotSaved(MidPointConfigTester t) {
        EditObjectForm form = saved(t);

        form.set("identifier", "cat1").submit().rejects("identifier");
        assertThat(t.midpoint().find(OrgType.class, "cat1").getIdentifier()).isEqualTo("CAT1");
        form.set("identifier", "CAT9").submit().saves();
        assertThat(t.midpoint().find(OrgType.class, "cat1").getIdentifier()).isEqualTo("CAT9");
    }

    @Test
    void aUserWhoMayNotOpenThePageHasNoForm(MidPointConfigTester t) {
        saved(t);
        t.midpoint().addObjectXml("<user " + Fixtures.NS + "><name>form-nobody</name></user>");

        assertThatThrownBy(() -> t.actor("form-nobody").editForm(OrgType.class, "cat1").validate("identifier", "CAT2"))
                .hasMessageContaining("form-nobody may not open /admin/");
    }

    @Test
    void anItemTheUserMayNotChangeHasNoField(MidPointConfigTester t) {
        saved(t);

        // the clerk adds categories, and changes none of their items
        EditObjectForm.Check check = t.actor("form-clerk").editForm(OrgType.class, "cat1").validate("identifier", "CAT2");
        assertThat(check.absent()).isNotNull();
        assertThatThrownBy(check::passes).hasMessageContaining("No field to type identifier into");
    }
}
