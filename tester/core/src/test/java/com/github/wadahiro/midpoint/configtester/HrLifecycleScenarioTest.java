package com.github.wadahiro.midpoint.configtester;

import static com.github.wadahiro.midpoint.configtester.Status.DISABLED;
import static com.github.wadahiro.midpoint.configtester.Status.ENABLED;
import static com.github.wadahiro.midpoint.configtester.assertj.MidPointConditions.refTo;
import static com.github.wadahiro.midpoint.configtester.assertj.MidPointConditions.targeting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivationStatusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

import com.github.wadahiro.midpoint.configtester.names.AttributeRef;
import com.github.wadahiro.midpoint.configtester.names.midpoint.UserItems;
import com.github.wadahiro.midpoint.configtester.sample.hr.HrAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.LdapAttrs;
import com.github.wadahiro.midpoint.configtester.sample.hr.Resources;
import com.github.wadahiro.midpoint.configtester.sample.hr.Roles;
import com.github.wadahiro.midpoint.configtester.sample.hr.Tasks;
import com.github.wadahiro.midpoint.configtester.sample.hr.UserExtension;

/**
 * Scenarios over the sample deployment ({@code scenarios/hr}): HR rows become users, the user template gives them the
 * role of their department from their start date on, the roles provision LDAP accounts and group memberships. The
 * deployment is loaded once (midPoint's post-initial import of its directory); every test starts from it. The names
 * of resources, roles, tasks, attributes and extension items are the typed names generated from the deployment
 * ({@code names.package} of its manifest); {@link #theStringsAreTheEscapeHatch} uses plain strings. The scenarios
 * read the values through the views and check them with AssertJ; {@link #theAssertionsOfTheApiReadTheSame} shows the
 * API's own assertions.
 */
@MidPointScenarios("classpath:scenarios/hr/midpoint-config-tester.yaml")
class HrLifecycleScenarioTest {

    static final String PEOPLE = "ou=people,dc=example,dc=com";
    static final String JOHN = "uid=e1001," + PEOPLE;
    static final String KATE = "uid=e1002," + PEOPLE;
    static final String SALES = "cn=sales,ou=groups,dc=example,dc=com";
    static final String IT = "cn=it,ou=groups,dc=example,dc=com";

    /** What "hire", "update" and "terminate" mean in this deployment: the project's vocabulary, not the tester's. */
    static final class Hr {
        private final Scenario s;

        Hr(Scenario s) {
            this.s = s;
        }

