package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The authorization matrix as read ({@link AuthzMatrixSpec}) and a cell's failure, without midPoint. */
class AuthzMatrixSpecTest {

    private static final String SPEC = String.join("\n",
            "spec: authz-matrix/1",
            "personas:",
            "  end-user: {label: End user}",
            "  help-desk: {label: Help desk, roles: [Help desk]}",
            "objects:",
            "  it-user: {user: jack, label: A user of IT}",
            "rows:",
            "  - group: pages",
            "    rows: [{id: users, check: open /admin/users}]",
            "  - group: it-user",
            "    object: it-user",
            "    rows:",
            "      - {check: get}",
            "      - {id: modify-phone, check: modify telephoneNumber}",
            "default: N",
            "rules:",
            "  - {name: all-get, for: \"*\", rows: [it-user/get], value: Y, note: the End user gets anyone}",
            "  - {name: help-desk, for: [help-desk], rows: [pages, it-user/modify-*], value: {default: N, \"4.8\": Y}}",
            "critical: [it-user/modify-phone]",
            "");

    /** midPoint's part, for the auto rows. */
    private static final AuthzMatrixSpec.Expander EXPANDER = new AuthzMatrixSpec.Expander() {
        @Override
        public List<String[]> pages() {
            return List.of(new String[] {"/admin/users", "com.example.PageUsers"},
                    new String[] {"/admin/user/**", "EndPointsUrlMapping.USER_DETAILS"},
                    new String[] {"/self/dashboard", "com.example.PageSelfDashboard"});
        }

        @Override
        public List<String> items(String type) {
            return List.of("name", "telephoneNumber");
        }

        @Override
        public List<String[]> views() {
            return List.of(new String[] {"UserType", "people", "People"},
                    new String[] {"UserType", "my-department", null});
        }

        @Override
        public List<String> features() {
            return List.of("orgTreeExpandAll");
        }

        @Override
        public List<String> menuLinks() {
            return List.of("Service desk");
        }
    };

    @TempDir
    Path dir;

    @Test
    void theRulesGiveEachCellItsValueTheLastWinning() throws IOException {
        AuthzMatrixSpec spec = read(SPEC, "4.8");

        assertThat(spec.checks()).extracting(row -> row.path)
                .containsExactly("pages/users", "it-user/get", "it-user/modify-phone");
        assertThat(expected(spec, "end-user", "it-user/get")).isEqualTo("Y");
        assertThat(expected(spec, "end-user", "pages/users")).isEqualTo("N");
        assertThat(expected(spec, "help-desk", "pages/users")).isEqualTo("Y");
        assertThat(expected(spec, "help-desk", "it-user/modify-phone")).isEqualTo("Y");
        assertThat(read(SPEC, "4.4").expected("help-desk", row(spec, "pages/users"))[0])
                .isEqualTo("N");
        assertThat(spec.critical(row(spec, "it-user/modify-phone"))).isTrue();
    }

    @Test
    void mistakesAreReportedWithTheirLine() {
        assertThatThrownBy(() -> read(SPEC.replace("value: Y, note", "value: yes, note"), "4.8"))
                .hasMessage("authz-matrix.yaml:17: a value is Y or N, not yes");
        assertThatThrownBy(() -> read(SPEC.replace("[it-user/get]", "[it-user/gett]"), "4.8"))
                .hasMessageStartingWith("authz-matrix.yaml:17: no row matches it-user/gett");
        assertThatThrownBy(() -> read(SPEC.replace("for: [help-desk]", "for: [helpdesk]"), "4.8"))
                .hasMessageStartingWith("authz-matrix.yaml:18: unknown persona helpdesk");
        assertThatThrownBy(() -> read(SPEC.replace("check: get", "check: fetch"), "4.8"))
                .hasMessageStartingWith("authz-matrix.yaml:13: unknown check fetch");
        assertThatThrownBy(() -> read(SPEC.replace("\ndefault: N\n", "\ndefault: N\nextra: 1\n"), "4.8"))
                .hasMessageStartingWith("authz-matrix.yaml:16: unknown key extra");
        assertThatThrownBy(() -> read(SPEC.replace("value: Y, note", "value: Y note: x,"), "4.8"))
                .hasMessageStartingWith("authz-matrix.yaml:17: not YAML: ");
        assertThatThrownBy(() -> read(SPEC.replace("object: it-user", "object: it-usr"), "4.8"))
                .hasMessageStartingWith("authz-matrix.yaml:11: unknown object it-usr");
    }

