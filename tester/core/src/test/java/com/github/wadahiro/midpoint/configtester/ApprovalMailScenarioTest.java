package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.sample.access.Roles;
import com.github.wadahiro.midpoint.configtester.sample.access.Users;

/**
 * Approval notifications over SMTP, as the sample deployment {@code scenarios/access} configures them
 * ({@code 000-system-configuration.xml}): Privileged Access needs the role owner (five days, reminded a day before the
 * deadline, then escalated to her deputy), then one of the Security officers (the group is expanded on work item
 * creation: each officer has a work item of their own, and a mail; the first decision counts). An approver is mailed when a work item waits for them, the requester when the request is
 * submitted and when it is decided. Each test checks the mails' content, and with {@code perRecipient()} that nobody
 * else got one. The same mails on 4.0, 4.4 and 4.8 (4.8 appends its subscription notice to the body).
 */
@MidPointScenarios("classpath:scenarios/access/midpoint-config-tester.yaml")
class ApprovalMailScenarioTest {

    static final String JACK = "jack@example.com";
    static final String OWNER = "role-owner@example.com";
    static final String DEPUTY = "owner-deputy@example.com";
    static final String OFFICER = "sec-officer@example.com";
    static final String ANALYST = "sec-analyst@example.com";

    @Test
    void eachStagesApproversAreMailedThenTheRequesterHearsTheOutcome(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        s.mails().to(JACK).withSubject("Request submitted: Privileged Access").single()
                .hasBodyContaining("Your request for Privileged Access for Jack Sparrow waits for approval.");
        s.mails().to(OWNER).withSubject("Approval needed: Privileged Access for Jack Sparrow").single()
                .hasBodyContaining("Dear Rose Owner,", "Jack Sparrow requests Privileged Access for Jack Sparrow (jack).",
                        "Stage 1 waits for your decision.");
        assertThat(s.mails().perRecipient()).isEqualTo(Map.of(JACK, 1, OWNER, 1));
        s.workItems().assignedTo(Users.ROLE_OWNER).single().approve(s.as(Users.ROLE_OWNER), "fine by me");

        // stage 2: every Security officer has a work item of their own, and a mail
        s.workItems().forUser(Users.JACK).inStage(2).exactly(2);
        assertThat(s.mails().withBodyContaining("Stage 2 waits for your decision.").perRecipient())
                .isEqualTo(Map.of(OFFICER, 1, ANALYST, 1));
        s.mails().to(ANALYST).withSubject("Approval needed: Privileged Access").single().hasBodyContaining("Dear Sue Analyst,");
        s.workItems().assignedTo(Users.SEC_ANALYST).single().approve(s.as(Users.SEC_ANALYST), "ok, ticket checked");

        // the first decision counts: the other officer's work item is cancelled, with no mail
        s.workItems().none();
        s.user(Users.JACK).hasRole(Roles.PRIVILEGED_ACCESS);
        s.mails().to(JACK).withSubject("Request approved: Privileged Access").single()
                .hasBodyContaining("Your request for Privileged Access has been approved by Sue Analyst: ok, ticket checked");
        assertThat(s.mails().perRecipient()).isEqualTo(Map.of(JACK, 2, OWNER, 1, OFFICER, 1, ANALYST, 1));
        assertThat(s.mails().from("idm@example.com").count()).isEqualTo(s.mails().count());
    }

    /**
     * The whole bodies, as the templates write them ({@code hasBody}: line ends, trailing blanks and 4.8's
     * subscription notice normalized away; {@code {{date}}} for what changes from run to run). Java 11 (the tests of
     * 4.0 and 4.4) has no text blocks: a body is joined from its lines, or read from a file next to the tests.
     */
    @Test
    void theWholeBodiesOfTheMails(Scenario s) throws IOException {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);

        s.mails().to(OWNER).withSubject("Approval needed").single().hasBody(String.join("\n",
                "Dear Rose Owner,",
                "",
                "Jack Sparrow requests Privileged Access for Jack Sparrow (jack). Stage 1 waits for your decision."));

        s.time().advance("P4DT1H").runDue();
        try (InputStream in = getClass().getResourceAsStream("/expected/reminder.txt")) {
            s.mails().to(OWNER).withSubject("Reminder").single()
                    .hasBody(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aRejectionTellsTheRequesterWhyAndGoesNoFurther(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
        s.workItems().assignedTo(Users.ROLE_OWNER).single()
                .reject(s.as(Users.ROLE_OWNER), "no privileged access without a ticket");

        s.cases().forUser(Users.JACK).forRole(Roles.PRIVILEGED_ACCESS).single().isRejected();
        s.user(Users.JACK).hasNoRole(Roles.PRIVILEGED_ACCESS);
        s.mails().to(JACK).withSubject("Request rejected: Privileged Access").single()
                .hasBodyContaining("has been rejected by Rose Owner: no privileged access without a ticket");
        // no stage 2, so no Security officer is mailed
        s.workItems().none();
        assertThat(s.mails().perRecipient()).isEqualTo(Map.of(JACK, 2, OWNER, 1));
    }

    @Test
    void anUndecidedRequestIsRemindedThenEscalatedToTheDeputy(Scenario s) {
        s.as(Users.JACK).request().assign(Roles.PRIVILEGED_ACCESS);
        WorkItem owners = s.workItems().assignedTo(Users.ROLE_OWNER).single().atEscalationLevel(0);
        Instant deadline = owners.deadline();
        // five calendar days in the JVM's zone: an hour more or less across a daylight saving change
        assertThat(Duration.between(s.time().now(), deadline))
                .isBetween(Duration.ofDays(5).minusHours(1).minusMinutes(5), Duration.ofDays(5).plusHours(1));

        // a day before the deadline: the owner is reminded, nothing else happens
        s.time().advance("P4DT1H").runDue();
        s.mails().to(OWNER).withSubject("Reminder: Privileged Access for Jack Sparrow").single()
                .hasBodyContaining("Dear Rose Owner,", "It will be escalated in P1D");
        s.workItems().assignedTo(Users.ROLE_OWNER).single().atEscalationLevel(0);
        assertThat(s.mails().perRecipient()).isEqualTo(Map.of(JACK, 1, OWNER, 2));

        // past the deadline: escalated to the deputy, who has three more days
        s.time().advance("P1D").runDue();
        s.workItems().assignedTo(Users.ROLE_OWNER).none();
        WorkItem escalated = s.workItems().forUser(Users.JACK).single().atStage(1).atEscalationLevel(1)
                .hasAssignee(Users.OWNER_DEPUTY);
        assertThat(escalated.assignees()).hasSize(1);
        assertThat(escalated.deadline()).isAfter(deadline.plus(Duration.ofDays(2)));
        s.mails().to(DEPUTY).withSubject("Escalated to you: Privileged Access for Jack Sparrow").single()
                .hasBodyContaining("Dear Olga Deputy,", "It is escalated to you (level 1, deputy).");

        // the deputy approves, then a Security officer: the request is done
        escalated.approve(s.as(Users.OWNER_DEPUTY));
        s.workItems().assignedTo(Users.SEC_OFFICER).single().approve(s.as(Users.SEC_OFFICER));
        s.user(Users.JACK).hasRole(Roles.PRIVILEGED_ACCESS);
        s.mails().to(JACK).withSubject("Request approved: Privileged Access").single()
                .hasBodyContaining("has been approved by Sam Officer.");
        assertThat(s.mails().perRecipient())
                .isEqualTo(Map.of(JACK, 2, OWNER, 2, DEPUTY, 1, OFFICER, 1, ANALYST, 1));
    }
}
