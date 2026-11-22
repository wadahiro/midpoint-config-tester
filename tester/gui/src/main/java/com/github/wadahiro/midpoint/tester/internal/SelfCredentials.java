package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.wicket.AttributeModifier;
import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.ajax.AjaxEventBehavior;
import org.apache.wicket.feedback.FeedbackMessage;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.FormComponent;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.IDataProvider;
import org.apache.wicket.util.tester.WicketTester;
import org.apache.wicket.util.tester.WicketTesterHelper;

import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.result.OperationResultStatus;

/**
 * The user's own credentials page driven as the user drives it ({@link GuiForms#credentials}), on the page the line's
 * GUI rendered ({@link GuiLine#selfCredentialsPage}). The line differences are read from the page itself:
 * <ul>
 *   <li>the current password: 4.0 and 4.4 the field {@code oldPassword}, 4.8+ {@code currentPassword}, shown when the
 *       security policy's {@code passwordChangeSecurity} asks for it (none set: asked on every line; 4.4+
 *       {@code oldPasswordIfExists} when the user has a password);</li>
 *   <li>the new password: the password panel's {@code password1} (its change event, as leaving the field) and
 *       {@code password2}, on every line;</li>
 *   <li>the targets: the rows of the page's table, each a {@code PasswordAccountDto} (the user themself, midPoint, and
 *       each linked account); 4.0 selects a row by clicking its icon (selected, deselected, changed through the
 *       mapping, no password capability), 4.4+ by its check box (disabled as the page decides, with its title); 4.8+
 *       shows the table only with "Change password on individual systems" ticked, which a selection ticks first, as the
 *       user must;</li>
 *   <li>the policy's requirements: the page's own {@code PasswordLimitationsPanel} shown for the typed password (4.4
 *       the password panel's {@code validationPanel}, 4.8+ {@code passwordValidationPanel}; 4.0 shows none); a row's
 *       own, its validation column's {@code PasswordPolicyValidationPanel} (4.4+), given to the row;</li>
 *   <li>Save: 4.0 and 4.4 the page's {@code save} (the form's default button), 4.8+ the panel's
 *       {@code changePassword}; saved when the page says so ({@code savedPassword}: 4.4, 4.8+; 4.0: the page goes to
 *       the home page) and no error shows;</li>
 *   <li>the messages: the page's feedback (4.0, 4.4), the toasts of the last response (4.8+: the JavaScript of
 *       {@code Toast.show}; "title: body", and the body's lines alone), the rows' results (the propagation result
 *       column, 4.4+) when the browser shows them: the table listed before the save, or (4.8+) the panel rendered again
 *       by a save without an error. 4.8+ with the table not listed and the save refused, the browser only shows the
 *       toast pointing to the table; the rows' results stay out of the messages (the targets still hold them).</li>
 * </ul>
 */
final class SelfCredentials {

    /** 4.0's icons of a row (ChangePasswordPanel): selected, deselected, changed by the mapping, no capability. */
    private static final String SELECTED_40 = "fa fa-check-square-o";
    private static final String PROPAGATED_40 = "fa fa-sign-out";
    private static final String NO_CAPABILITY_40 = "fa fa-square";

    private final WicketTester tester;
    private final Function<Object, String> translate;
    private final List<String> messages = new ArrayList<>();
    /** Whether the browser shows the rows' results after the save ({@link GuiForms.Credentials#rowResultsShown}). */
    private boolean rowResultsShown;

    SelfCredentials(WicketTester tester, Function<Object, String> translate) {
        this.tester = tester;
        this.translate = translate;
    }

    GuiForms.Credentials drive(GuiForms.CredentialsSpec spec, boolean save) {
        for (Map.Entry<String, Boolean> selection : spec.selections.entrySet()) {
            select(selection.getKey(), selection.getValue());
        }
        if (spec.newPassword != null) {
            // typed and left: the change event takes it into the model, the page's requirements follow it
            typeNew(spec.newPassword);
        }
        boolean saved = false;
        if (save) {
            saved = save(spec);
        }
        return read(save, saved);
    }

    // ---- reading the page

