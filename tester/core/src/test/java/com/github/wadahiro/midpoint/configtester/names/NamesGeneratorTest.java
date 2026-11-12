package com.github.wadahiro.midpoint.configtester.names;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The generator of typed names, without midPoint: identifiers, lines, collisions, incremental writing. */
class NamesGeneratorTest {

    @TempDir
    Path dir;

    @Test
    void identifiers() {
        assertThat(NamesGenerator.constant("Dept Sales")).isEqualTo("DEPT_SALES");
        assertThat(NamesGenerator.constant("firstName")).isEqualTo("FIRST_NAME");
        assertThat(NamesGenerator.constant("role-owner")).isEqualTo("ROLE_OWNER");
        assertThat(NamesGenerator.constant("e1001")).isEqualTo("E1001");
        assertThat(NamesGenerator.constant("1st line")).isEqualTo("_1ST_LINE");
        assertThat(NamesGenerator.className("LDAP")).isEqualTo("Ldap");
        assertThat(NamesGenerator.className("Active Directory")).isEqualTo("ActiveDirectory");
    }

    @Test
    void namesOfSomeLinesCarryTheirLines() throws IOException {
        write("post/010-roles.xml", "<objects xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\">"
                + "<role oid=\"r1\"><name>Everywhere</name></role></objects>");
        write("post/020-old.xml", role("Not on 4.8"));
        write("over48/030-new.xml", role("Only {{suffix}}"));
        write("midpoint-config-tester.yaml", "config:\n  dirs: [post]\n  properties: { suffix: on 4.8 }\n"
                + "  overlays:\n    \"4.8\": { dirs: [over48], remove: [020-old.xml] }\n"
                + "names: { package: com.example.names }\n");

        Path out = dir.resolve("out");
        List<String> written = NamesGenerator.generate(out, List.of(dir));
        String roles = read(out.resolve("com/example/names/Roles.java"));
        assertThat(roles).contains("RoleRef EVERYWHERE = new RoleRef(\"Everywhere\", \"r1\", Lines.ALL)")
                .contains("RoleRef NOT_ON_4_8 = new RoleRef(\"Not on 4.8\", null, Lines.except(\"4.8\"))")
                .contains("RoleRef ONLY_ON_4_8 = new RoleRef(\"Only on 4.8\", null, Lines.only(\"4.8\"))");
        assertThat(read(out.resolve("com/example/names/Names.java"))).contains("all.add(Roles.EVERYWHERE);");
        assertThat(written).hasSize(2);

        // nothing changed: nothing written; a stale file is removed
        write("out/com/example/names/Orgs.java", "// stale");
        assertThat(NamesGenerator.generate(out, List.of(dir))).isEmpty();
        assertThat(out.resolve("com/example/names/Orgs.java")).doesNotExist();
    }

    @Test
    void aClassWrittenInTheOtherUnicodeFormStays() throws IOException {
        // a JDK writes a name with a kana with dakuten decomposed (NFD) on macOS (JDK 11), another composed (NFC, JDK
        // 17+): the class the generator writes again must not be taken for a stale one
        String composed = "\u30d1\u30fc\u30c8\u30ca\u30fcAttrs.java";
        Path names = Files.createDirectories(dir.resolve("com/example/names"));
        Files.writeString(names.resolve(Normalizer.normalize(composed, Normalizer.Form.NFD)), "// earlier");
        NamesGenerator.write(dir, Map.of(names.resolve(composed), "class A {}"));
        assertThat(read(names.resolve(composed))).isEqualTo("class A {}");
        try (Stream<Path> files = Files.list(names)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void twoNamesForOneConstantFail() throws IOException {
        write("post/010-a.xml", role("Dept-Sales"));
        write("post/020-b.xml", role("Dept Sales"));
        write("midpoint-config-tester.yaml", "config: { dirs: [post] }\nnames: { package: com.example.names }\n");
        assertThatThrownBy(() -> NamesGenerator.generate(dir.resolve("out"), List.of(dir)))
                .hasMessageContaining("Roles.DEPT_SALES would name both 'Dept Sales' and 'Dept-Sales'");
    }

    @Test
    void lineAvailability() {
        RoleRef role = new RoleRef("x", null, Lines.except("4.0"));
        assertThat(role.existsOn("4.8")).isTrue();
        assertThatThrownBy(() -> role.on("4.0")).isInstanceOf(AssertionError.class)
                .hasMessage("role x is not in the deployment on midPoint 4.0 (not on 4.0 of the configuration)");
    }

    private static String role(String name) {
        return "<role xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\"><name>" + name
                + "</name></role>";
    }

    private void write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
