package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.hr.HrAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;

/**
 * What a block did ({@link MidPointConfigTester#effectsOf}) over the sample deployment ({@code scenarios/hr}): the
 * objects changed in the repository, the connector calls, the mails; read, or verified exhaustively.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class EffectsScenarioTest {

    static final String JOHN = "uid=e1001,ou=people,dc=example,dc=com";
    static final String SALES = "cn=sales,ou=groups,dc=example,dc=com";
    static final String IT = "cn=it,ou=groups,dc=example,dc=com";

    private Effects hired;

    @BeforeEach
    void hire(MidPointConfigTester t) {
        t.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1001").set(HrAttrs.FIRST_NAME, "John")
                .set(HrAttrs.LAST_NAME, "Doe").set(HrAttrs.DEPARTMENT, "Sales")
                .set(HrAttrs.START_DATE, LocalDate.of(2020, 4, 1).toString()));
        hired = t.effectsOf(() -> t.task(Tasks.RECONCILE_HR).run());
    }

    @Test
    void aJoinerIsCreatedProvisionedAndWelcomed() {
        Claims claims = hired.claims();
        claims.changes().of("e1001").added().single();
        claims.changes().ofType("ShadowType").added().exactly(3);     // HR row, LDAP account, the group found
        claims.connectorCalls().to("LDAP").adds().of(JOHN.toUpperCase()).single();     // names ignore case
        claims.connectorCalls().to("LDAP").modifies().of(SALES).single().only("member");
        claims.mails().to("e1001@example.com").withSubject("Welcome John Doe").single();
        claims.verifyAll();

        assertThat(hired.connectorCalls().to("HR").reads().count()).isPositive();
        assertThat(hired.repositoryCalls().writes().ofType("UserType").count()).isPositive();
    }

    @Test
    void reconcilingAgainChangesNothing(MidPointConfigTester t) {
        Effects again = t.effectsOf(() -> t.task(Tasks.RECONCILE_HR).run());

        again.none();
        assertThat(again.connectorCalls().to("HR").operation("search").count()).isPositive();
    }

    @Test
    void recomputingChangesNothing(MidPointConfigTester t) {
        t.effectsOf(() -> t.user("e1001").recompute()).none();
    }

    @Test
    void aMoverChangesOnlyTheDepartment(MidPointConfigTester t) {
        t.source(Resources.HR).modify("1001", HrAttrs.DEPARTMENT, "IT");

        Effects moved = t.effectsOf(() -> t.task(Tasks.RECONCILE_HR).run());

        Claims claims = moved.claims();
        claims.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
        claims.changes().of(IT).added().single();
        claims.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("ou");
        claims.connectorCalls().to("LDAP").modifies().of(SALES).single().only("member");
        claims.connectorCalls().to("LDAP").modifies().of(IT).single().only("member");
        claims.mails().none();
        claims.verifyAll();
    }

    @Test
    void whatIsNotClaimedFails(MidPointConfigTester t) {
        t.source(Resources.HR).modify("1001", HrAttrs.DEPARTMENT, "IT");

        Effects moved = t.effectsOf(() -> t.task(Tasks.RECONCILE_HR).run());

        Claims claims = moved.claims();
        claims.changes().of("e1001").single();
        assertThatThrownBy(claims::verifyAll).isInstanceOf(AssertionError.class)
                .hasMessageContaining("Effects of the block not verified")
                .hasMessageContaining("connector: LDAP modify inetOrgPerson " + JOHN + " [ou]")
                .hasMessageContaining("added: ShadowType " + IT)
                .hasMessageNotContaining("UserType e1001");
        assertThatThrownBy(moved::none).isInstanceOf(AssertionError.class).hasMessageContaining("UserType e1001");
        assertThatThrownBy(() -> moved.changes().of("e1001").single().only("costCenter"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("but also: assignment");
    }

    @Test
    void theTestersOwnCallsAreNotEffects(MidPointConfigTester t) {
        Effects reads = t.effectsOf(() -> t.user("e1001").asObject());

        assertThat(reads.repositoryCalls().count()).isZero();
        reads.none();
        assertThatThrownBy(() -> reads.mails().ignored()).isInstanceOf(IllegalStateException.class);
    }
}