    private GuiForms.Credentials read(boolean submitted, boolean saved) {
        Page page = page();
        Component current = currentPasswordField(page);
        boolean asks = current != null && current.isVisibleInHierarchy();
        Component individual = GuiLine.descendant(page, "propagatePasswordCheckbox");
        boolean offered = individual != null && individual.isVisibleInHierarchy();
        List<Row> rows = rows();
        boolean shown = !rows.isEmpty();
        List<GuiForms.CredentialsTarget> targets = new ArrayList<>();
        if (shown) {
            rows.forEach(row -> targets.add(row.target()));
        } else {
            // 4.8+: the table hidden: the rows the page holds, selected as its save takes them
            // (PropagatePasswordPanel#getAccountsListToChangePassword: selected, and the user's own row, a password
            // capability or a password outbound mapping; the check boxes' own rules apply only once they render)
            String note = offered ? "not listed: \"Change password on individual systems\" is not ticked"
                    : "not listed: the page offers no \"Change password on individual systems\" check box (the security"
                    + " policy's propagationUserControl is mapping), so no row can be selected or deselected";
            for (Object dto : hiddenRows(page)) {
                boolean user = Boolean.TRUE.equals(call(dto, "isMidpoint"));
                boolean selected = Boolean.TRUE.equals(call(dto, "isSelected"))
                        && (user || Boolean.TRUE.equals(call(dto, "isPasswordCapabilityEnabled"))
                                || Boolean.TRUE.equals(call(dto, "isPasswordOutbound")));
                targets.add(new GuiForms.CredentialsTarget(string(dto, "getOid"), string(dto, "getDisplayName"),
                        string(dto, "getResourceName"), user, selected, false, false, note, null,
                        new ArrayList<>()));
            }
        }
        List<String> shownMessages = new ArrayList<>(messages);
        if (submitted && rowResultsShown) {
            for (GuiForms.CredentialsTarget target : targets) {
                if (target.result != null) {
                    shownMessages.add(target.resource + " (" + target.name + "): " + target.result);
                }
            }
        }
        // 4.8+: a refused save does not render the hidden table again; the tester's rendering does, the browser not
        boolean listed = shown && (!submitted || rowResultsShown);
        return new GuiForms.Credentials(asks, listed, offered, targets, requirements(page), submitted, saved,
                submitted && rowResultsShown, shownMessages);
    }

    /** A row of the table of targets as rendered: its DTO and the component that changes its selection. */
    private final class Row {
        final Item<?> item;
        final Object dto;

        Row(Item<?> item, Object dto) {
            this.item = item;
            this.dto = dto;
        }

        /**
         * 4.4+: the row's check box (IsolatedCheckBoxPanel's {@code check}); 4.0: none (its rows' check boxes are the
         * disabled Enabled column, the selection is the icon).
         */
        Component checkBox() {
            if (icon() != null) {
                return null;
            }
            Component panel = first(item, c -> named(c, "IsolatedCheckBoxPanel"));
            return panel == null ? null : ((MarkupContainer) panel).get("check");
        }

        /** 4.0: the row's icon, clicked to change the selection (an ImagePanel with a click event). */
        Component icon() {
            return first(item, c -> named(c, "ImagePanel")
                    && !WicketTesterHelper.findAjaxEventBehaviors(c, "click").isEmpty());
        }

        boolean selected() {
            Component check = checkBox();
            if (check != null) {
                return Boolean.TRUE.equals(check.getDefaultModelObject());
            }
            return SELECTED_40.equals(call(dto, "getCssClass"));
        }

        GuiForms.CredentialsTarget target() {
            Component check = checkBox();
            boolean selectable;
            String note = null;
            boolean byMapping = false;
            if (check != null) {
                selectable = check.isVisibleInHierarchy() && check.isEnabledInHierarchy();
                note = title(check.getParent());
            } else {
                Component icon = icon();
                selectable = icon != null && icon.isEnabledInHierarchy();
                String css = String.valueOf(call(dto, "getCssClass"));
                byMapping = PROPAGATED_40.equals(css);
                if (NO_CAPABILITY_40.equals(css)) {
                    note = "no password capability";
                }
            }
            return new GuiForms.CredentialsTarget(string(dto, "getOid"), string(dto, "getDisplayName"),
                    string(dto, "getResourceName"), Boolean.TRUE.equals(call(dto, "isMidpoint")), selected(),
                    selectable, byMapping, note, result(), requirements());
        }

        /** The row's own policy requirements: its validation column's PasswordPolicyValidationPanel (4.4+). */
        List<GuiForms.PasswordRequirement> requirements() {
            List<GuiForms.PasswordRequirement> out = new ArrayList<>();
            Component panel = first(item, c -> named(c, "PasswordPolicyValidationPanel"));
            if (panel != null && panel.isVisibleInHierarchy()) {
                limitations(modelObject(panel), out, new LinkedHashSet<>());
            }
            return out;
        }

