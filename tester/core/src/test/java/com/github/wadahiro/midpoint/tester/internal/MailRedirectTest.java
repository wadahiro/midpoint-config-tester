package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The redirect of the mail servers of a system configuration on its own (no midPoint needed). */
class MailRedirectTest {

    private static final String NS = " xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'"
            + " xmlns:t='http://prism.evolveum.com/xml/ns/public/types-3'";
    private static final Path FILES = Path.of("/work/tester-mail/files");

    private static MailRedirect.Result apply(String xml) {
        return MailRedirect.apply(xml, "127.0.0.1", 2525, FILES);
    }

    @Test
    void messageTransportServersPointAtTheServerWithoutTlsAndCredentials() {
        MailRedirect.Result r = apply("<systemConfiguration" + NS + "><name>S</name>"
                + "<messageTransportConfiguration>"
                + "<mail><name>corp</name><server><host>smtp.example.com</host><port>587</port>"
                + "<username>mp</username><password><t:clearValue>x</t:clearValue></password>"
                + "<transportSecurity>starttlsRequired</transportSecurity></server>"
                + "<server><host>backup.example.com</host><basicAuthentication><username>u</username>"
                + "</basicAuthentication><oauth2Authentication/></server>"
                + "<defaultFrom>mp@example.com</defaultFrom></mail>"
                + "<sms><name>sms</name></sms></messageTransportConfiguration></systemConfiguration>");

        assertThat(r.changed()).isTrue();
        assertThat(r.xml).contains("<server><host>127.0.0.1</host><port>2525</port></server>"
                + "<server><host>127.0.0.1</host><port>2525</port></server>"
                + "<defaultFrom>mp@example.com</defaultFrom>");
        assertThat(r.xml).doesNotContain("username", "password", "transportSecurity", "Authentication");
        assertThat(r.xml).contains("<sms><name>sms</name></sms>");
        assertThat(r.notes).containsExactly("messageTransportConfiguration/mail[corp]: 2 server(s) redirected to "
                + "127.0.0.1:2525");
    }

    @Test
    void legacyNotificationMailInAnObjectsFile() {
        MailRedirect.Result r = apply("<objects" + NS + "><user><name>mail</name></user>"
                + "<systemConfiguration><name>S</name><notificationConfiguration><handler/>"
                + "<mail><redirectToFile>/var/log/mp/mail.txt</redirectToFile><logToFile>mails.log</logToFile>"
                + "<server><host>smtp.example.com</host></server></mail>"
                + "</notificationConfiguration></systemConfiguration></objects>");

        assertThat(r.xml).contains("<redirectToFile>" + FILES.resolve("mail.txt") + "</redirectToFile>"
                + "<logToFile>" + FILES.resolve("mails.log") + "</logToFile>"
                + "<server><host>127.0.0.1</host><port>2525</port></server>");
        assertThat(r.notes).hasSize(3);
        assertThat(r.notes.get(1)).contains("redirectToFile moved to").contains("not to mails()");
    }

    /** As midPoint exports objects: {@code <object xsi:type="c:SystemConfigurationType">}. */
    @Test
    void anObjectOfTheSystemConfigurationType() {
        MailRedirect.Result r = apply("<object" + NS + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
                + " xsi:type='SystemConfigurationType'><name>S</name><messageTransportConfiguration>"
                + "<mail><name>corp</name><server><host>smtp.example.com</host></server></mail>"
                + "</messageTransportConfiguration></object>");

        assertThat(r.xml).contains("<server><host>127.0.0.1</host><port>2525</port></server>");
    }

    @Test
    void otherObjectsAndConfigurationsWithoutMailAreLeftAlone() {
        String user = "<user" + NS + "><name>mail</name><emailAddress>a@example.com</emailAddress></user>";
        assertThat(apply(user).xml).isSameAs(user);
        String config = "<systemConfiguration" + NS + "><name>mail</name><notificationConfiguration><handler/>"
                + "</notificationConfiguration></systemConfiguration>";
        MailRedirect.Result r = apply(config);
        assertThat(r.changed()).isFalse();
        assertThat(r.xml).isSameAs(config);
    }

    @Test
    void aMailTransportWithoutServerIsReported() {
        MailRedirect.Result r = apply("<systemConfiguration" + NS + "><messageTransportConfiguration>"
                + "<mail><name>m</name></mail></messageTransportConfiguration></systemConfiguration>");

        assertThat(r.notes).containsExactly("messageTransportConfiguration/mail[m]: 0 server(s) redirected to "
                + "127.0.0.1:2525 (none configured: midPoint sends no mail)");
    }

    @Test
    void theServersThatAreNotTheTesters() {
        String xml = "<systemConfiguration xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                + "<notificationConfiguration><mail><server><host>127.0.0.1</host></server></mail>"
                + "</notificationConfiguration>"
                + "<messageTransportConfiguration><mail><name>m</name><server><host>smtp.example.com</host></server>"
                + "<server></server></mail></messageTransportConfiguration></systemConfiguration>";
        org.assertj.core.api.Assertions.assertThat(MailRedirect.otherServers(xml, "127.0.0.1")).containsExactly(
                "messageTransportConfiguration/mail: smtp.example.com", "messageTransportConfiguration/mail: (no host)");
    }
}
