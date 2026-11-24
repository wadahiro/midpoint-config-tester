package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * An object's form in midPoint's GUI, run by the GUI itself: {@link NewObjectForm} (a new object) and
 * {@link EditObjectForm} (an existing one). midPoint evaluates an object template's item validation
 * ({@code item/validation/server}) only in the GUI's forms, never in the model's operations; this opens the object's
 * details page in the GUI (in the JVM, WicketTester: no browser, no web server), as the user, types the values into
 * its fields and saves it:
 * <ul>
 *   <li>{@code set} types a value into a field, or (a collection) the values into a multi-valued item's value rows,
 *       as the GUI's add and remove buttons of the item make them;</li>
 *   <li>{@link #validate} types a value into a field and leaves it: what the field's validator says (its
 *       {@code object} is the object before the value: a new object's other items typed in, an existing object's
 *       stored values; 4.0 validates a text field twice, the second time with the value in {@code object});</li>
 *   <li>{@link #submit} presses Save: every field's validator again (those not typed into with their value, an
 *       empty one with {@code input} null), then on 4.4 and later the validation on saving
 *       (FormWrapperValidator: the items whose field said nothing, with the values in {@code object}), then the
 *       changes executed through the model as the user (the object stored, or the operation's error);</li>
 *   <li>{@link #tabs} reads the page's tabs: those the user sees and those the GUI hides from them, with why
 *       ({@code tabs().hasNoTab("Governance")}).</li>
 * </ul>
 * The user ({@link Actor#newObjectForm}, {@link Actor#editForm}; without one, the administrator) is logged in as the
 * tester logs a user in; the page's URL is decided first as midPoint's security filter decides it (a user who may not
 * open the page fails the form); the page shows the items as the GUI shows them to the user (the items they may not
 * see or change have no field). Messages are in the JVM's locale. midPoint's GUI comes with the tester
 * (docs/guide/gui-and-authorizations.md). Immutable: {@code set} (and every method recording a change:
 * {@code add}, {@code remove}, {@code unassign}, {@code uninduce}, {@code archetype}, {@code parent},
 * {@code memberOf}) returns a new form and leaves this one as it was; each {@code validate} and {@code submit} opens
 * the page anew. A change whose form is dropped is not lost silently: {@link #submit} of a form from which a form was
 * derived that nothing used (no {@code submit}, {@code validate}, {@code tabs}, {@code assignments} or
 * {@code inducements} of it or of a form derived from it) fails ({@code IllegalStateException}) naming the dropped
 * changes, rather than pressing Save without them.
 */
public abstract class ObjectForm {

    final MidPointConfigTester tester;
    final Class<? extends ObjectType> type;
    final String user;
    final Map<String, Object> items;
    /** The form this one was derived from by a recorded change, or null. */
    private ObjectForm parent;
    /** The change that derived this form from {@link #parent} ({@code unassign(RoleType team-1)}), or null. */
    private String change;
    /** The forms derived from this one, in order. */
    private final List<ObjectForm> derived = new ArrayList<>();
    /** Whether this form or a form derived from it was used: submitted, validated, its tabs or rows read. */
    private boolean used;

    ObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String user, Map<String, Object> items) {
        this.tester = tester;
        this.type = type;
        this.user = user;
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    abstract GuiForms.Spec spec();

    /** {@code form}, derived from this one by {@code change}: tracked for {@link #submit}'s check of dropped changes. */
    <F extends ObjectForm> F derive(F form, String change) {
        ObjectForm child = form;
        child.parent = this;
        child.change = change;
        derived.add(child);
        return form;
    }

    /** This form and every form it was derived from used. */
    void use() {
        for (ObjectForm form = this; form != null && !form.used; form = form.parent) {
            form.used = true;
        }
    }

    /**
     * Fails when a form derived from this one was never used: its change dropped (the result of {@code unassign},
     * {@code set}... not kept), which a Save of this form would not make.
     */
    private void checkNoDroppedChange() {
        List<String> dropped = new ArrayList<>();
        for (ObjectForm form : derived) {
            if (!form.used) {
                dropped.add(form.change);
            }
        }
        if (dropped.isEmpty()) {
            return;
        }
        List<String> recorded = new ArrayList<>();
        for (ObjectForm form = this; form.parent != null; form = form.parent) {
            recorded.add(0, form.change);
        }
        throw new IllegalStateException("Submitting the form of " + page() + ", but " + dropped.size()
                + (dropped.size() == 1 ? " change derived from it was" : " changes derived from it were")
                + " dropped: " + String.join(", ", dropped) + ". Each " + String.join("/", changeMethods())
                + " returns a new form and leaves this one as it was: chain the calls"
                + " (form.unassign(a).unassign(b).submit()) or keep the result (form = form.unassign(a)). This form"
                + " records " + (recorded.isEmpty() ? "no change" : String.join(", ", recorded)) + ".");
    }

    /** The methods of the form that record a change. */
    abstract List<String> changeMethods();

    /** The page as failures name it: {@code Org site-1}, {@code a new Org}. */
    abstract String page();

    /** {@code Org} of {@code OrgType}. */
    String kind() {
        String name = type.getSimpleName();
        return name.endsWith("Type") ? name.substring(0, name.length() - 4) : name;
    }

    /** The items with the item's value: a collection is the values of a multi-valued item ({@link GuiForms.ValueRows}). */
    static Map<String, Object> with(Map<String, Object> items, String item, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(items);
        copy.remove(item);
        copy.put(item, value instanceof java.util.Collection
                ? GuiForms.ValueRows.exactly((java.util.Collection<?>) value) : value);
        return copy;
    }

    GuiForms gui() {
        return GuiForms.of(tester.deployment().api());
    }

    static String oidOf(MidPoint api, Class<? extends ObjectType> type, String nameOrOid) {
        ObjectType object = api.find(type, nameOrOid);
        if (object == null) {
            throw new IllegalArgumentException("No " + type.getSimpleName() + " " + nameOrOid);
        }
        return object.getOid();
    }

    /** The value typed into the item's field (its path of local names: {@code identifier}, {@code extension/site}). */
    public Check validate(String item, Object value) {
        if (value instanceof java.util.Collection) {
            throw new IllegalArgumentException("validate types one value into a field; set(\"" + item
                    + "\", values) types several into a multi-valued item's rows");
        }
        use();
        return new Check(item, value, gui().type(spec(), item, value));
    }

    /**
     * Save pressed, the items typed in: {@code submit().saves()}, {@code submit().rejects("identifier")}. Fails
     * ({@code IllegalStateException}) when a change was recorded on a form derived from this one that nothing used:
     * the change was dropped (see the class).
     */
    public Submit submit() {
        checkNoDroppedChange();
        use();
        return new Submit(gui().save(spec()));
    }

    /**
     * The page's tabs as the GUI has them for the user, read from the page opened as the user (as {@link #validate}
     * opens it; once, the claims chain on it): {@code tabs().hasTab("Assignments").hasNoTab("Governance")},
     * {@code tabs().names()}.
     */
    public Tabs tabs() {
        use();
        List<Tab> tabs = new ArrayList<>();
        for (GuiForms.TabInfo info : gui().tabs(spec())) {
            tabs.add(new Tab(info));
        }
        if (user != null) {
            tester.actor(user).explainAuthorizations();
        }
        return new Tabs(user, page(), tabs);
    }

    /**
     * The tabs of an object's details page ({@link #tabs}): those the user sees, in the GUI's order, and those the
     * GUI hides from them, with why ({@link Tab#hiddenBecause}). A tab is named by its label as shown, its label key or
     * its identifier ({@link Tab}); a sub-tab through its tab ({@code tab("Assignments").subtabs()}). A failed claim
     * lists the shown and the hidden tabs, and for a user their authorizations.
     */
    public static final class Tabs {

        private final String user;
        private final String page;
        private final List<Tab> all;

        Tabs(String user, String page, List<Tab> all) {
            this.user = user;
            this.page = page;
            this.all = Collections.unmodifiableList(new ArrayList<>(all));
        }

        /** The labels of the tabs the user sees, in the GUI's order (in the JVM's locale). */
        public List<String> names() {
            List<String> out = new ArrayList<>();
            for (Tab tab : all) {
                if (tab.shown()) {
                    out.add(tab.label());
                }
            }
            return Collections.unmodifiableList(out);
        }

        /** Every tab the page has for the object, shown or hidden, in the GUI's order. */
        public List<Tab> all() {
            return all;
        }

        /** The tab by its label as shown, its label key or its identifier, shown or hidden; none fails. */
        public Tab tab(String tab) {
            Tab found = find(tab, false);
            if (found == null) {
                throw new IllegalArgumentException("The page of " + page + " has no tab " + tab
                        + " (label, key or identifier); " + text());
            }
            return found;
        }

        /** The user sees the tab (by its label, key or identifier). */
        public Tabs hasTab(String tab) {
            if (find(tab, true) == null) {
                throw new AssertionError(subject() + " shows no " + tab + " tab; " + text());
            }
            return this;
        }

        /** The user does not see the tab (by its label, key or identifier): hidden from them, or not on the page. */
        public Tabs hasNoTab(String tab) {
            if (find(tab, true) != null) {
                throw new AssertionError(subject() + " shows a " + tab + " tab; " + text());
            }
            return this;
        }

        /** A shown tab of the name, else (not only shown) a hidden one; null: none. */
        private Tab find(String name, boolean shownOnly) {
            Tab hidden = null;
            for (Tab tab : all) {
                if (tab.is(name)) {
                    if (tab.shown()) {
                        return tab;
                    }
                    hidden = hidden != null ? hidden : tab;
                }
            }
            return shownOnly ? null : hidden;
        }

        /** {@code helpdesk: the page of Org site-1}; without a user {@code The page of Org site-1}. */
        private String subject() {
            return (user != null ? user + ": the page of " : "The page of ") + page;
        }

        /** {@code its tabs: Basic, Members; hidden: Governance (visibility hidden)}. */
        String text() {
            List<String> hidden = new ArrayList<>();
            for (Tab tab : all) {
                if (!tab.shown()) {
                    hidden.add(tab.describe());
                }
            }
            List<String> names = names();
            return "its tabs: " + (names.isEmpty() ? "none" : String.join(", ", names))
                    + (hidden.isEmpty() ? "" : "; hidden: " + String.join(", ", hidden));
        }

        @Override
        public String toString() {
            return "the tabs of the page of " + page + (user != null ? " as " + user : "") + ": " + text();
        }
    }

    /** What the item's field says of a value typed into it. */
    public static final class Check {

        private final String item;
        private final Object value;
        private final GuiForms.Field field;

        Check(String item, Object value, GuiForms.Field field) {
            this.item = item;
            this.value = value;
            this.field = field;
        }

        /** Whether the item has a validation in the page (none: any value passes). */
        public boolean validated() {
            return field.validated;
        }

        /** Whether the field shows no message: the value passes (also an item without a validation). */
        public boolean passed() {
            return field.messages.isEmpty();
        }

        /** The field's first message, or null. */
        public String message() {
            return field.messages.isEmpty() ? null : field.messages.get(0);
        }

        /** The field's messages. */
        public List<String> messages() {
            return field.messages;
        }

        /** Why the page has no field of the item to type into (hidden from the user, read-only), or null. */
        public String absent() {
            return field.absent;
        }

        /** The item has a validation and the value passes it. */
        public Check passes() {
            requireValidation();
            if (!passed()) {
                throw new AssertionError("The form rejects " + item + " = " + value + ": " + field.messages);
            }
            return this;
        }

        /** The item has a validation and the value does not pass it. */
        public Check fails() {
            requireValidation();
            if (passed()) {
                throw new AssertionError("The form accepts " + item + " = " + value);
            }
            return this;
        }

        /** {@link #fails()} with this message. */
        public Check fails(String message) {
            fails();
            if (!field.messages.contains(message)) {
                throw new AssertionError("The form rejects " + item + " = " + value + " with: " + field.messages
                        + "\nexpected: " + message);
            }
            return this;
        }

        /**
         * {@link #fails()} with a message containing this text (a message that names a value or an object: assert
         * the part a test can state).
         */
        public Check failsWithMessageContaining(String part) {
            fails();
            if (field.messages.stream().noneMatch(m -> m != null && m.contains(part))) {
                throw new AssertionError("The form rejects " + item + " = " + value + " with: " + field.messages
                        + "\nexpected a message containing: " + part);
            }
            return this;
        }

        private void requireValidation() {
            if (field.absent != null) {
                throw new AssertionError("No field to type " + item + " into: " + field.absent);
            }
            if (!field.validated) {
                throw new AssertionError("The form has no validation of " + item
                        + " (no item/validation/server in the object template the page applies)");
            }
        }
    }

    /**
     * How a {@link #submit} ended ({@link Submit#outcome}): saved, or why not. {@code saved()} is false for each but
     * {@link #SAVED}.
     */
    public enum Outcome {
        /** The page saved the object: the changes executed, the page reported their success or left after them. */
        SAVED,
        /**
         * The page did not save the object: a field shows a message, the page an error (the validation on saving,
         * the operation's error, a refusal of the user's authorizations or of a policy rule), or the GUI's error
         * page opened.
         */
        REJECTED,
        /**
         * The save did not complete: its execution in the background still ran when the tester stopped waiting for
         * it (120 seconds), so the page reports no result yet; the execution may still create or change the object.
         */
        INCOMPLETE,
        /**
         * The save completed, but the page neither reported the result of its changes nor left, and shows no error:
         * nothing tells whether it saved (the object read through the model does).
         */
        UNDETERMINED
    }

    /** The form saved: whether the page saved the object, and its messages. */
    public static final class Submit {

        private final GuiForms.Saved saved;

        Submit(GuiForms.Saved saved) {
            this.saved = saved;
        }

        /** Whether the page saved the object (the changes executed): {@link #outcome} is {@link Outcome#SAVED}. */
        public boolean saved() {
            return saved.saved;
        }

        /** How the save ended: saved, rejected, not completed or undetermined. */
        public Outcome outcome() {
            if (saved.saved) {
                return Outcome.SAVED;
            }
            if (!saved.completed) {
                return Outcome.INCOMPLETE;
            }
            return saved.refused ? Outcome.REJECTED : Outcome.UNDETERMINED;
        }

        /** The items whose fields show a message, with the first one. */
        public Map<String, String> rejected() {
            Map<String, String> out = new LinkedHashMap<>();
            saved.fields.forEach((item, messages) -> out.put(item, messages.get(0)));
            return out;
        }

        /**
         * Every message of the page: the fields' and the others (the validation on saving reports on the form, not
         * on a field; the operation's result).
         */
        public List<String> messages() {
            List<String> out = new ArrayList<>();
            saved.fields.values().forEach(out::addAll);
            out.addAll(saved.page);
            return out;
        }

        /** What the item's field shows after saving. */
        public Check item(String item) {
            List<String> messages = saved.fields.getOrDefault(item, List.of());
            return new Check(item, null, new GuiForms.Field(true, messages, null));
        }

        /** The page saves the object. */
        public Submit saves() {
            if (!saved.saved) {
                throw new AssertionError("The form does not save" + why() + ": " + messages());
            }
            return this;
        }

        /** The page does not save the object, and the item's field shows a message. */
        public Submit rejects(String item) {
            notSaved();
            if (!saved.fields.containsKey(item)) {
                throw new AssertionError("The form shows no message on " + item + why() + "; its messages: "
                        + saved.fields + " " + saved.page);
            }
            return this;
        }

        /** The page does not save the object, and shows the message (on a field or on the form). */
        public Submit rejectsWith(String message) {
            notSaved();
            if (!messages().contains(message)) {
                throw new AssertionError("The form does not show \"" + message + "\"" + why() + "; its messages: "
                        + messages());
            }
            return this;
        }

        /**
         * The page does not save the object, and shows a message (on a field or on the form) containing this text:
         * a refusal whose message names objects by OID ({@code User 'u' not authorized for operation with
         * inducement on role:<oid>(team-1) ...}) asserted by the part a test can state.
         */
        public Submit rejectsWithMessageContaining(String part) {
            notSaved();
            if (messages().stream().noneMatch(m -> m != null && m.contains(part))) {
                throw new AssertionError("The form shows no message containing \"" + part + "\"" + why()
                        + "; its messages: " + messages());
            }
            return this;
        }

        private void notSaved() {
            if (saved.saved) {
                throw new AssertionError("The form saves" + (messages().isEmpty() ? "" : ": " + messages()));
            }
            if (outcome() == Outcome.INCOMPLETE) {
                throw new AssertionError("The form rejects nothing" + why() + ": " + messages());
            }
        }

        /** Why a save that is neither saved nor rejected did not save, as a failure names it; else empty. */
        private String why() {
            switch (outcome()) {
                case INCOMPLETE:
                    return " (INCOMPLETE: the save still ran after 120 s, the page reports no result yet)";
                case UNDETERMINED:
                    return " (UNDETERMINED: the page neither reported the result of the changes nor left, and shows"
                            + " no error)";
                default:
                    return "";
            }
        }
    }
}
