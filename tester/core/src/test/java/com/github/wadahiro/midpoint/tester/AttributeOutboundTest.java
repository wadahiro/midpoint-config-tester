package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ModelExecuteOptionsType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.Effects;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.Modification;

/**
 * What each way of recomputing a user sends, for outbounds of each kind: a strong one midPoint can read back
 * ({@code mail}), a normal one ({@code sn}), a strong one to an attribute midPoint cannot read ({@code description})
 * and a strong password outbound. A recompute reads the account (the strong mappings ask for it; normal ones do not), adds back a strong
 * value someone removed and leaves a normal one as it is; a reconcile does the same and also sends the password. An
 * attribute midPoint cannot read is sent by every one of them, every time. The same on every line.
 */
@MidPointConfigTest(Fixtures.BARE)
class AttributeOutboundTest {

    static final File DIR = Fixtures.dir("provisioning");
    static final String JDOE = "uid=jdoe,ou=people,dc=example,dc=com";

    /** {@code ModelService.recompute} without options: the member panel's Recompute of one member (4.4+). */
    @Test
    void aRecomputeSendsWhatItCannotReadAndRestoresStrongValues(MidPointConfigTester t, @TempDir Path dir)
            throws Exception {
        withoutReconcile(t, dir, () -> t.user("jdoe").recompute());
    }

    /** {@code reconcileFocus} reconciles the user, not its projections: as a recompute. */
    @Test
    void reconcilingTheFocusIsARecomputeForTheAccounts(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        withoutReconcile(t, dir, () -> t.effectsOf(() -> t.midpoint().change(UserType.class, "jdoe")
                .options(ModelExecuteOptions.fromModelExecutionOptionsType(new ModelExecuteOptionsType().reconcileFocus(true)))
                .execute()));
    }

    /** {@code reconcile}: the user list's Reconcile, the details page's save with Reconcile. */
    @Test
    void aReconcileSendsThePasswordToo(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        withReconcile(t, dir, () -> t.user("jdoe").modify(Modification::reconcile));
    }

    /**
     * A recomputation task without options reconciles ("for compatibility"): the member panel's Recompute of several or
     * all members (4.4+).
     */
    @Test
    void aRecomputationTaskReconcilesByDefault(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        recomputeTask(t, dir, null);
        withReconcile(t, dir, () -> t.task("recompute users").run());
    }

    @Test
    void aRecomputationTaskWithoutReconcileIsARecompute(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        recomputeTask(t, dir, "<reconcile>false</reconcile>");
        withoutReconcile(t, dir, () -> t.task("recompute users").run());
    }

    /** With normal outbounds only, a recompute does not read the account (the repository shadow is enough). */
    @Test
    void aRecomputeOfNormalOutboundsDoesNotReadTheAccount(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        String xml = new String(Files.readAllBytes(new File(DIR, "resource-ldap.xml").toPath()), StandardCharsets.UTF_8)
                .replace("<strength>strong</strength>", "");
        File resource = dir.resolve("resource-ldap.xml").toFile();
        Files.write(resource.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        t.midpoint().importResource(resource, ResourceOptions.defaults());
        t.midpoint().addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName>"
                + "<familyName>Doe</familyName><assignment><construction>"
                + "<resourceRef oid='10000000-0000-0000-0000-00000000a002' type='ResourceType'/></construction>"
                + "</assignment></user>");
        t.midpoint().target("LDAP").modifyAccount(JDOE, "mail", "other@example.com");

        t.user("jdoe").recompute().connectorCalls().to("LDAP").none();
        Effects reconciled = t.user("jdoe").modify(Modification::reconcile);
        assertThat(reconciled.connectorCalls().to("LDAP").reads().of(JDOE).count()).isPositive();
        reconciled.connectorCalls().to("LDAP").writes().none();
    }

    private static void withoutReconcile(MidPointConfigTester t, Path dir, Supplier<Effects> verb) throws Exception {
        claims(t, dir, verb, "description");
    }

