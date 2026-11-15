package com.github.wadahiro.midpoint.tester;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.wadahiro.midpoint.configtester.Claims;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.Modification;

/**
 * A password outbound and the law that a recompute changes nothing ({@code Changes.focuses()}): midPoint cannot read
 * the account's password, so a strong mapping sends it on every reconcile of the user, a normal one only when the
 * password changes. A recompute (without reconcile) sends neither. The same on every line.
 */
@MidPointConfigTest(Fixtures.BARE)
class PasswordOutboundTest {

    static final File DIR = Fixtures.dir("provisioning");
    static final String JDOE = "uid=jdoe,ou=people,dc=example,dc=com";

    @Test
    void aStrongPasswordMappingSendsThePasswordOnEveryReconcile(MidPointConfigTester t, @TempDir Path dir)
            throws Exception {
        jdoeWithAnAccount(t, dir, "strong");

        t.user("jdoe").recompute().none();
        for (int i = 0; i < 2; i++) {
            Claims claims = t.user("jdoe").modify(Modification::reconcile).claims();
            claims.connectorCalls().to("LDAP").modifies().of(JDOE).single().only("credentials/password/value");
            claims.verifyAll();
        }
    }

    @Test
    void aNormalPasswordMappingSendsThePasswordWhenItChanges(MidPointConfigTester t, @TempDir Path dir)
            throws Exception {
        jdoeWithAnAccount(t, dir, "normal");

        t.user("jdoe").recompute().none();
        t.user("jdoe").modify(Modification::reconcile).none();
        t.user("jdoe").modify(m -> m.password("Changed1234")).connectorCalls().to("LDAP").modifies().of(JDOE)
                .single().only("credentials/password/value");
        t.user("jdoe").modify(Modification::reconcile).none();
    }

    /** The LDAP resource of {@code fixtures/provisioning} with a password outbound of the strength, and jdoe's account. */
    private static void jdoeWithAnAccount(MidPointConfigTester t, Path dir, String strength) throws Exception {
        MidPoint mp = t.midpoint();
        String xml = new String(Files.readAllBytes(new File(DIR, "resource-ldap.xml").toPath()), StandardCharsets.UTF_8);
        int end = xml.indexOf("        </objectType>");
        xml = xml.substring(0, end) + "            <credentials><password><outbound><strength>" + strength
                + "</strength></outbound></password></credentials>\n" + xml.substring(end);
        File resource = dir.resolve("resource-ldap.xml").toFile();
        Files.write(resource.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        mp.importResource(resource, ResourceOptions.defaults());
        mp.addObjectXml("<user " + Fixtures.NS + "><name>jdoe</name><fullName>John Doe</fullName>"
                + "<familyName>Doe</familyName><assignment><construction>"
                + "<resourceRef oid='10000000-0000-0000-0000-00000000a002' type='ResourceType'/></construction>"
                + "</assignment><credentials><password><value>"
                + "<clearValue xmlns='http://prism.evolveum.com/xml/ns/public/types-3'>Secret1234</clearValue>"
                + "</value></password></credentials></user>");
    }
}