        /** The propagation result column (ColumnResultPanel, 4.4+), or null: nothing shown. */
        String result() {
            Component panel = first(item, c -> named(c, "ColumnResultPanel"));
            if (panel == null) {
                return null;
            }
            Object model = modelObject(panel);
            if (!(model instanceof OperationResult)) {
                return null;
            }
            OperationResult result = (OperationResult) model;
            if (result.getStatus() == null || result.getStatus() == OperationResultStatus.UNKNOWN) {
                return null;
            }
            StringBuilder out = new StringBuilder(String.valueOf(result.getStatus()));
            String friendly = result.getUserFriendlyMessage() == null ? null
                    : translate.apply(result.getUserFriendlyMessage());
            if (friendly != null && !friendly.isEmpty()) {
                out.append(": ").append(friendly);
            }
            if (result.getMessage() != null && !result.getMessage().equals(friendly)) {
                out.append(friendly != null && !friendly.isEmpty() ? "; " : ": ").append(result.getMessage());
            }
            return out.toString();
        }
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        page().visitChildren(Item.class, (component, visit) -> {
            Object dto = component.getDefaultModelObject();
            if (dto != null && dto.getClass().getSimpleName().equals("PasswordAccountDto")) {
                visit.dontGoDeeper();
                if (component.isVisibleInHierarchy()) {
                    out.add(new Row((Item<?>) component, dto));
                }
            }
        });
        return out;
    }