    private static void withReconcile(MidPointConfigTester t, Path dir, Supplier<Effects> verb) throws Exception {
        claims(t, dir, verb, "description", "credentials/password/value");
    }

    /** At rest, then after mail and sn were changed on the directory, twice each: what the verb writes. */
    private static void claims(MidPointConfigTester t, Path dir, Supplier<Effects> verb, String... everyTime)
            throws Exception {
        jdoeWithAnAccount(t, dir);
        TargetSystem ldap = t.midpoint().target("LDAP");
        for (int i = 0; i < 2; i++) {
            verb.get().connectorCalls().to("LDAP").writes().single().only(everyTime);
        }
        ldap.modifyAccount(JDOE, "mail", "other@example.com");
        ldap.modifyAccount(JDOE, "sn", "Other");
        verb.get().connectorCalls().to("LDAP").writes().single()
                .only(Stream.concat(Stream.of(everyTime), Stream.of("mail")).toArray(String[]::new));
        verb.get().connectorCalls().to("LDAP").writes().single().only(everyTime);
        // mail is multi-valued and tolerant: the strong value is added, the other one stays; sn is never restored
        assertThat(ldap.attributeValues(JDOE, "mail")).containsExactlyInAnyOrder("other@example.com", "jdoe@example.com");
        assertThat(ldap.attributeValues(JDOE, "sn")).containsExactly("Other");
    }

    /** A recurring recomputation of users, with these execution options (none: the default). */
    private static void recomputeTask(MidPointConfigTester t, Path dir, String options) throws Exception {
        String xml;
        if (t.midpoint().line().startsWith("4.0")) {
            // the recompute handler, its options in the task extension
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
     * The LDAP resource of {@code fixtures/provisioning} with a strong password outbound and a strong outbound of
     * fullName to description, which its schema lets midPoint create and update but not read; jdoe's account.
     */
    private static void jdoeWithAnAccount(MidPointConfigTester t, Path dir) throws Exception {
        String xml = new String(Files.readAllBytes(new File(DIR, "resource-ldap.xml").toPath()), StandardCharsets.UTF_8);
        int mail = xml.indexOf("                        <xsd:element maxOccurs=\"unbounded\" minOccurs=\"0\" name=\"mail\"");
        xml = xml.substring(0, mail)
                + "                        <xsd:element maxOccurs=\"unbounded\" minOccurs=\"0\" name=\"description\""
                + " type=\"xsd:string\"><xsd:annotation><xsd:appinfo><a:access>create</a:access>"
                + "<a:access>update</a:access><ra:returnedByDefault>false</ra:returnedByDefault>"
                + "<ra:nativeAttributeName>description</ra:nativeAttributeName>"
                + "<ra:frameworkAttributeName>description</ra:frameworkAttributeName>"
                + "</xsd:appinfo></xsd:annotation></xsd:element>\n" + xml.substring(mail);
        int association = xml.indexOf("            <association>");
        xml = xml.substring(0, association) + "            <attribute><ref>ri:description</ref><outbound>"
                + "<strength>strong</strength><source><path>fullName</path></source></outbound></attribute>\n"
                + xml.substring(association);
        int end = xml.indexOf("        </objectType>");
        xml = xml.substring(0, end) + "            <credentials><password><outbound><strength>strong</strength>"
                + "</outbound></password></credentials>\n" + xml.substring(end);
        File resource = dir.resolve("resource-ldap.xml").toFile();
        Files.write(resource.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        t.midpoint().importResource(resource, ResourceOptions.defaults());
        t.midpoint().addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName>"
                + "<familyName>Doe</familyName><assignment><construction>"
                + "<resourceRef oid='10000000-0000-0000-0000-00000000a002' type='ResourceType'/></construction>"
                + "</assignment><credentials><password><value>"
                + "<clearValue xmlns='http://prism.evolveum.com/xml/ns/public/types-3'>Secret1234</clearValue>"
                + "</value></password></credentials></user>");
    }
}
