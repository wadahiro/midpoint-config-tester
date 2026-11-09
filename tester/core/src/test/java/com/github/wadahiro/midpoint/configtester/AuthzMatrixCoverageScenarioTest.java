package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * Whether the samples of an authorization matrix are on both sides of the personas' selectors
 * ({@link AuthzMatrixTest#coverage}), over the sample deployment {@code scenarios/access}: as midPoint selects them
 * for each persona, by a filter, {@code special: self} and {@code orgRelation}; a selector with no sample outside it
 * is a warning.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class AuthzMatrixCoverageScenarioTest {

    private static final String SPEC = String.join("\n",
            "spec: authz-matrix/1",
            "personas:",
            "  help-desk: {roles: [Help desk]}",
            "  it-manager: {orgs: [{org: IT, relation: manager}]}",
            "objects:",
            "  it-user: {user: jack}",
            "  other-user: {user: ann}",
            "  requestable-role: {role: Auditor}",
            "  other-role: {role: Accountant}",
            "rows:",
            "  - {group: it-user, object: it-user, rows: [{check: get}]}",
            "default: N",
            "");

    @TempDir
    Path dir;

    @Test
    void theSamplesAreOnBothSidesOfTheSelectors(MidPointConfigTester t) throws IOException {
        List<AuthzMatrixTest.Coverage> coverage = coverage(t, SPEC);

        // a filter (costCenter IT): the help desk user has none
        assertThat(find(coverage, "help-desk", "'read-it-users'", "object"))
                .satisfies(c -> assertThat(c.inside).containsExactly("it-user"))
                .satisfies(c -> assertThat(c.outside).containsExactlyInAnyOrder("self", "other-user"));
        // special: self, of any type
        assertThat(find(coverage, "help-desk", "'self-read'", "object"))
                .satisfies(c -> assertThat(c.inside).containsExactly("self"))
                .satisfies(c -> assertThat(c.outside).contains("it-user", "other-user", "requestable-role"));
        // orgRelation: the members of IT, the manager among them
        assertThat(find(coverage, "it-manager", "'read-members'", "object"))
                .satisfies(c -> assertThat(c.inside).containsExactlyInAnyOrder("self", "it-user"))
                .satisfies(c -> assertThat(c.outside).containsExactly("other-user"));
        // a target's filter (requestable)
        assertThat(find(coverage, "it-manager", "'assign-members-requestable-roles'", "target"))
                .satisfies(c -> assertThat(c.inside).containsExactly("requestable-role"))
                .satisfies(c -> assertThat(c.outside).containsExactly("other-role"));
        assertThat(coverage).extracting(c -> c.warning).containsOnlyNulls();
    }

    /**
     * The authorizations as midPoint compiled them at login: the manager of IT has the Department manager role's
     * from the org's inducement to its managers, its member jack has not.
     */
    @Test
    void theAuthorizationsAreAsMidPointCompiledThem(MidPointConfigTester t) {
        assertThat(t.as(Users.IT_MANAGER).compiledAuthorizations())
                .extracting(c -> Actor.source(c.source()) + " " + c.authorization().getName())
                .contains("role Department manager read-members", "role End user self-read");
        assertThat(t.as(Users.JACK).compiledAuthorizations())
                .extracting(c -> Actor.source(c.source()))
                .doesNotContain("role Department manager");
    }

    @Test
    void aSelectorWithNoSampleOutsideIsAWarning(MidPointConfigTester t) throws IOException {
        List<AuthzMatrixTest.Coverage> coverage = coverage(t, SPEC.replace("  other-role: {role: Accountant}\n", ""));

        assertThat(find(coverage, "it-manager", "'assign-members-requestable-roles'", "target").warning)
                .isEqualTo("persona it-manager: 'assign-members-requestable-roles' (role Department manager) target"
                        + " RoleType with a filter: no sample outside it (requestable-role inside); add an object it"
                        + " does not select");
    }

    private List<AuthzMatrixTest.Coverage> coverage(MidPointConfigTester t, String spec) throws IOException {
        Path file = dir.resolve("authz-matrix.yaml");
        Files.writeString(file, spec);
        AuthzMatrixSpec read = AuthzMatrixSpec.read(file, t.line());
        Map<String, String> users = AuthzMatrixTest.check(read, t);
        return AuthzMatrixTest.coverage(read, t, users);
    }

    private static AuthzMatrixTest.Coverage find(List<AuthzMatrixTest.Coverage> coverage, String persona,
            String authorization, String side) {
        return coverage.stream()
                .filter(c -> c.persona.equals(persona) && c.authorization.startsWith(authorization)
                        && c.side.equals(side))
                .findFirst().orElseThrow(() -> new AssertionError("No coverage of " + authorization + " " + side
                        + " for " + persona + ": " + coverage));
    }
}