    @Test
    void autoAddsThePagesTheActionsAndTheItems() throws IOException {
        String auto = String.join("\n",
                "spec: authz-matrix/1",
                "personas: {end-user: {label: End user}}",
                "objects: {it-user: {user: jack}}",
                "rows:",
                "  - {group: pages, auto: pages}",
                "  - {group: it-user, object: it-user, auto: [get, delete, items]}",
                "default: N",
                "rules:",
                "  - {for: \"*\", rows: [pages/self, it-user/items/read], value: Y}",
                "  - {for: \"*\", rows: [pages/admin/roles], value: Y}",
                "");
        AuthzMatrixSpec spec = read(auto, "4.8");

        assertThat(spec.checks()).extracting(row -> row.path + " " + row.check).containsExactly(
                "pages/admin/users open /admin/users",
                "pages/admin/user-any open /admin/user/00000000-0000-0000-0000-000000000000",
                "pages/self/dashboard open /self/dashboard",
                "it-user/get get", "it-user/delete delete",
                "it-user/items/read/name read name", "it-user/items/read/telephoneNumber read telephoneNumber",
                "it-user/items/modify/name modify name",
                "it-user/items/modify/telephoneNumber modify telephoneNumber");
        assertThat(expected(spec, "end-user", "pages/self/dashboard")).isEqualTo("Y");
        assertThat(expected(spec, "end-user", "it-user/items/read/name")).isEqualTo("Y");
        assertThat(expected(spec, "end-user", "it-user/items/modify/name")).isEqualTo("N");
        // pages differ between lines: a rule for a page this line has not is a warning
        assertThat(spec.warnings).containsExactly("authz-matrix.yaml:10: no row matches pages/admin/roles");
    }

    @Test
    void assignRowsHaveATargetAndARelation() throws IOException {
        String assign = String.join("\n",
                "spec: authz-matrix/1",
                "personas:",
                "  manager: {roles: [Department manager], orgs: [IT, {org: Sales, relation: manager}]}",
                "objects:",
                "  it-user: {user: jack}",
                "  auditor: {role: Auditor, label: Auditor}",
                "  it-org: {org: IT, label: IT}",
                "rows:",
                "  - group: it-user",
                "    object: it-user",
                "    auto: [assign]",
                "    rows: [{id: own, check: unassign auditor as owner}]",
                "default: N",
                "");
        AuthzMatrixSpec spec = read(assign, "4.8");

        assertThat(spec.personas.get("manager").orgs).extracting(Object::toString)
                .containsExactly("IT", "Sales (manager)");
        // auto: every role, org, service, archetype of the objects, with the default relation
        assertThat(spec.checks()).extracting(row -> row.path + " " + row.check).containsExactly(
                "it-user/assign/auditor assign auditor", "it-user/assign/it-org assign it-org",
                "it-user/own unassign auditor as owner");
        AuthzMatrixSpec.Row own = row(spec, "it-user/own");
        assertThat(own.kind).isEqualTo(AuthzMatrixSpec.Kind.UNASSIGN);
        assertThat(own.argument).isEqualTo("auditor");
        assertThat(own.relation).isEqualTo("owner");

        // as written: the targets and relations
        AuthzMatrixSpec targets = read(assign.replace("rows:\n", "assignment-targets: [auditor, {object: auditor,"
                + " relation: owner}]\nrows:\n"), "4.8");
        assertThat(targets.checks()).extracting(row -> row.path).containsExactly(
                "it-user/assign/auditor", "it-user/assign/auditor-owner", "it-user/own");
        assertThat(row(targets, "it-user/assign/auditor-owner").relation).isEqualTo("owner");

        assertThatThrownBy(() -> read(assign.replace("unassign auditor", "unassign nobody"), "4.8"))
                .hasMessageStartingWith("authz-matrix.yaml:12: unassign needs an object to assign");
        assertThatThrownBy(() -> read(assign.replace("rows:\n", "assignment-targets: [auditor, auditor]\nrows:\n"),
                "4.8")).hasMessageStartingWith("authz-matrix.yaml:8: assignment target auditor is listed twice");
    }

