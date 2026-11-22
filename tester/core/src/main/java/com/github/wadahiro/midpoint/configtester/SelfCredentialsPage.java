package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * The user's own credentials page as the GUI opens it for them ({@link Actor#selfCredentials}: the side menu's
 * Credentials, {@code /self/credentials}, {@code #selfAll} or {@code #selfCredentials}), run by midPoint's GUI with the
 * user logged in: the password tab of the line's page (4.0 and 4.4 {@code PageSelfCredentials} with its change
 * password panel and Save; 4.8+ {@code PageSelfCredentials} with its {@code PropagatePasswordPanel} and Change
 * password). What the page shows is midPoint's: the current password field as the security policy's
 * {@code passwordChangeSecurity} asks for it, the targets (the user themself and their linked accounts, by resource,
 * selected or not as the page has them), the requirements of the password policy for the password typed; its Save
 * executes the page's own changes through the model as the user (the user's authorizations, the password policy and
 * its history, the self-service channel): a save the password policy or the user's authorizations refuse changes
 * nothing, a partial error (4.4+: one target failed) may still have changed the user's own password, with
 * {@link Submit#saved} false.
 *
 * <pre>{@code
 * t.actor("jdoe").selfCredentials().currentPassword("Old.1234").newPassword("New.12345").submit().saves()
 * t.actor("jdoe").selfCredentials().newPassword("short").submit().rejectsWithMessageContaining("at least")
 * t.actor("jdoe").selfCredentials().open().target("LDAP").selected()
 * t.actor("jdoe").selfCredentials().deselect("LDAP").newPassword("New.12345").submit().saves()
 * }</pre>
 *
 * Immutable: each input returns a new page, each {@link #open} and {@link #submit} opens the page anew.
 */
public final class SelfCredentialsPage {

    private final MidPointConfigTester tester;
    private final String user;
    private final String currentPassword;
    private final String newPassword;
    private final String repeatedPassword;
    private final Map<String, Boolean> selections;

    SelfCredentialsPage(MidPointConfigTester tester, String user) {
        this(tester, user, null, null, null, Map.of());
    }

    private SelfCredentialsPage(MidPointConfigTester tester, String user, String currentPassword, String newPassword,
            String repeatedPassword, Map<String, Boolean> selections) {
        this.tester = tester;
        this.user = user;
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
        this.repeatedPassword = repeatedPassword;
        this.selections = new LinkedHashMap<>(selections);
    }

    /**
     * The current password typed into its field (fails at {@link #submit} when the page does not ask for it: the
     * security policy's {@code passwordChangeSecurity} is {@code none}, {@link View#asksCurrentPassword} false).
     */
    public SelfCredentialsPage currentPassword(String password) {
        return new SelfCredentialsPage(tester, user, password, newPassword, repeatedPassword, selections);
    }

    /** The new password typed into both of its fields. */
    public SelfCredentialsPage newPassword(String password) {
        return new SelfCredentialsPage(tester, user, currentPassword, password, null, selections);
    }

    /** The new password typed into its first field and the repetition into the second (to see a mismatch refused). */
    public SelfCredentialsPage newPassword(String password, String repeated) {
        return new SelfCredentialsPage(tester, user, currentPassword, password, repeated, selections);
    }

    /**
     * The target's row selected, as the user clicks it (4.0: its icon; 4.4+: its check box; 4.8+: "Change password on
     * individual systems" ticked first when the table is hidden). The target by its resource ({@code LDAP}; the
     * user's own row: {@code midPoint}), its name or OID. Fails when the page lists no such row or does not let the user
     * change it (4.4+: a disabled check box, with the page's reason; 4.0: an icon without the password capability, or
     * an account deselected whose password changes by the mapping while the user's own row is selected; 4.8+ with
     * {@code propagationUserControl} {@code mapping}: no check box to tick, so no row).
     */
    public SelfCredentialsPage select(String target) {
        return selection(target, true);
    }

    /** The target's row deselected, as {@link #select}. */
    public SelfCredentialsPage deselect(String target) {
        return selection(target, false);
    }

    private SelfCredentialsPage selection(String target, boolean selected) {
        Map<String, Boolean> more = new LinkedHashMap<>(selections);
        more.remove(target);
        more.put(target, selected);
        return new SelfCredentialsPage(tester, user, currentPassword, newPassword, repeatedPassword, more);
    }

    /**
     * The page as it shows after the inputs (the selections made, the new password typed and left), nothing saved:
     * whether it asks for the current password, its targets, the password policy's requirements.
     */
    public View open() {
        tester.actor(user).explainAuthorizations();
        View view = new View(gui().credentials(spec(), false));
        tester.read("credentials page of " + user, view::toString);
        return view;
    }

    /**
     * Save pressed (4.0, 4.4: the page's Save; 4.8+: Change password) with the inputs: the page's own checks (the
     * current password, an empty or mistyped password, no target), then its changes executed through the model as the
     * user: {@code submit().saves()}, {@code submit().rejectsWithMessageContaining("...")}.
     */
    public Submit submit() {
        tester.actor(user).explainAuthorizations();
        return new Submit(new View(gui().credentials(spec(), true)));
    }

    private GuiForms gui() {
        return GuiForms.of(tester.deployment().api());
    }

    private GuiForms.CredentialsSpec spec() {
        return new GuiForms.CredentialsSpec(user, currentPassword, newPassword, repeatedPassword, selections);
    }

    /** The credentials page as the GUI rendered it for the user. */
    public static final class View {

        private final GuiForms.Credentials page;
        private final List<Target> targets = new ArrayList<>();

        View(GuiForms.Credentials page) {
            this.page = page;
            page.targets.forEach(target -> targets.add(new Target(target)));
        }

        /** Whether the page shows the current password field (and so needs it to save). */
        public boolean asksCurrentPassword() {
            return page.asksCurrentPassword;
        }

        /**
         * Whether the browser shows the page's table of targets (4.8+: "Change password on individual systems" ticked,
         * by the policy's {@code propagationUserControl} or a selection; 4.0, 4.4: unless the propagation is
         * {@code mapping}). Not listed, {@link #targets} are the rows the page holds for its save, selected as the
         * save takes them (4.8+: the user's own row, an account with the password capability or a password outbound
         * mapping), each with a note why it is not listed.
         */
        public boolean listsTargets() {
            return page.targetsShown;
        }

        /** The rows of the page's table of targets: the user themself (midPoint), then their accounts by resource. */
        public List<Target> targets() {
            return targets;
        }

        /** The row of the target (its resource, name or OID); fails when the page has none. */
        public Target target(String target) {
            for (Target row : targets) {
                if (target.equals(row.resource()) || target.equals(row.name()) || target.equals(row.oid())) {
                    return row;
                }
            }
            throw new AssertionError("The credentials page lists no target " + target + "; " + this);
        }

        /**
         * The requirements of the user's password policy as the page lists them for the password typed (4.0: none);
         * the rows' own policies: {@link Target#requirements}.
         */
        public List<Requirement> requirements() {
            List<Requirement> out = new ArrayList<>();
            page.requirements.forEach(r -> out.add(new Requirement(r.text, r.satisfied)));
            return out;
        }

        /** The requirements the typed password does not meet. */
        public List<String> unmetRequirements() {
            List<String> out = new ArrayList<>();
            page.requirements.forEach(r -> {
                if (!r.satisfied) {
                    out.add(r.text);
                }
            });
            return out;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("the credentials page ")
                    .append(page.asksCurrentPassword ? "asks" : "does not ask").append(" for the current password")
                    .append(page.targetsShown ? "; its targets:" : "; its targets (not listed):");
            for (Target target : targets) {
                sb.append("\n  ").append(target);
            }
            if (!page.requirements.isEmpty()) {
                sb.append("\n  requirements:");
                page.requirements.forEach(r -> sb.append("\n    ").append(r.satisfied ? "[x] " : "[ ] ").append(r.text));
            }
            return sb.toString();
        }
    }

    /** A row of the page's table of targets. */
    public static final class Target {

        private final GuiForms.CredentialsTarget row;

        Target(GuiForms.CredentialsTarget row) {
            this.row = row;
        }

        /** The OID of the user or the account. */
        public String oid() {
            return row.oid;
        }

        /** The name column: the user's name, the account's. */
        public String name() {
            return row.name;
        }

        /** The resource column: the resource's name; for the user's own row the page's name of midPoint. */
        public String resource() {
            return row.resource;
        }

        /** Whether the row is the user themself, not an account. */
        public boolean isUser() {
            return row.user;
        }

        /** Whether the row is selected, as the page shows it (4.0: its icon; 4.4+: its check box). */
        public boolean selected() {
            return row.selected;
        }

        /** Whether the user may change the selection. */
        public boolean selectable() {
            return row.selectable;
        }

        /** 4.0: the account's password changes through the user's password mapping (its icon says so). */
        public boolean byMapping() {
            return row.byMapping;
        }

        /** Why the row cannot be changed, as the page says it, or null. */
        public String note() {
            return row.note;
        }

        /**
         * The row's result after a save (status and message), or null: none. The page holds it whether or not the
         * browser shows it ({@link Submit#rowResultsShown}).
         */
        public String result() {
            return row.result;
        }

        /**
         * The requirements of the row's own password policy as its validation column lists them for the password
         * typed (4.4+; the account's resource's policy), empty: none listed.
         */
        public List<Requirement> requirements() {
            List<Requirement> out = new ArrayList<>();
            row.requirements.forEach(r -> out.add(new Requirement(r.text, r.satisfied)));
            return out;
        }

        @Override
        public String toString() {
            return row.resource + " (" + row.name + "): " + (row.selected ? "selected" : "not selected")
                    + (row.byMapping ? ", by the mapping" : "") + (row.selectable ? "" : ", not selectable")
                    + (row.note == null ? "" : " (" + row.note + ")") + (row.result == null ? "" : "; " + row.result);
        }
    }

    /** A requirement of the password policy and whether the typed password meets it. */
    public static final class Requirement {

        private final String text;
        private final boolean satisfied;

        Requirement(String text, boolean satisfied) {
            this.text = text;
            this.satisfied = satisfied;
        }

        public String text() {
            return text;
        }

        public boolean satisfied() {
            return satisfied;
        }

        @Override
        public String toString() {
            return (satisfied ? "[x] " : "[ ] ") + text;
        }
    }

    /** The page saved: whether it saved the password, and what it shows. */
    public static final class Submit {

        private final View view;

        Submit(View view) {
            this.view = view;
        }

        /**
         * Whether the page saved the password (the changes executed without an error). False does not always mean
         * nothing changed: a partial error (4.4+) may have changed the user's own password, and 4.0 reports false when
         * the home page it opens after saving is the GUI's error page.
         */
        public boolean saved() {
            return view.page.saved;
        }

        /**
         * What the browser shows after saving, in its order: its feedback (4.0, 4.4), its toasts (4.8+: each as
         * {@code "Save password: Password not changed. Old password is incorrect."} and its body's lines alone,
         * {@code "Password not changed. Old password is incorrect."}), the rows' results ({@code LDAP (jdoe): FATAL_ERROR: ...}, 4.4+) when the browser shows them
         * ({@link #rowResultsShown}). 4.8+ with the table not listed, a refused save shows only the toast pointing to
         * the table ("There were some errors during processing, see result table for details."): the policy's message
         * is then in {@link #targetResults} only.
         */
        public List<String> messages() {
            return view.page.messages;
        }

        /**
         * Whether the browser shows the rows' results: the table was listed before the save, or the page rendered it
         * again (4.8+: a save without an error).
         */
        public boolean rowResultsShown() {
            return view.page.rowResultsShown;
        }

        /**
         * The rows' results the page holds after saving ({@code LDAP (jdoe): FATAL_ERROR: ...}, 4.4+), whether or not
         * the browser shows them ({@link #rowResultsShown}); in {@link #messages} only when it does.
         */
        public List<String> targetResults() {
            List<String> out = new ArrayList<>();
            for (Target target : view.targets) {
                if (target.result() != null) {
                    out.add(target.resource() + " (" + target.name() + "): " + target.result());
                }
            }
            return out;
        }

        /**
         * The page after saving as the tester renders it again: its rows with their results, shown by the browser or
         * not ({@link #rowResultsShown}).
         */
        public View page() {
            return view;
        }

        /** The page saves the password. */
        public Submit saves() {
            if (!saved()) {
                throw new AssertionError("The credentials page does not save: " + messages() + "\n" + view);
            }
            return this;
        }

        /** The page does not save the password. */
        public Submit rejects() {
            if (saved()) {
                throw new AssertionError("The credentials page saves" + (messages().isEmpty() ? "" : ": " + messages()));
            }
            return this;
        }

        /**
         * The page does not save, and shows exactly this message. The lines differ in a message's form (4.8+ toasts
         * "title: body", 4.0 and 4.4 the body only, which 4.8+ also lists): prefer
         * {@link #rejectsWithMessageContaining}.
         */
        public Submit rejectsWith(String message) {
            rejects();
            if (!messages().contains(message)) {
                throw new AssertionError("The credentials page does not show \"" + message + "\"; its messages: "
                        + messages());
            }
            return this;
        }

        /** The page does not save, and shows a message containing this text (reads alike on every line). */
        public Submit rejectsWithMessageContaining(String part) {
            rejects();
            if (messages().stream().noneMatch(m -> m != null && m.contains(part))) {
                throw new AssertionError("The credentials page shows no message containing \"" + part
                        + "\"; its messages: " + messages());
            }
            return this;
        }
    }
}
