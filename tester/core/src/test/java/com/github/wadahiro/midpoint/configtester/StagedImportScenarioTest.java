package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

/**
 * A deployment in stages ({@code scenarios/staged}): the files of {@code config.then} are imported after the
 * post-initial import of {@code config.dirs}, so an object of a later stage may refer to one of an earlier stage
 * whatever their file names; a stage with {@code processing: full} is imported as the GUI import does.
 */
@MidPointConfigTest("classpath:scenarios/staged/midpoint-config-tester.yaml")
class StagedImportScenarioTest {

    @Test
    void aLaterStageIsImportedAfterTheEarlierOne(MidPointConfigTester t) {
        RoleType dependent = t.midpoint().find(RoleType.class, "staged-dependent");

        assertThat(dependent.getInducement()).hasSize(1);
        assertThat(dependent.getInducement().get(0).getTargetRef().getOid())
                .isEqualTo("5a9e0c1e-3f1d-4c47-9a0c-000000000900");
    }

    @Test
    void aStageWithFullProcessingIsImportedAsTheGuiImportDoes(MidPointConfigTester t) {
        UserType user = t.midpoint().find(UserType.class, "staged-user");

        assertThat(user.getRoleMembershipRef()).extracting(ObjectReferenceType::getOid)
                .contains("5a9e0c1e-3f1d-4c47-9a0c-000000000900");
    }
}
