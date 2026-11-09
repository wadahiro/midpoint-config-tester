package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.names.ObjectRef;

/**
 * {@code t.as(user).collection(name)}: an object collection as the user sees its list in the GUI: the filter with its
 * expressions evaluated for the user ({@code midpoint.principalOid}), the base collection (approval cases), the user's
 * authorizations.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
class CollectionScenarioTest {

    @Test
    void theRequestsOfTheLoggedInUser(MidPointConfigTester t) {
        t.as("jack").request().assign("Privileged Access");
        assertThat(t.as("jack").collection("My requests")).extracting(ObjectRef::type).containsOnly("CaseType")
                .isNotEmpty();
        assertThat(t.as("administrator").collection("My requests")).isEmpty();
    }

    @Test
    void aCollectionWithoutExpressions(MidPointConfigTester t) {
        assertThat(t.as("administrator").collection("People")).extracting(ObjectRef::name).contains("jack")
                .doesNotContain("administrator");
    }
}
