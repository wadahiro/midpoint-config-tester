package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.namespace.QName;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.github.wadahiro.midpoint.configtester.EditObjectForm;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.ObjectForm;

/**
 * The fields of the GUI's forms by the type of their item ({@link EditObjectForm#set}): a value is typed as the
 * field's converter shows it, and is in the field's model afterwards, or the form fails (fixtures/forms/field-types.xml:
 * an org with an extension item of each type, a typist who may change only those items).
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class FieldTypeFormScenarioTest {

    private static final File FIXTURE = new File(Fixtures.dir("forms"), "field-types.xml");
    private static final String UNIT = "form-typed-unit";
    private static final String TYPIST = "form-typist";
    private static final String OPENED = "extension/opened";

    private static EditObjectForm form(MidPointConfigTester t, String user) {
        t.midpoint().importObject(FIXTURE);
        return user == null ? t.editForm(OrgType.class, UNIT) : t.actor(user).editForm(OrgType.class, UNIT);
    }

    /** The instant of the org's stored date (whatever time zone it is stored in). */
    private static Instant opened(MidPointConfigTester t) {
        List<String> values = t.org(UNIT).values(OPENED);
        assertThat(values).hasSize(1);
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(values.get(0)).toGregorianCalendar()
                    .toInstant();
        } catch (javax.xml.datatype.DatatypeConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void numbersAreTypedAsTheirValue(MidPointConfigTester t) {
        form(t, null).set("extension/seats", 42).set("extension/budget", 5_000_000_000L)
                .set("extension/floors", List.of(2, 3)).submit().saves();
        t.org(UNIT).hasItem("extension/seats", 42).hasItem("extension/budget", 5_000_000_000L)
                .hasItem("extension/floors", 2, 3);
        // as text, and a value row added
        t.actor(TYPIST).editForm(OrgType.class, UNIT).set("extension/seats", "43")
                .add("extension/floors", 4).submit().saves();
        t.org(UNIT).hasItem("extension/seats", 43).hasItem("extension/floors", 2, 3, 4);
        t.actor(TYPIST).editForm(OrgType.class, UNIT).set("extension/seats", null).submit().saves();
        t.org(UNIT).hasItem("extension/seats");
    }

    @Test
    void aNumberTheFieldDoesNotTakeFails(MidPointConfigTester t) {
        // a text the field's converter does not read: the field's message
        ObjectForm.Submit submit = form(t, TYPIST).set("extension/seats", "many").submit();
        assertThat(submit.saved()).isFalse();
        assertThat(submit.rejected()).containsKey("extension/seats");
        assertThatThrownBy(() -> t.editForm(OrgType.class, UNIT).set("extension/seats", 2.5).submit())
                .hasMessageContaining("Cannot fill in extension/seats: its field takes a whole number (Integer)");
        t.org(UNIT).hasItem("extension/seats", 10);
    }

    @Test
    void aDoubleHasAFieldFrom48(MidPointConfigTester t) {
        EditObjectForm form = form(t, null).set("extension/ratio", 0.25);
        if (t.line().equals("4.0") || t.line().equals("4.4")) {
            // no panel factory of xsd:double (TextPanelFactory: 4.8 and later)
            assertThatThrownBy(form::submit).hasMessageContaining("Cannot fill in extension/ratio: the GUI shows no"
                    + " input of extension/ratio, which the user may change: the line's GUI has no field of its type");
            t.org(UNIT).hasItem("extension/ratio", 0.5);
        } else {
            form.submit().saves();
            t.org(UNIT).hasItem("extension/ratio", 0.25);
            t.actor(TYPIST).editForm(OrgType.class, UNIT).set("extension/ratio", "1.5").submit().saves();
            t.org(UNIT).hasItem("extension/ratio", 1.5);
        }
    }

    @Test
    void aDateIsStoredAsTheInstantSet(MidPointConfigTester t) {
        form(t, null).set(OPENED, Instant.parse("2026-03-04T10:15:00Z")).submit().saves();
        assertThat(opened(t)).isEqualTo(Instant.parse("2026-03-04T10:15:00Z"));
        // in another time zone than the JVM's: the same instant
        t.editForm(OrgType.class, UNIT).set(OPENED, ZonedDateTime.parse("2026-03-04T05:20:00-05:00[America/New_York]"))
                .submit().saves();
        assertThat(opened(t)).isEqualTo(Instant.parse("2026-03-04T10:20:00Z"));
        t.editForm(OrgType.class, UNIT).set(OPENED, ZonedDateTime.parse("2026-03-04T19:25:00+09:00[Asia/Tokyo]"))
                .submit().saves();
        assertThat(opened(t)).isEqualTo(Instant.parse("2026-03-04T10:25:00Z"));
        t.editForm(OrgType.class, UNIT).set(OPENED, "2026-07-01T23:45:00+02:00").submit().saves();
        assertThat(opened(t)).isEqualTo(Instant.parse("2026-07-01T21:45:00Z"));
    }

    @Test
    void aUserWhoMayChangeTheItemSetsAndClearsADate(MidPointConfigTester t) throws Exception {
        form(t, TYPIST).set(OPENED, DatatypeFactory.newInstance().newXMLGregorianCalendar("2027-12-31T00:00:00Z"))
                .submit().saves();
        assertThat(opened(t)).isEqualTo(Instant.parse("2027-12-31T00:00:00Z"));
        t.actor(TYPIST).editForm(OrgType.class, UNIT).set(OPENED, null).submit().saves();
        t.org(UNIT).hasItem(OPENED);
        // into the empty field ("show empty fields"; the administrator: 4.8.14 fails it for the typist, see
        // ChoiceFormScenarioTest.anEmptyFieldOfAUserWhoMayChangeSomeItems)
        t.editForm(OrgType.class, UNIT).set(OPENED, Instant.parse("2027-01-02T03:04:00Z")).submit().saves();
        assertThat(opened(t)).isEqualTo(Instant.parse("2027-01-02T03:04:00Z"));
    }

    @Test
    void aDateWithSecondsFailsNamingTheMinute(MidPointConfigTester t) {
        // the GUI's date field takes hours and minutes: seconds would be dropped
        EditObjectForm form = form(t, null);
        assertThatThrownBy(() -> form.set(OPENED, Instant.parse("2026-03-05T10:15:30Z")).submit())
                .hasMessageContaining("Typed 2026-03-05T10:15:30Z into extension/opened, but its field holds"
                        + " 2026-03-05T10:15:00Z")
                .hasMessageContaining("takes a time to the minute, without seconds; pass 2026-03-05T10:15:00Z");
        assertThatThrownBy(() -> t.editForm(OrgType.class, UNIT).set(OPENED, 20260305L).submit())
                .hasMessageContaining("Cannot fill in extension/opened: its field takes a date and time");
        assertThat(opened(t)).isEqualTo(Instant.parse("2026-01-05T08:30:00Z"));
    }

    @Test
    void aDurationIsTypedAsItsText(MidPointConfigTester t) {
        form(t, null).set("extension/grace", "P2D").submit().saves();
        t.org(UNIT).hasItem("extension/grace", "P2D");
        t.actor(TYPIST).editForm(OrgType.class, UNIT).set("extension/grace", java.time.Duration.ofHours(36))
                .submit().saves();
        t.org(UNIT).hasItem("extension/grace", "PT36H");
        ObjectForm.Submit submit = t.editForm(OrgType.class, UNIT).set("extension/grace", "soon").submit();
        assertThat(submit.saved()).isFalse();
        if (t.line().equals("4.0") || t.line().equals("4.4")) {
            // the GUI's converter throws IllegalArgumentException, not a conversion error: Save opens its error page
            assertThat(String.join("; ", submit.messages())).contains("Saving opened the GUI's error page PageError:"
                    + " java.lang.IllegalArgumentException: soon");
        } else {
            assertThat(submit.rejected()).containsKey("extension/grace");
        }
        t.org(UNIT).hasItem("extension/grace", "PT36H");
    }

    @Test
    void aQNameIsAnObjectTypeChoice(MidPointConfigTester t) {
        // a QName item's field is a drop-down of the object types (DropDownChoicePanelFactory)
        form(t, null).set("extension/kind", "UserType").submit().saves();
        assertThat(t.org(UNIT).values("extension/kind")).singleElement().asString().endsWith("UserType");
        t.actor(TYPIST).editForm(OrgType.class, UNIT)
                .set("extension/kind", new QName("http://midpoint.evolveum.com/xml/ns/public/common/common-3",
                        "ServiceType"))
                .submit().saves();
        assertThat(t.org(UNIT).values("extension/kind")).singleElement().asString().endsWith("ServiceType");
        assertThatThrownBy(() -> t.editForm(OrgType.class, UNIT).set("extension/kind", "special").submit())
                .hasMessageContaining("Cannot fill in extension/kind: its field has no choice special")
                .hasMessageContaining("RoleType");
    }

    @Test
    void aStoredQNameNoChoiceHasFailsTheSave(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);
        t.midpoint().addObjectXml("<org " + Fixtures.NS + " xmlns:ext='http://example.com/xml/ns/tester-extension'>"
                + "<name>form-odd-unit</name><extension><ext:kind>ext:plain</ext:kind></extension></org>");

        // the drop-down has no choice of its value: the GUI fails converting the form on Save (its error page)
        ObjectForm.Submit submit = t.editForm(OrgType.class, "form-odd-unit").set("displayName", "Odd").submit();
        assertThat(submit.saved()).isFalse();
        assertThat(String.join("; ", submit.messages())).contains("Saving opened the GUI's error page PageError");
        assertThat(t.org("form-odd-unit").values("displayName")).isEmpty();
    }

    @Test
    void aLookupTableValueIsTypedByItsKeyOrLabel(MidPointConfigTester t) {
        form(t, null).set("extension/category", "south").submit().saves();
        t.org(UNIT).hasItem("extension/category", "south");
        t.actor(TYPIST).editForm(OrgType.class, UNIT).set("extension/category", "North wing").submit().saves();
        t.org(UNIT).hasItem("extension/category", "north");
    }

    @Test
    void textsPolyStringsAndMultiLineText(MidPointConfigTester t) {
        form(t, TYPIST).set("displayName", new PolyString("Typed unit 2")).set("description", "line one\nline two")
                .submit().saves();
        t.org(UNIT).hasItem("displayName", "Typed unit 2").hasItem("description", "line one\nline two");
        t.editForm(OrgType.class, UNIT).set("displayName", new PolyStringType("Typed unit 3")).set("description", null)
                .submit().saves();
        t.org(UNIT).hasItem("displayName", "Typed unit 3").hasItem("description");
        t.newObjectForm(OrgType.class).set("name", new PolyStringType("form-typed-unit-2")).set("extension/seats", 5)
                .set(OPENED, Instant.parse("2026-05-06T07:08:00Z")).submit().saves();
        t.org("form-typed-unit-2").hasItem("extension/seats", 5);
        assertThat(t.org("form-typed-unit-2").values(OPENED).stream().map(v -> {
            try {
                return DatatypeFactory.newInstance().newXMLGregorianCalendar(v).toGregorianCalendar().toInstant();
            } catch (javax.xml.datatype.DatatypeConfigurationException e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toList())).containsExactly(Instant.parse("2026-05-06T07:08:00Z"));
    }

    @Test
    void aReferenceIsNotTypedIntoItsChooser(MidPointConfigTester t) {
        EditObjectForm form = form(t, TYPIST);
        assertThatThrownBy(() -> form.set("extension/contact", "form-contact-2").submit())
                .hasMessageContaining("Cannot fill in extension/contact: it is a reference")
                .hasMessageContaining("chooser popup (ValueChoosePanel), which set does not drive")
                .hasMessageContaining("modify(");
        assertThatThrownBy(() -> t.editForm(OrgType.class, UNIT).set("parentOrgRef", "form-typed-unit").submit())
                .hasMessageContaining("Cannot fill in parentOrgRef: it is a reference");
        assertThat(t.org(UNIT).values("extension/contact")).containsExactly("10000000-0000-0000-0000-0000000f0602");
    }

    @Test
    void aPasswordIsNotTyped(MidPointConfigTester t) {
        t.midpoint().importObject(FIXTURE);

        assertThatThrownBy(() -> t.editForm(UserType.class, "form-contact-1")
                .set("credentials/password/value", "Secret.123").submit())
                .hasMessageContaining("Cannot fill in credentials/password/value: it is a protected string (a"
                        + " password)")
                .hasMessageContaining("m.password(");
    }
}
