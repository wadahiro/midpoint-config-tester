package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.github.wadahiro.midpoint.configtester.sample.access.Orgs;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * Roles, orgs and services are focuses as users are ({@link Focus}): the same checks, reads and verbs, and their
 * members ({@link AbstractRole}), over the sample deployment {@code scenarios/access}: jack is a member of the IT
 * department, it-manager its manager.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class FocusScenarioTest {

    @Test
    void aRoleIsAFocus(MidPointConfigTester t) {
        t.role("Auditor").recompute();                                 // 4.0 imports the roles raw: no activation yet
        t.role("Auditor").exists().hasItem("name", "Auditor").isEnabled();
        t.role(Roles.AUDITOR).exists();
        assertThat(t.role("Auditor").asObject().getName().getOrig()).isEqualTo("Auditor");
        assertThatThrownBy(() -> t.role("Nobody").exists())
                .isInstanceOf(AssertionError.class).hasMessageContaining("Expected a role Nobody");
        t.role("Nobody").doesNotExist();
        t.service("Nobody").doesNotExist();
        assertThatThrownBy(() -> t.role("Auditor").doesNotExist())
                .isInstanceOf(AssertionError.class).hasMessageContaining("Expected no role Auditor");
    }

    @Test
    void theMembersOfAnOrgAreThoseWithTheDefaultRelation(MidPointConfigTester t) {
        t.org(Orgs.IT).hasMember("jack").hasNoMember("ann").hasNoMember("it-manager");
        assertThat(t.org("IT").members()).contains("jack");
        t.user(Users.IT_MANAGER).hasRole(Orgs.IT);                     // assigned, as the manager
        assertThat(t.org("IT").parents()).isEmpty();
        assertThatThrownBy(() -> t.org("IT").hasMember("ann"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Org IT: expected a member ann");
    }

    @Test
    void aVerbOnARoleOrAUserWithARelation(MidPointConfigTester t) {
        t.user("ann").assign("Help desk", SchemaConstants.ORG_OWNER);
        t.user("ann").hasRole("Help desk");
        t.role("Help desk").hasNoMember("ann");                       // an owner is no member

        t.role("Auditor").recompute();                                 // as aRoleIsAFocus
        t.raw().role("Auditor").set("description", "legacy");
        t.role("Auditor").hasItem("description", "legacy");
        t.role("Auditor").recompute().none();
        t.role("Auditor").hasItem("description", "legacy");
    }
}