    /** 4.8+: the DTOs of the hidden table, from the panel's data provider (PropagatePasswordPanel's provider). */
    private static List<Object> hiddenRows(Page page) {
        List<Object> out = new ArrayList<>();
        Component panel = first(page, c -> named(c, "PropagatePasswordPanel"));
        if (panel == null) {
            return out;
        }
        Object provider = field(panel, "provider");
        if (provider instanceof IDataProvider) {
            IDataProvider<?> data = (IDataProvider<?>) provider;
            Iterator<?> it = data.iterator(0, data.size());
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }

    /**
     * The requirements of the policy the page lists for the user's password: its own PasswordLimitationsPanel shown
     * (4.4: the password panel's, 4.8+: ChangePasswordPanel's {@code passwordValidationPanel}; 4.0: none), not the
     * rows' popovers (PasswordPolicyValidationPanel, each row's own: {@link Row#requirements}).
     */
    private List<GuiForms.PasswordRequirement> requirements(Page page) {
        List<GuiForms.PasswordRequirement> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        page.visitChildren((component, visit) -> {
            if (named(component, "PasswordPolicyValidationPanel")) {
                visit.dontGoDeeper();
                return;
            }
            if (!named(component, "PasswordLimitationsPanel") || !component.isVisibleInHierarchy()) {
                return;
            }
            visit.dontGoDeeper();
            limitations(modelObject(component), out, seen);
        });
        return out;
    }

    /** The limitations of a panel's model (StringLimitationResult) as the panel shows them. */
    private void limitations(Object model, List<GuiForms.PasswordRequirement> out, Set<String> seen) {
        if (!(model instanceof List)) {
            return;
        }
        for (Object limitation : (List<?>) model) {
            // its name and help, as the panel shows them ("Characters: Must be between 10 and 64 characters")
            Object name = call(limitation, "getName");
            Object help = call(limitation, "getHelp");
            String text = name == null ? String.valueOf(limitation) : translate.apply(name);
            String helpText = help == null ? null : translate.apply(help);
            if (helpText != null && !helpText.isEmpty() && !helpText.equals(text)) {
                text = text + ": " + helpText;
            }
            if (seen.add(text)) {
                out.add(new GuiForms.PasswordRequirement(text, Boolean.TRUE.equals(call(limitation, "isSuccess"))));
            }
        }
    }

    // ---- the user's actions

    /** The row of the target (its name or resource) selected or deselected, as the user clicks it. */
    private void select(String target, boolean selected) {
        Row row = row(target);
        if (row == null) {
            Component individual = GuiLine.descendant(page(), "propagatePasswordCheckbox");
            if (individual != null && individual.isVisibleInHierarchy()) {
                // 4.8+: the table shows once "Change password on individual systems" is ticked
                Component check = ((MarkupContainer) individual).get("check");
                if (!Boolean.TRUE.equals(check.getDefaultModelObject())) {
                    click(check, true);
                }
                row = row(target);
            }
        }
        if (row == null && rows().isEmpty() && !hiddenRows(page()).isEmpty()) {
            // 4.8+ with propagationUserControl mapping: the table hidden and no check box to list it
            throw new AssertionError("Cannot " + (selected ? "select " : "deselect ") + target
                    + ": the credentials page does not list its targets and offers no \"Change password on individual"
                    + " systems\" check box (the security policy's propagationUserControl is mapping)");
        }
        if (row == null) {
            List<String> names = new ArrayList<>();
            rows().forEach(r -> names.add(describe(r.dto)));
            throw new AssertionError("Cannot " + (selected ? "select " : "deselect ") + target
                    + ": the credentials page lists no such target; it lists: "
                    + (names.isEmpty() ? "none" : String.join(", ", names)));
        }
        if (!selected && row.checkBox() == null && PROPAGATED_40.equals(String.valueOf(call(row.dto, "getCssClass")))) {
            // 4.0: its icon cycles between selected and by the mapping while the user's own row is selected
            throw new AssertionError("Cannot deselect " + describe(row.dto) + ": its password changes by the mapping"
                    + " (its icon) while the user's own row is selected; the page does not let the user deselect it");
        }
        if (row.selected() == selected) {
            return;
        }
        Component check = row.checkBox();
        if (check != null) {
            if (!check.isEnabledInHierarchy()) {
                String note = title(check.getParent());
                throw new AssertionError("Cannot " + (selected ? "select " : "deselect ") + describe(row.dto)
                        + ": its check box is disabled" + (note == null || note.isEmpty() ? "" : " (" + note + ")"));
            }
            click(check, selected);
            return;
        }
        // 4.0: the icon cycles (selected, deselected; an account with a password mapping: by the mapping, selected)
        for (int i = 0; i < 3; i++) {
            Component icon = row.icon();
            if (icon == null || !icon.isEnabledInHierarchy()) {
                throw new AssertionError("Cannot " + (selected ? "select " : "deselect ") + describe(row.dto)
                        + ": its icon is disabled (no password capability)");
            }
            tester.executeAjaxEvent(icon, "click");
            fail(null, "clicking " + describe(row.dto));
            row = row(target);
            if (row.selected() == selected) {
                return;
            }
        }
        throw new AssertionError("Cannot " + (selected ? "select " : "deselect ") + target + ": its icon does not");
    }

    private Row row(String target) {
        Row found = null;
        for (Row row : rows()) {
            if (target.equals(string(row.dto, "getDisplayName")) || target.equals(string(row.dto, "getResourceName"))
                    || target.equals(string(row.dto, "getOid"))) {
                if (found != null) {
                    throw new AssertionError("Two targets of the credentials page are " + target + ": "
                            + describe(found.dto) + ", " + describe(row.dto) + "; name one by its OID");
                }
                found = row;
            }
        }
        return found;
    }

    /** A check box ticked or unticked and its click event fired, as the user clicks it. */
    private void click(Component check, boolean value) {
        Form<?> form = mainForm();
        String relative = check.getPageRelativePath().substring(form.getPageRelativePath().length() + 1);
        tester.newFormTester(form.getPageRelativePath(), false).setValue(relative, value);
        List<AjaxEventBehavior> events = WicketTesterHelper.findAjaxEventBehaviors(check, "click");
        if (events.isEmpty()) {
            events = WicketTesterHelper.findAjaxEventBehaviors(check, "change");
        }
        if (events.isEmpty()) {
            throw new AssertionError("The check box " + check.getPageRelativePath() + " has no click event");
        }
        tester.executeBehavior(events.get(0));
        fail(null, "clicking " + check.getPageRelativePath());
    }

    /** The new password typed into its first field and left (the field's change event), as the user types it. */
    private void typeNew(String value) {
        Component field = passwordField("password1");
        Form<?> form = mainForm();
        String relative = field.getPageRelativePath().substring(form.getPageRelativePath().length() + 1);
        for (AjaxEventBehavior change : WicketTesterHelper.findAjaxEventBehaviors(field, "change")) {
            tester.newFormTester(form.getPageRelativePath(), false).setValue(relative, value);
            tester.executeBehavior(change);
            fail(null, "typing the new password");
        }
        // the requirements follow the value typed (4.4+: the field's keyup)
        List<AjaxEventBehavior> keyup = WicketTesterHelper.findAjaxEventBehaviors(field, "keyup");
        if (!keyup.isEmpty()) {
            tester.newFormTester(form.getPageRelativePath(), false).setValue(relative, value);
            tester.executeBehavior(keyup.get(0));
            fail(null, "typing the new password");
        }
    }

    /**
     * Save pressed (4.0, 4.4: the page's {@code save}; 4.8+: the panel's {@code changePassword}) with the fields as
     * typed: the current password, the new password, its repetition (the new password again unless given).
     */
    private boolean save(GuiForms.CredentialsSpec spec) {
        Page page = page();
        Form<?> form = mainForm();
        Component button = GuiLine.descendant(page, "changePassword");
        if (button == null) {
            button = GuiLine.descendant(form, "save");
        }
        if (button == null || !button.isVisibleInHierarchy() || !button.isEnabledInHierarchy()) {
            throw new AssertionError("The credentials page shows no " + (button == null ? "" : "enabled ")
                    + "Save / Change password button");
        }
        String formPath = form.getPageRelativePath();
        org.apache.wicket.util.tester.FormTester fields = tester.newFormTester(formPath, false);
        Component current = currentPasswordField(page);
        if (spec.currentPassword != null) {
            if (current == null || !current.isVisibleInHierarchy()) {
                throw new AssertionError("Cannot type the current password: the credentials page does not ask for it"
                        + " (the security policy's passwordChangeSecurity)");
            }
            fields.setValue(relative(form, current), spec.currentPassword);
        }
        if (spec.newPassword != null) {
            fields.setValue(relative(form, passwordField("password1")), spec.newPassword);
            fields.setValue(relative(form, passwordField("password2")),
                    spec.repeatedPassword != null ? spec.repeatedPassword : spec.newPassword);
        }
        boolean listedBefore = !rows().isEmpty();
        // every feedback message of the clicks and typing before (the session's and the components'): only the save's
        tester.clearFeedbackMessages();
        tester.executeAjaxEvent(button, "click");
        String failed = GuiFormsImpl.errorPageOf(tester.getLastRenderedPage());
        if (failed != null) {
            messages.add("Saving opened the GUI's error page " + failed);
            return false;
        }
        boolean errors = false;
        for (FeedbackMessage message : tester.getFeedbackMessages(m -> true)) {
            if (message.getLevel() >= FeedbackMessage.ERROR) {
                errors = true;
            }
            add(GuiFormsImpl.textOf(message.getMessage()));
        }
        for (Toast toast : toasts(tester.getLastResponseAsString())) {
            if (toast.error) {
                errors = true;
            }
            add(toast.text);
            if (toast.body != null) {
                // 4.4 and 4.0 show the body only; 4.8+ a toast's body may be several lines (the fields' messages)
                for (String line : toast.body.split("\n")) {
                    add(line.trim());
                }
            }
        }
        Boolean savedPassword = savedPassword(tester.getLastRenderedPage());
        // 4.8+: a save without an error renders ChangePasswordPanel again, its table with the results; 4.4 renders only
        // its Save button, and after an error 4.4+ update only the result column of a table already shown
        rowResultsShown = listedBefore || Boolean.TRUE.equals(savedPassword)
                && !(field(tester.getLastRenderedPage(), "savedPassword") instanceof Boolean);
        boolean saved = savedPassword != null ? savedPassword && !errors
                // 4.0: a saved password sends the user to the home page
                : !errors && tester.getLastRenderedPage().getClass() != page.getClass();
        if (tester.getLastRenderedPage().getClass() == page.getClass()) {
            // the page as the browser shows it rendered again: its table of targets with each row's result (4.8+: the
            // table the toast points to, which an error does not re-render)
            tester.startPage(tester.getLastRenderedPage());
            fail(null, "rendering the credentials page after saving");
        }
        return saved;
    }

    private void add(String message) {
        if (message != null && !message.isEmpty() && !messages.contains(message)) {
            messages.add(message);
        }
    }

    /** 4.4 PageAbstractSelfCredentials's, 4.8+ ChangePasswordPanel's {@code savedPassword}; 4.0: null (none). */
    private static Boolean savedPassword(Page page) {
        Object onPage = field(page, "savedPassword");
        if (onPage instanceof Boolean) {
            return (Boolean) onPage;
        }
        Component panel = first(page, c -> field(c, "savedPassword") instanceof Boolean);
        return panel == null ? null : (Boolean) field(panel, "savedPassword");
    }

    /** A toast the response's JavaScript shows (4.8 to 4.10: {@code Toasts('create', {...})}; 4.11: {@code showToast}). */
    private static final class Toast {
        /** "title: body" (or the one of them given). */
        final String text;
        final String body;
        final boolean error;

        Toast(String text, String body, boolean error) {
            this.text = text;
            this.body = body;
            this.error = error;
        }
    }

    static List<Toast> toasts(String response) {
        List<Toast> out = new ArrayList<>();
        if (response == null) {
            return out;
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String call : new String[] {"Toasts('create', ", "MidPointTheme.showToast("}) {
            int at = 0;
            while ((at = response.indexOf(call, at)) >= 0) {
                at += call.length();
                try (com.fasterxml.jackson.core.JsonParser parser = mapper.getFactory()
                        .createParser(response.substring(at))) {
                    com.fasterxml.jackson.databind.JsonNode toast = mapper.readTree(parser);
                    String title = text(toast, "title");
                    String body = text(toast, "body");
                    String css = (text(toast, "class") == null ? "" : text(toast, "class")) + " "
                            + (text(toast, "className") == null ? "" : text(toast, "className"));
                    String message = title == null ? body : body == null ? title : title + ": " + body;
                    if (message != null) {
                        out.add(new Toast(message.trim(), body, css.contains("danger")));
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    // not a toast's JSON
                }
            }
        }
        return out;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    // ---- components

    private Page page() {
        return tester.getLastRenderedPage();
    }

    private Form<?> mainForm() {
        Component form = GuiLine.descendant(page(), "mainForm");
        if (!(form instanceof Form)) {
            throw new AssertionError(page().getClass().getSimpleName() + " has no main form");
        }
        return (Form<?>) form;
    }

    /** 4.0, 4.4: {@code oldPassword}; 4.8+: {@code currentPassword}. */
    private static Component currentPasswordField(Page page) {
        Component current = GuiLine.descendant(page, "currentPassword");
        return current instanceof FormComponent ? current : GuiLine.descendant(page, "oldPassword");
    }

    private Component passwordField(String id) {
        Component panel = GuiLine.descendant(page(), "passwordPanel");
        Component field = panel instanceof MarkupContainer ? GuiLine.descendant((MarkupContainer) panel, id) : null;
        if (field == null) {
            throw new AssertionError("The credentials page has no password field " + id);
        }
        if (!field.isVisibleInHierarchy() || !field.isEnabledInHierarchy()) {
            throw new AssertionError("The credentials page's password field " + id + " is "
                    + (field.isVisibleInHierarchy() ? "disabled" : "hidden") + " (the password saved already?)");
        }
        return field;
    }

    private static String relative(Form<?> form, Component component) {
        return component.getPageRelativePath().substring(form.getPageRelativePath().length() + 1);
    }

    private void fail(Object ignored, String what) {
        String failed = GuiFormsImpl.errorPageOf(tester.getLastRenderedPage());
        if (failed != null) {
            throw new AssertionError(what + " on the credentials page opened the GUI's error page " + failed);
        }
    }

    /** The title the page gives the component (AttributeModifier / AttributeAppender of {@code title}), or null. */
    private static String title(Component component) {
        if (component == null) {
            return null;
        }
        for (AttributeModifier modifier : component.getBehaviors(AttributeModifier.class)) {
            if ("title".equals(modifier.getAttribute())) {
                try {
                    // its replaceModel (getReplaceModel is protected)
                    Object model = field(modifier, "replaceModel");
                    Object value = model instanceof org.apache.wicket.model.IModel
                            ? ((org.apache.wicket.model.IModel<?>) model).getObject() : null;
                    String text = value == null ? null : String.valueOf(value).trim();
                    return text == null || text.isEmpty() ? null : text;
                } catch (RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String describe(Object dto) {
        return string(dto, "getResourceName") + " (" + string(dto, "getDisplayName") + ")";
    }

    private static Component first(MarkupContainer container, java.util.function.Predicate<Component> test) {
        Component[] found = new Component[1];
        container.visitChildren((component, visit) -> {
            if (test.test(component)) {
                found[0] = component;
                visit.stop();
            }
        });
        return found[0];
    }

    /** Whether the component's class (an anonymous one: the class it extends) has the simple name. */
    private static boolean named(Component component, String simpleName) {
        for (Class<?> c = component.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /** The component's model object: a BasePanel's own model ({@code getModelObject}), else its default model's. */
    private static Object modelObject(Component component) {
        try {
            Object model = call(component, "getModelObject");
            return model != null ? model : component.getDefaultModelObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // a superclass's
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Object call(Object target, String method) {
        try {
            return target == null ? null : target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String string(Object target, String method) {
        Object value = call(target, method);
        return value == null ? null : String.valueOf(value);
    }
}
