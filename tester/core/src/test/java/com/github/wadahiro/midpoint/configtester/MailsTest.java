package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.Mail;

/** The selections of {@link Mails} without midPoint: one mail to two recipients (4.0, 4.4) counts for each. */
class MailsTest {

    static Mail mail(String from, String subject, String body, String... to) {
        return Mail.builder().from(from).to(List.of(to)).recipients(List.of(to)).subject(subject)
                .body(body, "text/plain; charset=UTF-8").build();
    }

    final Mails mails = Mails.of(List.of(
            mail("idm@example.com", "Approval needed", "Stage 2 waits", "Ann@example.com", "bob@example.com"),
            mail("idm@example.com", "Request approved", "approved by Ann", "jack@example.com"),
            mail("hr@example.com", "Welcome", "Hello", "ann@example.com")));

    @Test
    void perRecipientCountsEachAddressOfAMail() {
        assertThat(mails.perRecipient()).isEqualTo(Map.of("ann@example.com", 2, "bob@example.com", 1,
                "jack@example.com", 1));
        assertThat(mails.withSubject("Approval").perRecipient()).containsOnlyKeys("ann@example.com", "bob@example.com");
    }

    @Test
    void fromAndBodySelect() {
        assertThat(mails.from("IDM@example.com").count()).isEqualTo(2);
        mails.withBodyContaining("approved by Ann").single().hasBodyContaining("approved", "Ann");
        mails.from("hr@example.com").withBodyContaining("approved").none();
    }

    @Test
    void aMissingTextNamesTheMailAndShowsTheBody() {
        assertThatThrownBy(() -> mails.to("jack@example.com").single().hasBodyContaining("rejected"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Request approved")
                .hasMessageContaining("to contain 'rejected'").hasMessageContaining("approved by Ann");
    }

    @Test
    void theNormalizedBodyHasLfNoTrailingSpaceAndNoSubscriptionFooter() {
        String sent = "Dear Ann,  \r\n\r\nStage 1 waits.\r\n" + Mail.SUBSCRIPTION_FOOTER + "\r\n";
        assertThat(Mail.normalizeBody(sent)).isEqualTo("Dear Ann,\n\nStage 1 waits.");
        assertThat(Mail.normalizeBody("Dear Ann,\n\nStage 1 waits.\n\n")).isEqualTo("Dear Ann,\n\nStage 1 waits.");
        // only a last line of exactly the footer is removed
        assertThat(Mail.normalizeBody("No active subscription.")).isEqualTo("No active subscription.");
    }

    @Test
    void hasBodyComparesWholeBodiesWithPlaceholdersPerLine() {
        Mail m = mail("idm@example.com", "Reminder", "Dear Rose,\r\n\r\nescalated in P1D (deadline "
                + "2026-11-11T21:17:54.637+09:00), case 30000000-0000-0000-0000-00000000e001.\r\n"
                + Mail.SUBSCRIPTION_FOOTER, "rose@example.com");
        m.hasBody("Dear Rose,\n\nescalated in {{duration}} (deadline {{date}}), case {{oid}}.");
        assertThatThrownBy(() -> m.hasBody("Dear Rose,\n\nescalated in {{duration}} (deadline {{date}}), case 1."))
                .isInstanceOf(org.opentest4j.AssertionFailedError.class).hasMessageContaining("differs at line 3");
        assertThatThrownBy(() -> m.hasBody("Dear Rosa,\n\nanything"))
                .isInstanceOf(org.opentest4j.AssertionFailedError.class).hasMessageContaining("differs at line 1");
    }
}
