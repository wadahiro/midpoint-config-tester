package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.SecurityPolicyType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ValuePolicyType;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialPolicies;
import com.github.wadahiro.midpoint.configtester.names.midpoint.InitialRoles;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * A deployment on top of midPoint's initial objects ({@code scenarios/access}): the line's initial import runs before
 * the post-initial import, as at midPoint's first start, so the deployment can use the End user role, the default
 * security and password policies; its own system configuration replaces the initial one.
 */
@MidPointScenarios("classpath:scenarios/access/midpoint-config-tester.yaml")
class InitialObjectsScenarioTest {

    @Test
    void theTemplateAssignsTheInitialEndUserRole(Scenario s) {
        s.user(Users.JACK).hasFullName("Jack Sparrow").hasRole(InitialRoles.END_USER);
        s.user(Users.ANN).hasRole(InitialRoles.END_USER).hasRole(Roles.ACCOUNTANT);
    }

    /** The post-initial system configuration overwrites the initial one (midPoint: overwrite on every line). */
    @Test
    void theDeploymentsSystemConfigurationReplacesTheInitialOne(Scenario s) {
        SystemConfigurationType config = s.midpoint().object(SystemConfigurationType.class, "SystemConfiguration");
        assertThat(config.getDescription()).isEqualTo("Access deployment");
        // the initial one has a cleanup policy and GUI configuration (object collection views); the deployment's
        // has no cleanup policy, and GUI configuration of its own: one view
        assertThat(config.getCleanupPolicy()).isNull();
        assertThat(config.getAdminGuiConfiguration().getObjectCollectionViews().getObjectCollectionView())
                .extracting(v -> v.getIdentifier()).containsExactly("people");
        // the initial objects the deployment refers to are there
        assertThat(config.getGlobalSecurityPolicyRef().getOid()).isEqualTo(InitialPolicies.DEFAULT_SECURITY_POLICY.oid());
        assertThat(s.midpoint().object(SecurityPolicyType.class, InitialPolicies.DEFAULT_SECURITY_POLICY.oid())).isNotNull();
        assertThat(s.midpoint().object(ValuePolicyType.class, InitialPolicies.DEFAULT_PASSWORD_POLICY.name())).isNotNull();
    }

    /** midPoint's default password policy (an initial object, by the default security policy) checks passwords. */
    @Test
    void theDefaultPasswordPolicyRejectsAShortPassword(Scenario s) {
        Failure failure = s.expectFailure(() -> s.user(Users.JACK).setPassword("ab"));
        assertThat(failure.isPolicyViolation()).as(failure.message()).isTrue();

        s.user(Users.JACK).setPassword("Correct-Horse7");
    }
}
