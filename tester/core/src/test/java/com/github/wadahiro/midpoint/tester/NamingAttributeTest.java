package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.Account;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * The naming attribute and the primary identifier of an object class as attributes of its objects
 * ({@code Account.hasAttribute}): a mirror keeps them as the object's name and identifier, not among its attributes.
 * A SCIM service on the mirror (fixtures/provisioning/resource-scim.xml): users named by {@code userName}, identified by
 * {@code id} (4.0: {@code scimId}, fixtures-4.0), groups named by {@code displayName}.
 */
@MidPointConfigTest("classpath:scenarios/bare/midpoint-config-tester.yaml")
class NamingAttributeTest {

    @Test
    void theNamingAttributeIsTheNameAndTheIdentifierTheId(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        TargetSystem scim = mp.importResource(Fixtures.of(mp, new File(MembershipTest.DIR, "resource-scim.xml")),
                MembershipTest.SCIM_SERVICE);
        scim.addAccount("jdoe", Map.of("displayName", "John Doe"));
        scim.addGroup("staff", Map.of());
        String id = scim.identifier("User", "jdoe");
        String idAttribute = t.line().equals("4.0") ? "scimId" : "id";

        Account account = t.target("SCIM").account("jdoe");
        account.hasAttribute("userName", "jdoe").hasAttribute("ri:userName", "jdoe").hasAttribute("__NAME__", "jdoe")
                .hasAttribute(idAttribute, id).hasAttribute("__UID__", id).hasAttribute("displayName", "John Doe");
        assertThat(account.value("userName")).isEqualTo("jdoe");
        assertThat(account.values(idAttribute)).containsExactly(id);
        assertThat(account.attributes()).containsEntry("userName", List.of("jdoe"))
                .containsEntry(idAttribute, List.of(id)).containsEntry("displayName", List.of("John Doe"));
        assertThatThrownBy(() -> account.hasNoAttribute("userName"))
                .hasMessageContaining("userName expected [] but was [jdoe]");
        t.target("SCIM").group("staff").hasAttribute("displayName", "staff");
    }
}
