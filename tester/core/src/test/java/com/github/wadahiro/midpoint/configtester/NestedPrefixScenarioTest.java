package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * A nested class shares its prefix: its {@code @BeforeAll} runs once, each of its tests (and nested classes) starts
 * from midPoint as the prefix left it: the users, the clock, the mails seen so far, the open work items.
 */
@MidPointConfigTest("classpath:scenarios/access/midpoint-config-tester.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NestedPrefixScenarioTest {

    static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    static int prefixRuns;

    static void addUser(MidPointConfigTester t, String name) {
        t.midpoint().addObjectXml("<user xmlns='" + NS + "'><name>" + name + "</name></user>");
    }

    static List<String> userNames(MidPointConfigTester t) {
        return t.midpoint().actor("administrator").search(UserType.class, null).value().stream()
                .map(u -> u.getName().getOrig()).collect(Collectors.toList());
    }

    @Test
    @Order(1)
    void aTopLevelTestStartsFromTheDeployment(MidPointConfigTester t) {
        assertThat(userNames(t)).doesNotContain("prefix");
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class JackHasAskedForPrivilegedAccess {

        Instant stopped;
        int mailsAfterPrefix;

        @BeforeAll
        void prefix(MidPointConfigTester t) {
            prefixRuns++;
            addUser(t, "prefix");
            stopped = t.clock().now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            t.clock().stopAt(stopped);
            t.actor(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
            mailsAfterPrefix = t.mails().count();
        }

        @Test
        @Order(1)
        void theOwnerApproves30DaysLater(MidPointConfigTester t) {
            t.clock().advance("P30D");
            t.workItems().assignedTo(Users.ROLE_OWNER).single().approve(t.actor(Users.ROLE_OWNER), "fine by me");
            addUser(t, "approval");
            assertThat(t.mails().count()).isGreaterThan(mailsAfterPrefix);
        }

        @Test
        @Order(2)
        void theNextTestStartsFromThePrefixAgain(MidPointConfigTester t) {
            assertThat(prefixRuns).isEqualTo(1);
            assertThat(userNames(t)).contains("prefix").doesNotContain("approval");
            assertThat(t.clock().now()).isEqualTo(stopped);
            assertThat(t.mails().count()).isEqualTo(mailsAfterPrefix);
            t.workItems().assignedTo(Users.ROLE_OWNER).single();
        }

        @Nested
        @TestInstance(TestInstance.Lifecycle.PER_CLASS)
        @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
        class ANestedClass {

            @BeforeAll
            void prefix(MidPointConfigTester t) {
                addUser(t, "nested");
            }

            @Test
            @Order(1)
            void hasItsOwnPrefixOnTheOuterOne(MidPointConfigTester t) {
                assertThat(userNames(t)).contains("prefix", "nested");
                addUser(t, "inner-1");
            }

            @Test
            @Order(2)
            void andGivesItToEachOfItsTests(MidPointConfigTester t) {
                assertThat(userNames(t)).contains("nested").doesNotContain("inner-1");
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AfterTheNestedClasses {

        @Test
        void theirPrefixesAreGone(MidPointConfigTester t) {
            assertThat(userNames(t)).doesNotContain("prefix", "nested");
            assertThat(t.mails().count()).isZero();
        }
    }

    @Test
    @Order(2)
    void theNextTopLevelTestStartsFromTheDeploymentAgain(MidPointConfigTester t) {
        assertThat(userNames(t)).doesNotContain("prefix", "nested");
        assertThat(t.mails().count()).isZero();
    }
}
