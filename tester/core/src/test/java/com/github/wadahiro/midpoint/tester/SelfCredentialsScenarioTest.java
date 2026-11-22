package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.crypto.Protector;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ConstructionType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CredentialsPolicyType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PasswordChangeSecurityType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PasswordCredentialsPolicyType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SecurityPolicyType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ValuePolicyType;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;
import com.github.wadahiro.midpoint.configtester.SelfCredentialsPage;

/**
 * The user's own credentials page ({@code Actor.selfCredentials()}), run by the line's GUI over the bare deployment
 * (fixtures/self-credentials: a password policy of at least 10 characters with a history of 3, jdoe with the End user
 * role, nobody without a role, pageonly with a role that opens the page and reads the user but grants no change; the
 * LDAP resource of fixtures/provisioning for a linked account). The page asks for the current password (the security
 * policy sets no passwordChangeSecurity), lists the user and their accounts, and its Save executes its own changes
 * through the model as the user: a save the policy or the authorizations refuse changes nothing.
 */
@MidPointConfigTest(Fixtures.BARE)
class SelfCredentialsScenarioTest {

    static final String SECURITY_POLICY = "00000000-0000-0000-0000-000000000120";
    static final String VALUE_POLICY = "10000000-0000-0000-0000-0000000c0001";
    static final String LDAP = "10000000-0000-0000-0000-00000000a002";
    static final String JDOE_DN = "uid=jdoe,ou=people,dc=example,dc=com";

    private static void setUp(MidPointConfigTester t) {
        setUp(t, null);
    }

    private static void setUp(MidPointConfigTester t, PasswordChangeSecurityType changeSecurity) {
        MidPoint mp = t.midpoint();
        mp.importObject(new File(Fixtures.dir("self-credentials"), "objects.xml"));
        CredentialsPolicyType credentials = new CredentialsPolicyType().password(new PasswordCredentialsPolicyType()
                .historyLength(3)
                .passwordChangeSecurity(changeSecurity)
                .valuePolicyRef(new ObjectReferenceType().oid(VALUE_POLICY).type(ValuePolicyType.COMPLEX_TYPE)));
        mp.modify(SecurityPolicyType.class, SECURITY_POLICY, ItemPath.create(SecurityPolicyType.F_CREDENTIALS),
                credentials);
    }

    /**
     * jdoe and an LDAP account: the resource of fixtures/provisioning with a password (its account type's
     * {@code credentials/password}, the mirror's password capability) and no password mapping, so the page changes the
     * account's password only when its row is selected.
     */
    private static void withAnAccount(MidPointConfigTester t, Path dir) throws Exception {
        withAnAccount(t, dir, "<credentials><password/></credentials>");
        assertThat(t.midpoint().target("LDAP").password(JDOE_DN)).isNull();
    }

