package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.wadahiro.midpoint.tester.ResourceOptions;

/** The manifest as read, on its own (no midPoint needed). */
class ManifestTest {

    @TempDir
    Path dir;

    @Test
    void textsReplacedAsTheyAreInTheManifestsOrder() throws IOException {
        Manifest m = read("config:\n  dirs: [objects]\n  replace:\n    \"@HOST@\": mail.example.com\n"
                + "    \"<!--TEST-->\": \"\"\n");

        assertThat(m.replace).containsExactly(java.util.Map.entry("@HOST@", "mail.example.com"),
                java.util.Map.entry("<!--TEST-->", ""));
    }

    @Test
    void namingAttributesByObjectClassAndAttributesSchemaHandlingDoesNotName() throws IOException {
        Manifest m = read("config:\n  dirs: [objects]\ntargets:\n"
                + "  SCIM: { namingAttribute: { User: userName, Group: displayName },"
                + " attributes: { User: [category, region] }, multiValued: { Group: [tags] },"
                + " keyedValues: { Group: { labels: \"=\" } }, store: { User: { emails: \"values + add\" } } }\n"
                + "  CRM: { namingAttribute: login }\n");

        ResourceOptions scim = m.targets.get("SCIM").options;
        assertThat(scim.namingAttribute()).isNull();
        assertThat(scim.namingAttributeOf("User")).isEqualTo("userName");
        assertThat(scim.namingAttributeOf("Group")).isEqualTo("displayName");
        assertThat(scim.attributes()).extracting(a -> a[0] + "/" + a[1]).containsExactly("User/category",
                "User/region");
        assertThat(scim.storedValues()).extracting(a -> a.objectClass + "/" + a.attribute).containsExactly(
                "Group/tags", "Group/labels", "User/emails");
        assertThat(scim.storedValues().get(1).keySeparator).isEqualTo("=");
        assertThat(scim.storedValues().get(2).expression).isEqualTo("values + add");
        assertThat(m.targets.get("CRM").options.namingAttributeOf("AccountObjectClass")).isEqualTo("login");
    }

    @Test
    void anIdentifierTheTargetSystemIssues() throws IOException {
        Manifest m = read("config:\n  dirs: [objects]\ntargets:\n"
                + "  Certs: { namingAttribute: certName, identifier: { attribute: certId, value: \"name + '/1'\" } }\n"
                + "  CRM: { identifier: crmId }\n");

        assertThat(m.targets.get("Certs").options.identifier()).isEqualTo("certId");
        assertThat(m.targets.get("Certs").options.identifierValue()).isEqualTo("name + '/1'");
        assertThat(m.targets.get("CRM").options.identifierValue()).isNull();
        assertThatThrownBy(() -> read("config:\n  dirs: [objects]\ntargets:\n"
                + "  Certs: { identifier: { attribute: certId } }\n"))
                .hasMessageContaining("targets.Certs.identifier: attribute and value");
    }

    @Test
    void midPointsConstants() throws IOException {
        assertThat(read("config:\n  dirs: [objects]\nconstants: { tenant: 13, host: a.example.com }\n").constants)
                .containsExactly(java.util.Map.entry("tenant", "13"), java.util.Map.entry("host", "a.example.com"));
        assertThat(read("config:\n  dirs: [objects]\n").constants).isEmpty();
    }

    @Test
    void httpServicesTheTesterStandsInFor() throws IOException {
        Manifest m = read("config:\n  dirs: [objects]\nhttp:\n  redirect: [\"https://hooks.example.com\"]\n");
        assertThat(m.httpRedirect).containsExactly("https://hooks.example.com");
        assertThat(read("config:\n  dirs: [objects]\n").httpRedirect).isEmpty();
        assertThatThrownBy(() -> read("config:\n  dirs: [objects]\nhttp: { redirect: [hooks.example.com] }\n"))
                .hasMessageContaining("http.redirect: a URL prefix with a scheme and a host");
    }

    @Test
    void anEmptyTextCannotBeReplaced() throws IOException {
        assertThatThrownBy(() -> read("config:\n  dirs: [objects]\n  replace: { \"\": x }\n"))
                .hasMessageContaining("config.replace: an empty text cannot be replaced");
    }

    @Test
    void theRemovedTasksKeySaysToDeleteIt() {
        assertThatThrownBy(() -> read("config:\n  dirs: [objects]\ntasks: { recurring: held }\n"))
                .hasMessageContaining("tasks: removed, delete it");
    }

    private Manifest read(String yaml) throws IOException {
        Files.createDirectories(dir.resolve("objects"));
        Path file = dir.resolve("midpoint-config-tester.yaml");
        Files.writeString(file, yaml);
        return Manifest.read(file);
    }
}
