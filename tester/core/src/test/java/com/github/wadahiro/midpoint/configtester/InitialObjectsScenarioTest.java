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
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class InitialObjectsScenarioTest {

    @Test
    void theTemplateAssignsTheInitialEndUserRole(MidPointConfigTester t) {
        t.user(Users.JACK).hasFullName("Jack Sparrow").hasRole(InitialRoles.END_USER);
        t.user(Users.ANN).hasRole(InitialRoles.END_USER).hasRole(Roles.ACCOUNTANT);
    }

    /** The post-initial system configuration overwrites the initial one (midPoint: overwrite on every line). */
    @Test
    void theDeploymentsSystemConfigurationReplacesTheInitialOne(MidPointConfigTester t) {
        SystemConfigurationType config = t.midpoint().object(SystemConfigurationType.class, "SystemConfiguration");
        assertThat(config.getDescription()).isEqualTo("Access deployment");
        // the initial one has a cleanup policy and GUI configuration (object collection views); the deployment's
        // has no cleanup policy, and GUI configuration of its own: one view
        assertThat(config.getCleanupPolicy()).isNull();
        assertThat(config.getAdminGuiConfiguration().getObjectCollectionViews().getObjectCollectionView())
                .extracting(v -> v.getIdentifier()).containsExactly("people");
        // the initial objects the deployment refers to are there
        assertThat(config.getGlobalSecurityPolicyRef().getOid()).isEqualTo(InitialPolicies.DEFAULT_SECURITY_POLICY.oid());
        assertThat(t.midpoint().object(SecurityPolicyType.class, InitialPolicies.DEFAULT_SECURITY_POLICY.oid())).isNotNull();
        assertThat(t.midpoint().object(ValuePolicyType.class, InitialPolicies.DEFAULT_PASSWORD_POLICY.name())).isNotNull();
    }

    /** midPoint's default password policy (an initial object, by the default security policy) checks passwords. */
    @Test
    void theDefaultPasswordPolicyRejectsAShortPassword(MidPointConfigTester t) {
        Failure failure = t.expectFailure(() -> t.user(Users.JACK).setPassword("ab"));
        assertThat(failure.isPolicyViolation()).as(failure.message()).isTrue();

        t.user(Users.JACK).setPassword("Correct-Horse7");
    }
}