    /** As {@link #withAnAccount(MidPointConfigTester, Path)}, the account type's credentials as given. */
    private static void withAnAccount(MidPointConfigTester t, Path dir, String credentials) throws Exception {
        MidPoint mp = t.midpoint();
        String xml = new String(Files.readAllBytes(new File(Fixtures.dir("provisioning"), "resource-ldap.xml").toPath()),
                StandardCharsets.UTF_8);
        int end = xml.indexOf("        </objectType>");
        xml = xml.substring(0, end) + "            " + credentials + "\n" + xml.substring(end);
        File resource = dir.resolve("resource-ldap.xml").toFile();
        Files.write(resource.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        mp.importResource(resource, ResourceOptions.defaults());
        mp.modifyAdd(UserType.class, "jdoe", ItemPath.create(UserType.F_ASSIGNMENT), new AssignmentType()
                .construction(new ConstructionType().resourceRef(LDAP, ResourceType.COMPLEX_TYPE)));
    }

    private static String password(MidPointConfigTester t, String user) throws Exception {
        UserType object = t.midpoint().user(user);
        return t.midpoint().bean(Protector.class).decryptString(object.getCredentials().getPassword().getValue());
    }

    /** The page asks for the current password and lists the user themself; its Save changes the user's password. */
    @Test
    void theUserChangesTheirOwnPassword(MidPointConfigTester t) throws Exception {
        setUp(t);

        SelfCredentialsPage.View page = t.actor("jdoe").selfCredentials().open();
        assertThat(page.asksCurrentPassword()).isTrue();
        SelfCredentialsPage.Target self = page.target("jdoe");
        assertThat(self.isUser()).isTrue();
        assertThat(self.selected()).isTrue();

        SelfCredentialsPage.Submit submit = t.actor("jdoe").selfCredentials().currentPassword("Initial.12345")
                .newPassword("Changed.12345").submit().saves();
        assertThat(String.join("\n", submit.messages())).contains("SUCCESS");
        assertThat(password(t, "jdoe")).isEqualTo("Changed.12345");
    }

    /**
     * Passwords the policy or the page refuses, each with its message and nothing changed: too short (4.4+ list the
     * requirement unmet as it is typed; 4.0 lists none), recently used (the history), a wrong current password. 4.8+
     * with the table not listed (no propagationUserControl), the model's refusal shows only the toast pointing to the
     * table, which the browser does not render: the policy's message is not among the messages, only in the rows'
     * results the page holds.
     */
    @Test
    void aPolicyViolationIsShownAndNothingChanges(MidPointConfigTester t) throws Exception {
        setUp(t);

        SelfCredentialsPage.View typed = t.actor("jdoe").selfCredentials().newPassword("Short.1").open();
        if (t.line().equals("4.0")) {
            assertThat(typed.requirements()).isEmpty();
        } else {
            assertThat(typed.unmetRequirements()).isNotEmpty();
            // the table listed (4.8+: "Change password on individual systems" ticked by the selection), the user's own
            // row's validation column lists the same policy: the page's list has each requirement once, not twice
            SelfCredentialsPage.View listed = t.actor("jdoe").selfCredentials().select("jdoe").newPassword("Short.1")
                    .open();
            assertThat(listed.listsTargets()).isTrue();
            assertThat(listed.target("jdoe").requirements()).isNotEmpty();
            assertThat(listed.requirements()).hasSameSizeAs(listed.target("jdoe").requirements());
        }

        refusedByThePolicy(t, t.actor("jdoe").selfCredentials().currentPassword("Initial.12345").newPassword("Short.1")
                .submit(), "Minimal size (10) is not met");
        assertThat(password(t, "jdoe")).isEqualTo("Initial.12345");

        refusedByThePolicy(t, t.actor("jdoe").selfCredentials().currentPassword("Initial.12345")
                .newPassword("Initial.12345").submit(), "recently used");
        assertThat(password(t, "jdoe")).isEqualTo("Initial.12345");

        // the page's own check before the model, a toast on 4.8+
        SelfCredentialsPage.Submit wrong = t.actor("jdoe").selfCredentials().currentPassword("Wrong.12345")
                .newPassword("Changed.12345").submit().rejectsWithMessageContaining("Old password is incorrect");
        if (Lines.atLeast(t.midpoint(), "4.8")) {
            // the toast as "title: body", and its body alone as 4.0 and 4.4 show their feedback
            assertThat(wrong.messages()).contains("Save password: Password not changed. Old password is incorrect.",
                    "Password not changed. Old password is incorrect.");
        }
        assertThat(password(t, "jdoe")).isEqualTo("Initial.12345");
    }

    /**
     * The model refused the save for the policy: 4.0 and 4.4 show its message (4.4: the user's row's result in the
     * table shown); 4.8+, the table not listed, only the toast pointing to the table.
     */
    private static void refusedByThePolicy(MidPointConfigTester t, SelfCredentialsPage.Submit submit, String text) {
        submit.rejects();
        if (Lines.atLeast(t.midpoint(), "4.8")) {
            assertThat(submit.rowResultsShown()).isFalse();
            assertThat(submit.page().listsTargets()).isFalse();
            assertThat(submit.messages()).noneMatch(m -> m.contains(text));
            submit.rejectsWithMessageContaining("see result table for details");
            assertThat(submit.targetResults()).anyMatch(m -> m.contains(text));
        } else {
            submit.rejectsWithMessageContaining(text);
        }
    }

    /**
     * The repetition not the new password: the page's form refuses it before the model (4.8+: its toast "Save
     * password: " with the fields' messages, one a line), nothing changed.
     */
    @Test
    void aMistypedRepetitionIsRefused(MidPointConfigTester t) throws Exception {
        setUp(t);

        SelfCredentialsPage.Submit submit = t.actor("jdoe").selfCredentials().currentPassword("Initial.12345")
                .newPassword("Changed.12345", "Changed.54321").submit()
                .rejectsWithMessageContaining("Passwords don't match");
        assertThat(submit.messages()).contains("Passwords don't match.");
        if (Lines.atLeast(t.midpoint(), "4.8")) {
            assertThat(submit.messages()).contains("Save password: Passwords don't match.");
        }
        assertThat(password(t, "jdoe")).isEqualTo("Initial.12345");
    }

    /**
     * The security policy's passwordChangeSecurity none: the page does not ask for the current password, saves
     * without it, and typing it fails (the page has no such field).
     */
    @Test
    void noCurrentPasswordIsAskedWithPasswordChangeSecurityNone(MidPointConfigTester t) throws Exception {
        setUp(t, PasswordChangeSecurityType.NONE);

        assertThat(t.actor("jdoe").selfCredentials().open().asksCurrentPassword()).isFalse();
        assertThatThrownBy(() -> t.actor("jdoe").selfCredentials().currentPassword("Initial.12345")
                .newPassword("Changed.12345").submit()).hasMessageContaining("does not ask for it");
        assertThat(password(t, "jdoe")).isEqualTo("Initial.12345");

        t.actor("jdoe").selfCredentials().newPassword("Changed.12345").submit().saves();
        assertThat(password(t, "jdoe")).isEqualTo("Changed.12345");
    }

    /**
     * pageonly may open the page (#selfCredentials) and read themself, but no authorization lets them change their
     * password: the page opens, its Save is refused by the model as the user, nothing changed.
     */
    @Test
    void aUserWhoMayOpenThePageButNotChangeIsRefused(MidPointConfigTester t) throws Exception {
        setUp(t);

        assertThat(t.actor("pageonly").canOpen("/self/credentials")).isTrue();
        assertThat(t.actor("pageonly").selfCredentials().open().target("pageonly").selected()).isTrue();
        // as the page shows it: 4.0 the model's message; 4.4+ the message pointing to the table, whose rows show no
        // result (the model refused the request before any change was executed)
        SelfCredentialsPage.Submit submit = t.actor("pageonly").selfCredentials().currentPassword("Initial.12345")
                .newPassword("Changed.12345").submit().rejectsWithMessageContaining(t.line().equals("4.0")
                        ? "not authorized" : "see result table for details");
        assertThat(submit.targetResults()).isEmpty();
        assertThat(password(t, "pageonly")).isEqualTo("Initial.12345");
    }

    /** A user without the page's authorization (#selfAll, #selfCredentials) can neither open it nor save. */
    @Test
    void aUserWithoutTheAuthorizationCannotOpenOrSave(MidPointConfigTester t) throws Exception {
        setUp(t);

        assertThat(t.actor("nobody").canOpen("/self/credentials")).isFalse();
        assertThatThrownBy(() -> t.actor("nobody").selfCredentials().open())
                .hasMessageContaining("may not open /self/credentials");
        assertThatThrownBy(() -> t.actor("nobody").selfCredentials().currentPassword("Initial.12345")
                .newPassword("Changed.12345").submit()).hasMessageContaining("may not open /self/credentials");
        assertThat(password(t, "nobody")).isEqualTo("Initial.12345");
        assertThat(t.actor("jdoe").canOpen("/self/credentials")).isTrue();
    }

    /**
     * The page has a row of the linked account, selected by default or not as the line decides (4.0: not selected,
     * its icon; 4.4: selected, its check box; 4.8+: selected, the table not listed until "Change password on
     * individual systems" is ticked, which a selection ticks): left unselected its password stays, selected it changes.
     */
    @Test
    void aLinkedAccountIsOfferedAndCanBeLeftUnselected(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        setUp(t);
        withAnAccount(t, dir);

        SelfCredentialsPage.View page = t.actor("jdoe").selfCredentials().open();
        SelfCredentialsPage.Target account = page.target("LDAP");
        assertThat(account.isUser()).isFalse();
        assertThat(account.name()).isEqualTo(JDOE_DN);
        assertThat(account.selected()).isEqualTo(!t.line().equals("4.0"));
        assertThat(page.listsTargets()).isEqualTo(!Lines.atLeast(t.midpoint(), "4.8"));
        assertThat(account.selectable()).isEqualTo(!Lines.atLeast(t.midpoint(), "4.8"));

        t.actor("jdoe").selfCredentials().deselect("LDAP").currentPassword("Initial.12345")
                .newPassword("Changed.12345").submit().saves();
        assertThat(password(t, "jdoe")).isEqualTo("Changed.12345");
        assertThat(t.midpoint().target("LDAP").password(JDOE_DN)).isNull();

        SelfCredentialsPage.Submit selected = t.actor("jdoe").selfCredentials().select("LDAP")
                .currentPassword("Changed.12345").newPassword("Again.Changed.12345").submit().saves();
        if (!t.line().equals("4.0")) {
            // 4.0 goes to the home page after saving; 4.4+ show each row's result
            assertThat(selected.page().target("LDAP").result()).startsWith("SUCCESS");
        }
        assertThat(password(t, "jdoe")).isEqualTo("Again.Changed.12345");
        assertThat(t.midpoint().target("LDAP").password(JDOE_DN)).isEqualTo("Again.Changed.12345");
    }

    /**
     * Saved without a selection: the rows as the page has them by default. 4.0 leaves the account (not selected);
     * 4.4 saves it (selected); 4.8+ too, the table not listed (its rows saved as selected).
     */
    @Test
    void savingWithoutASelectionSavesTheDefaultTargets(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        setUp(t);
        withAnAccount(t, dir);

        SelfCredentialsPage.View page = t.actor("jdoe").selfCredentials().open();
        assertThat(page.target("LDAP").selected()).isEqualTo(!t.line().equals("4.0"));
        if (Lines.atLeast(t.midpoint(), "4.8")) {
            assertThat(page.target("LDAP").note()).contains("is not ticked");
        }

        t.actor("jdoe").selfCredentials().currentPassword("Initial.12345").newPassword("Changed.12345").submit()
                .saves();
        assertThat(password(t, "jdoe")).isEqualTo("Changed.12345");
        assertThat(t.midpoint().target("LDAP").password(JDOE_DN))
                .isEqualTo(t.line().equals("4.0") ? null : "Changed.12345");
    }

    /**
     * An account whose password the user's password mapping changes (a password outbound): while the user's own row
     * is selected the page does not let the user deselect it. 4.0: its icon says "by the mapping" and cycles between
     * that and selected; 4.4+: its check box is disabled (the policy).
     */
    @Test
    void anAccountChangedByTheMappingCannotBeDeselected(MidPointConfigTester t, @TempDir Path dir) throws Exception {
        setUp(t);
        withAnAccount(t, dir, "<credentials><password><outbound/></password></credentials>");

        SelfCredentialsPage.Target account = t.actor("jdoe").selfCredentials().open().target("LDAP");
        assertThat(account.byMapping()).isEqualTo(t.line().equals("4.0"));
        assertThatThrownBy(() -> t.actor("jdoe").selfCredentials().deselect("LDAP").open())
                .hasMessageContaining(t.line().equals("4.0") ? "changes by the mapping" : "check box is disabled");
    }
}