    @Test
    void guiRowsAreTheViewsFeaturesAndMenuLinksOfTheProfiles() throws IOException {
        String gui = String.join("\n",
                "spec: authz-matrix/1",
                "personas:",
                "  end-user: {label: End user}",
                "rows:",
                "  - group: gui",
                "    auto: [views, features, menu-links]",
                "    rows: [{id: tasks, check: view TaskType/running}]",
                "default: N",
                "rules:",
                "  - {for: \"*\", rows: [gui/views/UserType/*], value: Y}",
                "");
        AuthzMatrixSpec spec = read(gui, "4.8");

        assertThat(spec.checks()).extracting(row -> row.path + " " + row.check).containsExactly(
                "gui/views/UserType/people view UserType/people",
                "gui/views/UserType/my-department view UserType/my-department",
                "gui/features/orgTreeExpandAll feature orgTreeExpandAll",
                "gui/menu-links/Service-desk menu-link Service desk",
                "gui/tasks view TaskType/running");
        assertThat(row(spec, "gui/views/UserType/people").label).isEqualTo("people (People)");
        assertThat(row(spec, "gui/menu-links/Service-desk").kind).isEqualTo(AuthzMatrixSpec.Kind.MENU_LINK);
        assertThat(row(spec, "gui/menu-links/Service-desk").object).isNull();
        assertThat(expected(spec, "end-user", "gui/views/UserType/my-department")).isEqualTo("Y");

        assertThatThrownBy(() -> read(gui.replace("view TaskType/running", "view running"), "4.8"))
                .hasMessageContaining("authz-matrix.yaml:7").hasMessageContaining("view UserType/people");
    }

    @Test
    void aCellNotAsExpectedFailsWithItsRuleAndTheAuthorizations() throws IOException {
        AuthzMatrixSpec spec = read(SPEC, "4.8");
        AuthzMatrixTest.Cell cell = new AuthzMatrixTest.Cell(spec.personas.get("end-user"),
                row(spec, "it-user/get"), "Y", spec.rules.get(0), false);
        cell.actual = "N";

        assertThatThrownBy(() -> AuthzMatrixTest.verify(cell, Map.of("end-user", "Authorizations of authz-end-user"),
                dir.resolve("authz-matrix-4.8.html")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("End user / it-user/get (get): expected Y but was N")
                .hasMessageContaining("expected by rule 'all-get' (line 17)")
                .hasMessageContaining("the rule's note: the End user gets anyone")
                .hasMessageContaining("Authorizations of authz-end-user");
        cell.actual = "Y";
        AuthzMatrixTest.verify(cell, Map.of(), dir.resolve("authz-matrix-4.8.html"));
    }

    private AuthzMatrixSpec read(String content, String line) throws IOException {
        Path file = dir.resolve("authz-matrix.yaml");
        Files.writeString(file, content);
        return AuthzMatrixSpec.read(file, line).expand(EXPANDER);
    }

    private static String expected(AuthzMatrixSpec spec, String persona, String path) {
        return (String) spec.expected(persona, row(spec, path))[0];
    }

    private static AuthzMatrixSpec.Row row(AuthzMatrixSpec spec, String path) {
        List<AuthzMatrixSpec.Row> rows = spec.checks();
        return rows.stream().filter(row -> row.path.equals(path)).findFirst().orElseThrow();
    }
}
