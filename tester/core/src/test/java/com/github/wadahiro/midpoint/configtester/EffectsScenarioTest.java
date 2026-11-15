package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.github.wadahiro.midpoint.configtester.sample.hr.HrAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;

/**
 * What a verb or a block did ({@link MidPointConfigTester#effectsOf}) over the sample deployment ({@code scenarios/hr}): the
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
        hired = t.task(Tasks.RECONCILE_HR).run();
    }

    @Test
    void aJoinerIsCreatedProvisionedAndWelcomed() {
        Claims claims = hired.claims();
        claims.changes().of("e1001").added().single();
        claims.changes().ofType(ShadowType.class).added().exactly(3);     // HR row, LDAP account, the group found
        claims.connectorCalls().to("LDAP").adds().of(JOHN.toUpperCase()).single();     // names ignore case
        claims.connectorCalls().to("LDAP").modifies().of(SALES).single().only("member");
        claims.mails().to("e1001@example.com").withSubject("Welcome John Doe").single();
        claims.verifyAll();

        assertThat(hired.connectorCalls().to("HR").reads().count()).isPositive();
        assertThat(hired.repositoryCalls().writes().ofType(UserType.class).count()).isPositive();
    }

    @Test
    void reconcilingAgainChangesNothing(MidPointConfigTester t) {
        Effects again = t.task(Tasks.RECONCILE_HR).run();

        again.none();
        assertThat(again.connectorCalls().to("HR").operation("search").count()).isPositive();
    }

    @Test
    void recomputingChangesNothing(MidPointConfigTester t) {
        t.user("e1001").recompute().none();
    }

    @Test
    void recomputingTheFocusesARunChangedChangesNothing(MidPointConfigTester t) {
        List<Focus<?>> focuses = hired.changes().focuses();

        assertThat(focuses).extracting(Focus::name).contains("e1001");
        focuses.forEach(f -> f.recompute().none());
        assertThat(hired.changes().ofType(ShadowType.class).focuses()).isEmpty();
        t.user("jdoe").add();
        assertThat(t.user("jdoe").delete().changes().focuses()).isEmpty();       // a deleted focus is left out
    }

    @Test
    void aMappingWaitingForItsTimeIsAtRestToo(MidPointConfigTester t) {
        Effects added = t.user("jdoe").add(u -> u.set("costCenter", "Sales")
                .validFrom(t.clock().now().plus(Duration.ofDays(30))));
        assertThat(t.midpoint().user("jdoe").getTrigger()).isNotEmpty();         // the role mapping's timeFrom

        added.changes().focuses().forEach(f -> f.recompute().none());
    }

    @Test
    void aMoverChangesOnlyTheDepartment(MidPointConfigTester t) {
        t.source(Resources.HR).modify("1001", HrAttrs.DEPARTMENT, "IT");

        Effects moved = t.task(Tasks.RECONCILE_HR).run();

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

        Effects moved = t.task(Tasks.RECONCILE_HR).run();

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
    void aBlockHasWhatEachOfItsVerbsDid(MidPointConfigTester t) {
        t.source(Resources.HR).modify("1001", HrAttrs.DEPARTMENT, "IT");

        Effects[] reconciled = new Effects[1];
        Effects block = t.effectsOf(() -> {
            reconciled[0] = t.task(Tasks.RECONCILE_HR).run();
            t.user("e1001").recompute();
        });

        reconciled[0].connectorCalls().to("LDAP").modifies().of(JOHN).single().only("ou");
        block.connectorCalls().to("LDAP").modifies().of(JOHN).single().only("ou");
        block.changes().of("e1001").single().only("costCenter", "assignment", "roleMembershipRef");
    }

    @Test
    void theTestersOwnCallsAreNotEffects(MidPointConfigTester t) {
        Effects reads = t.effectsOf(() -> t.user("e1001").asObject());

        assertThat(reads.repositoryCalls().count()).isZero();
        reads.none();
        assertThatThrownBy(() -> reads.mails().ignored()).isInstanceOf(IllegalStateException.class);
    }
}