        void hire(String number, String firstName, String lastName, String department, LocalDate start) {
            s.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, number).set(HrAttrs.FIRST_NAME, firstName)
                    .set(HrAttrs.LAST_NAME, lastName).set(HrAttrs.DEPARTMENT, department)
                    .set(HrAttrs.START_DATE, start.toString()));
            s.task(Tasks.RECONCILE_HR).run();
        }

        void update(String number, AttributeRef column, String value) {
            s.source(Resources.HR).modify(number, column, value);
            s.task(Tasks.RECONCILE_HR).run();
        }

        void terminate(String number) {
            s.source(Resources.HR).delete(number);
            s.task(Tasks.RECONCILE_HR).run();
        }
    }

    @Test
    void joinerMoverLeaver(Scenario s) {
        Hr hr = new Hr(s);

        hr.hire("1001", "John", " DOE ", "Sales", LocalDate.of(2020, 4, 1));
        User john = s.user("e1001");
        UserType u = john.object();
        assertThat(u.getFullName().getOrig()).isEqualTo("John Doe");
        assertThat(u.getCostCenter()).isEqualTo("Sales");
        assertThat(u.getAssignment()).haveExactly(1, targeting(Roles.DEPT_SALES));
        assertThat(u.getActivation().getEffectiveStatus()).isEqualTo(ActivationStatusType.ENABLED);
        john.hasAccountOn(Resources.HR).hasAccountOn(Resources.LDAP);
        Account ldap = s.target(Resources.LDAP).account(JOHN);
        assertThat(ldap.value(LdapAttrs.CN)).isEqualTo("John Doe");
        assertThat(ldap.value(LdapAttrs.OU)).isEqualTo("Sales");
        assertThat(ldap.value(LdapAttrs.MAIL)).isEqualTo("e1001@example.com");
        assertThat(ldap.groups()).containsExactly(SALES);
        assertThat(ldap.status()).isEqualTo(ENABLED);
        assertThat(s.mails().to("e1001@example.com").subjects()).containsExactly("Welcome John Doe");

        hr.update("1001", HrAttrs.DEPARTMENT, "IT");
        u = john.object();      // the user as midPoint has it now
        assertThat(u.getAssignment()).haveExactly(1, targeting(Roles.DEPT_IT)).doNotHave(targeting(Roles.DEPT_SALES));
        assertThat(ldap.value(LdapAttrs.OU)).isEqualTo("IT");
        assertThat(ldap.groups()).containsExactly(IT);

        hr.terminate("1001");
        john.doesNotExist();
        ldap.doesNotExist();
    }

    @Test
    void futureJoinerIsProvisionedOnTheStartDateAndDisabledAfterTheEndDate(Scenario s) {
        Hr hr = new Hr(s);
        LocalDate start = s.time().today().plusDays(10);
        User kate = s.user("e1002");
        Account ldap = s.target(Resources.LDAP).account(KATE);

        // hired today, starting in ten days: a user, but no role and no account yet
        hr.hire("1002", "Kate", "Smith", "IT", start);
        assertThat(kate.object().getActivation().getEffectiveStatus()).isEqualTo(ActivationStatusType.DISABLED);
        assertThat(kate.object().getAssignment()).doNotHave(targeting(Roles.DEPT_IT));
        kate.hasNoAccountOn(Resources.LDAP);
        ldap.doesNotExist();

        // the start date has come: the scanners recompute her, the role and the account follow
        s.time().advanceTo(start).advance("PT1H").runDue();
        UserType u = kate.object();
        assertThat(u.getActivation().getEffectiveStatus()).isEqualTo(ActivationStatusType.ENABLED);
        assertThat(u.getAssignment()).haveExactly(1, targeting(Roles.DEPT_IT));
        assertThat(u.getRoleMembershipRef()).haveAtLeastOne(refTo(Roles.DEPT_IT));
        assertThat(ldap.groups()).containsExactly(IT);
        assertThat(ldap.status()).isEqualTo(ENABLED);

        // HR records the end of the employment; once it has passed, the user and the account are disabled
        LocalDate end = start.plusDays(30);
        hr.update("1002", HrAttrs.END_DATE, end.toString());
        assertThat(ldap.status()).isEqualTo(ENABLED);
        s.time().advanceTo(end).advance("PT1H").runDue();
        assertThat(kate.object().getActivation().getEffectiveStatus()).isEqualTo(ActivationStatusType.DISABLED);
        assertThat(ldap.status()).isEqualTo(DISABLED);
    }

    /** The API's own assertions check the same, chained, each failing with what is there and the mappings behind it. */
    @Test
    void theAssertionsOfTheApiReadTheSame(Scenario s) {
        new Hr(s).hire("1001", "John", " DOE ", "Sales", LocalDate.of(2020, 4, 1));
        s.user("e1001").exists()
                .hasFullName("John Doe")
                .hasItem(UserItems.COST_CENTER, "Sales")
                .hasRole(Roles.DEPT_SALES)
                .isEnabled()
                .hasAccountOn(Resources.HR)
                .hasAccountOn(Resources.LDAP);
        s.target(Resources.LDAP).account(JOHN)
                .hasAttribute(LdapAttrs.OU, "Sales")
                .memberOf(SALES)
                .notMemberOf(IT)
                .isEnabled();
        s.mails().to("e1001@example.com").withSubject("Welcome John Doe").exactly(1);
    }

    /**
     * The effective status of an archived user (lifecycle state {@code archived}): {@code archived} on 4.0 and 4.4, with
     * its {@code archiveTimestamp}; {@code disabled} on 4.8+, with a {@code disableTimestamp} and no archive time.
     */
    @Test
    void anArchivedUsersEffectiveStatus(Scenario s) {
        s.midpoint().addObjectXml("<user xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'>"
                + "<name>archived1</name><lifecycleState>archived</lifecycleState></user>");

        com.evolveum.midpoint.xml.ns._public.common.common_3.ActivationType activation =
                s.user("archived1").object().getActivation();
        if (com.github.wadahiro.midpoint.common.MidPointLine.isBefore(s.line(), "4.8")) {
            // the time it was archived
            assertThat(activation.getArchiveTimestamp()).isNotNull();
            assertThat(activation.getDisableTimestamp()).isNull();
            s.user("archived1").isArchived();
            assertThat(catchThrowableOfType(() -> s.user("archived1").isEnabled(), AssertionError.class))
                    .hasMessageContaining("expected effectiveStatus enabled but was archived")
                    .hasMessageContaining("lifecycleState archived");
        } else {
            // MID-9026 (0a384b387f7): the status is never archived, so no archiveTimestamp is set (the disable time is)
            assertThat(activation.getArchiveTimestamp()).isNull();
            assertThat(activation.getDisableTimestamp()).isNotNull();
            s.user("archived1").isDisabled();
        }
    }

    /**
     * An AssertJ assertion on a view fails with AssertJ's message, its expected and actual values (for the IDE's
     * diff), and the mappings behind the values the test read, the last read first.
     */
    @Test
    void aFailedAssertionOnAViewShowsTheMappingsBehindTheValuesRead(Scenario s) {
        new Hr(s).hire("1001", "John", "Doe", "Sales", LocalDate.of(2020, 4, 1));
        Account ldap = s.target(Resources.LDAP).account(JOHN);

        AssertionError failure = catchThrowableOfType(() -> {
            try {
                assertThat(ldap.value(LdapAttrs.OU)).isEqualTo("Marketing");
            } catch (AssertionError e) {
                // what the extension does when the test fails
                throw ScenarioExtension.withExplanations(e, e.getMessage() + "\n\n" + s.explanations(e));
            }
        }, AssertionError.class);

        assertThat(failure).isInstanceOf(org.opentest4j.AssertionFailedError.class);
        // AssertJ gives the IDE the values as it shows them
        assertThat(((org.opentest4j.AssertionFailedError) failure).getActual().getStringRepresentation())
                .contains("Sales");
        assertThat(failure.getMessage())
                .contains("Marketing")
                .contains("Mappings that set ou of " + JOHN + " on LDAP in this test")
                .contains("for e1001");
    }

    /**
     * A failed assertion on the user's object shows midPoint's values readably and the mappings that set an item whose
     * value appears in the failure (here the assignments, set by the user template from the department).
     */
    @Test
    void aFailedAssertionOnTheObjectShowsTheValuesAndTheMappingsBehindThem(Scenario s) {
        new Hr(s).hire("1001", "John", "Doe", "Sales", LocalDate.of(2020, 4, 1));
        UserType john = s.user("e1001").object();

        AssertionError failure = catchThrowableOfType(() -> {
            try {
                assertThat(john.getAssignment()).haveExactly(1, targeting(Roles.DEPT_IT));
            } catch (AssertionError e) {
                // what the extension does when the test fails
                throw ScenarioExtension.withExplanations(e, e.getMessage() + "\n\n" + s.explanations(e));
            }
        }, AssertionError.class);

        assertThat(failure.getMessage())
                .contains("assignment of role Dept Sales")
                .contains("assignment of role Dept IT")
                .contains("Possibly related")
                .contains("Mappings that set assignment of e1001 in this test");
        // the projector evaluates a mapping several times in the reconciliation: the same evaluation is one line, counted
        String[] lines = failure.getMessage().split("\n");
        for (int i = 1; i < lines.length; i++) {
            assertThat(lines[i]).as("line " + i).isNotEqualTo(lines[i - 1]);
        }
    }

    /** How often each mapping was evaluated in the test and how long it took, by the total after the first. */
    @Test
    void theMappingStatsCountAndTimeTheEvaluations(Scenario s) {
        Hr hr = new Hr(s);
        hr.hire("1001", "John", "Doe", "Sales", LocalDate.of(2020, 4, 1));
        hr.hire("1002", "Kate", "Smith", "IT", LocalDate.of(2020, 4, 1));

        MappingStats stats = s.mappingStats();
        MappingStats.Entry role = stats.of("user-department-role");
        assertThat(role).isNotNull();
        assertThat(role.owner()).isEqualTo("User template");
        // at least once for each of the two users
        assertThat(role.count()).isGreaterThanOrEqualTo(2);
        assertThat(role.total()).isPositive().isGreaterThanOrEqualTo(role.max());
        assertThat(role.median()).isLessThanOrEqualTo(role.max());
        assertThat(role.rest()).isEqualTo(role.total().minus(role.first()));
        assertThat(stats.entries()).isSortedAccordingTo(
                java.util.Comparator.comparing(MappingStats.Entry::rest).reversed());
        assertThat(stats.toString())
                .startsWith("Mapping evaluations in this test, by the total after the first, the longest first:")
                .contains("'user-department-role' of User template: ");
    }

    @Test
    void everyTestStartsFromTheDeploymentAsLoaded(Scenario s) {
        s.user("e1001").doesNotExist();
        s.user("e1002").doesNotExist();
        assertThat(s.source(Resources.HR).accounts()).isEmpty();
        assertThat(s.target(Resources.LDAP).accounts()).isEmpty();
        s.mails().none();
        // the clock is back to the real time
        assertThat(Duration.between(java.time.Instant.now(), s.time().now()).abs()).isLessThan(Duration.ofMinutes(1));
    }

    /**
     * The manifest holds the recurring tasks: the hourly reconciliation imported by the load has never run (in
     * production it would have run right after the deployment), so it is due, and only {@code runDue()} runs it.
     */
    @Test
    void theHeldReconciliationRunsWhenDue(Scenario s) {
        s.source(Resources.HR).add("1003", row -> row.set(HrAttrs.FIRST_NAME, "Ann").set(HrAttrs.LAST_NAME, "Lee")
                .set(HrAttrs.DEPARTMENT, "Sales").set(HrAttrs.START_DATE, "2020-04-01"));
        s.midpoint().waitForTasks();
        s.user("e1003").doesNotExist();

        s.time().runDue();
        s.user("e1003").exists().hasRole(Roles.DEPT_SALES);
    }

    /** The HR site column goes to the users' extension item {@code site} (the extension schema of the manifest). */
    @Test
    void theSiteGoesToTheUsersExtension(Scenario s) {
        s.source(Resources.HR).add(row -> row.set(HrAttrs.EMPNO, "1004").set(HrAttrs.FIRST_NAME, "Joe")
                .set(HrAttrs.LAST_NAME, "Hill").set(HrAttrs.DEPARTMENT, "IT").set(HrAttrs.SITE, "Prague")
                .set(HrAttrs.START_DATE, "2020-04-01"));
        s.task(Tasks.RECONCILE_HR).run();
        s.user("e1004").hasItem(UserExtension.SITE, "Prague");
        assertThat(s.user("e1004").why(UserExtension.SITE).last().mapping()).isEqualTo("hr-site");
    }

    /** A typed name used on the wrong resource fails with what it is, not with "no such attribute". */
    @Test
    void aTypedAttributeOfAnotherResourceIsRefused(Scenario s) {
        AssertionError error = null;
        try {
            s.target(Resources.LDAP).account(JOHN).hasAttribute(HrAttrs.FIRST_NAME, "John");
        } catch (AssertionError e) {
            error = e;
        }
        assertThat(error).isNotNull().hasMessageContaining("attribute of HR firstName is used on resource LDAP");
    }

    /**
     * The escape hatch: every verb also takes plain strings, for a name the generated classes do not have (an object
     * a scenario creates, an attribute only the connector knows).
     */
    @Test
    void theStringsAreTheEscapeHatch(Scenario s) {
        s.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales",
                "startDate", "2020-04-01"));
        s.task("Reconcile HR").run();
        s.user("e1001").hasRole("Dept Sales").hasAccountOn("LDAP");
        s.target("LDAP").account(JOHN).hasAttribute("ou", "Sales").memberOf(SALES);
    }

    @Test
    void aFailedAssertionShowsWhatIsThereAndTheMappingsBehindIt(Scenario s) {
        new Hr(s).hire("1001", "John", "Doe", "Sales", LocalDate.of(2020, 4, 1));

        AssertionError failure = null;
        try {
            s.user("e1001").hasFullName("Johnny Doe");
        } catch (AssertionError e) {
            failure = e;
        }
        assertThat(failure).isNotNull();
        assertThat(failure.getMessage())
                .contains("User e1001: fullName expected [Johnny Doe] but was [John Doe]")
                .contains("Mappings that set fullName of e1001 in this test")
                .contains("'user-fullName' of User template");
    }

    @Test
    void whyShowsTheMappingsBehindAValue(Scenario s) {
        Hr hr = new Hr(s);
        hr.hire("1001", "John", "Doe", "Sales", LocalDate.of(2020, 4, 1));
        hr.update("1001", HrAttrs.LAST_NAME, "Smith");

        Why fullName = s.user("e1001").why(UserItems.FULL_NAME);
        assertThat(fullName.steps()).isNotEmpty();
        Why.Step last = fullName.last();
        assertThat(last.mapping()).isEqualTo("user-fullName");
        assertThat(last.owner()).isEqualTo("User template");
        assertThat(last.focus()).isEqualTo("e1001");
        assertThat(last.output()).containsExactly("John Smith");
        // the evaluation of the change (the later ones of the same run see the new state on both sides)
        assertThat(fullName.steps()).anySatisfy(step -> {
            assertThat(step.sources()).containsEntry("familyName", "[Doe] -> [Smith]");
            assertThat(step.output()).containsExactly("John Smith");
        });
        // the deployed reconciliation task ran it, in its channel
        assertThat(last.task()).isEqualTo("Reconcile HR");
        assertThat(last.channel()).isEqualTo("reconciliation");
        assertThat(fullName.toString()).startsWith("Mappings that set fullName of e1001 in this test:")
                .contains("'user-fullName' of User template for e1001 (task Reconcile HR, reconciliation)");

        // an attribute of an account: the outbound mappings of its owner
        Why cn = s.target(Resources.LDAP).account(JOHN).why(LdapAttrs.CN);
        assertThat(cn.last().focus()).isEqualTo("e1001");
        assertThat(cn.last().output()).containsExactly("John Smith");
        // nothing set the description
        assertThat(s.user("e1001").why(UserItems.DESCRIPTION).isEmpty()).isTrue();
    }
}
