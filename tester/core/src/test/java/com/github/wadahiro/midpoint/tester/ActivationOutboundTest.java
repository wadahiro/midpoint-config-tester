package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ModelExecuteOptionsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.Effects;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.Modification;

/**
 * An account disabled on the target, under each way of recomputing its user (as {@link AttributeOutboundTest} does for
 * attributes): a strong status outbound enables it again, on a recompute as on a reconcile, and makes the recompute read
 * the account even with normal attribute outbounds only; a normal one leaves it disabled, on a reconcile too. The same
 * for a native status and a status simulated in an attribute, on every line.
 */
@MidPointConfigTest(Fixtures.BARE)
class ActivationOutboundTest {

    static final File DIR = Fixtures.dir("provisioning");
    static final String JDOE = "uid=jdoe,ou=people,dc=example,dc=com";

    @ParameterizedTest(name = "{0} status, {1} outbound, {2}")
    @CsvSource({
            "native,strong,recompute", "native,strong,reconcileFocus", "native,strong,reconcile",
            "native,strong,task", "native,strong,taskNoReconcile",
            "simulated,strong,recompute", "simulated,strong,reconcile", "simulated,strong,task",
            "plain-native,strong,recompute", "plain-simulated,strong,recompute",
            "native,normal,recompute", "native,normal,reconcile", "native,normal,task",
            "simulated,normal,recompute", "simulated,normal,reconcile",
    })
    void anAccountDisabledOnTheTarget(String kind, String strength, String way, MidPointConfigTester t,
            @TempDir Path dir) throws Exception {
        resource(t, dir, kind, strength);
        if (way.startsWith("task")) {
            task(t, dir, way.equals("task") ? null : "<reconcile>false</reconcile>");
        }
        Supplier<Effects> verb = verb(t, way);
        TargetSystem ldap = t.midpoint().target("LDAP");
        verb.get().connectorCalls().to("LDAP").writes().none();
        ldap.setEnabled(JDOE, false);

        Effects first = verb.get();
        if (strength.equals("strong")) {
            assertThat(first.connectorCalls().to("LDAP").reads().of(JDOE).count()).isPositive();
            first.connectorCalls().to("LDAP").writes().single()
                    .only(kind.endsWith("simulated") ? "employeeType" : "activation/administrativeStatus");
            assertThat(ldap.enabled(JDOE)).isTrue();
        } else {
            first.connectorCalls().to("LDAP").writes().none();
            assertThat(ldap.enabled(JDOE)).isFalse();
        }
        verb.get().connectorCalls().to("LDAP").writes().none();
    }

    private static Supplier<Effects> verb(MidPointConfigTester t, String way) {
        switch (way) {
            case "recompute":
                return () -> t.user("jdoe").recompute();
            case "reconcileFocus":
                return () -> t.effectsOf(() -> t.midpoint().change(UserType.class, "jdoe")
                        .options(ModelExecuteOptions.fromModelExecutionOptionsType(
                                new ModelExecuteOptionsType().reconcileFocus(true)))
                        .execute());
            case "reconcile":
                return () -> t.user("jdoe").modify(Modification::reconcile);
            default:
                return () -> t.task("recompute users").run();
        }
    }

    /** A recurring recomputation of users, with these execution options (none: the default). */
    private static void task(MidPointConfigTester t, Path dir, String options) throws Exception {
        String xml;
        if (t.midpoint().line().startsWith("4.0")) {
            xml = "<task oid='10000000-0000-0000-0000-00000000f0a1' " + Fixtures.NS
                    + " xmlns:mext='http://midpoint.evolveum.com/xml/ns/public/model/extension-3'>"
                    + "<name>recompute users</name><extension><mext:objectType>UserType</mext:objectType>"
                    + (options == null ? "" : "<modelExecuteOptions>" + options + "</modelExecuteOptions>")
                    + "</extension><ownerRef oid='00000000-0000-0000-0000-000000000002' type='UserType'/>"
                    + "<executionStatus>runnable</executionStatus>"
                    + "<handlerUri>http://midpoint.evolveum.com/xml/ns/public/model/synchronization/task/recompute/handler-3</handlerUri>"
                    + "<recurrence>recurring</recurrence><binding>loose</binding>"
                    + "<schedule><interval>3600</interval></schedule></task>";
        } else {
            xml = "<task oid='10000000-0000-0000-0000-00000000f0a1' " + Fixtures.NS + "><name>recompute users</name>"
                    + "<ownerRef oid='00000000-0000-0000-0000-000000000002' type='UserType'/>"
                    + "<executionState>runnable</executionState>"
                    + "<schedule><recurrence>recurring</recurrence><interval>3600</interval></schedule>"
                    + "<activity><work><recomputation><objects><type>UserType</type></objects>"
                    + (options == null ? "" : "<executionOptions>" + options + "</executionOptions>")
                    + "</recomputation></work></activity></task>";
        }
        File file = dir.resolve("task.xml").toFile();
        Files.write(file.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        t.midpoint().importObject(file);
    }

    /**
     * The LDAP resource of {@code fixtures/provisioning} with an administrative status outbound of the strength, the
     * status native or simulated in employeeType; {@code plain-} without its strong attribute outbounds. jdoe's account.
     */
    private static void resource(MidPointConfigTester t, Path dir, String kind, String strength) throws Exception {
        String xml = new String(Files.readAllBytes(new File(DIR, "resource-ldap.xml").toPath()), StandardCharsets.UTF_8);
        if (kind.startsWith("plain-")) {
            // no strong or weak attribute outbound: nothing asks a recompute to read the account
            xml = xml.replace("<strength>strong</strength>", "");
            kind = kind.substring("plain-".length());
        }
        int end = xml.indexOf("        </objectType>");
        xml = xml.substring(0, end) + "            <activation><administrativeStatus><outbound><strength>" + strength
                + "</strength></outbound></administrativeStatus></activation>\n" + xml.substring(end);
        if (kind.equals("simulated")) {
            int mail = xml.indexOf("                        <xsd:element maxOccurs=\"unbounded\" minOccurs=\"0\" name=\"mail\"");
            xml = xml.substring(0, mail)
                    + "                        <xsd:element minOccurs=\"0\" name=\"employeeType\" type=\"xsd:string\">"
                    + "<xsd:annotation><xsd:appinfo><ra:nativeAttributeName>employeeType</ra:nativeAttributeName>"
                    + "<ra:frameworkAttributeName>employeeType</ra:frameworkAttributeName></xsd:appinfo></xsd:annotation>"
                    + "</xsd:element>\n" + xml.substring(mail);
            int close = xml.lastIndexOf("    <synchronization>");
            xml = xml.substring(0, close) + "    <capabilities><configured>"
                    + "<cap:activation xmlns:cap='http://midpoint.evolveum.com/xml/ns/public/resource/capabilities-3'>"
                    + "<cap:status><cap:attribute>ri:employeeType</cap:attribute><cap:enableValue>active</cap:enableValue>"
                    + "<cap:disableValue>disabled</cap:disableValue></cap:status></cap:activation>"
                    + "</configured></capabilities>\n" + xml.substring(close);
        }
        File resource = dir.resolve("resource-ldap.xml").toFile();
        Files.write(resource.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        t.midpoint().importResource(resource, ResourceOptions.defaults());
        t.midpoint().addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName>"
                + "<familyName>Doe</familyName><assignment><construction>"
                + "<resourceRef oid='10000000-0000-0000-0000-00000000a002' type='ResourceType'/></construction>"
                + "</assignment></user>");
    }
}
