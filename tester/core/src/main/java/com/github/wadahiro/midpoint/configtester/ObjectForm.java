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
 *   <li>{@link #validate} types a value into a field and leaves it: what the field's validator says (its
 *       {@code object} is the object before the value: a new object's other items typed in, an existing object's
 *       stored values; 4.0 validates a text field twice, the second time with the value in {@code object});</li>
 *   <li>{@link #submit} presses Save: every field's validator again (those not typed into with their value, an
 *       empty one with {@code input} null), then on 4.4 and later the validation on saving
 *       (FormWrapperValidator: the items whose field said nothing, with the values in {@code object}), then the
 *       changes executed through the model as the user (the object stored, or the operation's error).</li>
 * </ul>
 * The user ({@link Actor#newObjectForm}, {@link Actor#editForm}; without one, the administrator) is logged in as the
 * tester logs a user in; the page's URL is decided first as midPoint's security filter decides it (a user who may not
 * open the page fails the form); the page shows the items as the GUI shows them to the user (the items they may not
 * see or change have no field). Messages are in the JVM's locale. midPoint's GUI comes with the tester
 * (docs/guide/gui-and-authorizations.md). Immutable: {@code set} returns a new form, each {@code validate} and
 * {@code submit} opens the page anew.
 */
public abstract class ObjectForm {

    final MidPointConfigTester tester;
    final Class<? extends ObjectType> type;
    final String user;
    final Map<String, Object> items;

    ObjectForm(MidPointConfigTester tester, Class<? extends ObjectType> type, String user, Map<String, Object> items) {
        this.tester = tester;
        this.type = type;
        this.user = user;
        this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    abstract GuiForms.Spec spec();

    static Map<String, Object> with(Map<String, Object> items, String item, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(items);
        copy.remove(item);
        copy.put(item, value);
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
        return new Check(item, value, gui().type(spec(), item, value));
    }

    /** Save pressed, the items typed in: {@code submit().saves()}, {@code submit().rejects("identifier")}. */
    public Submit submit() {
        return new Submit(gui().save(spec()));
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

    /** The form saved: whether the page saved the object, and its messages. */
    public static final class Submit {

        private final GuiForms.Saved saved;

        Submit(GuiForms.Saved saved) {
            this.saved = saved;
        }

        /** Whether the page saved the object (the changes executed). */
        public boolean saved() {
            return saved.saved;
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
                throw new AssertionError("The form does not save: " + messages());
            }
            return this;
        }

        /** The page does not save the object, and the item's field shows a message. */
        public Submit rejects(String item) {
            notSaved();
            if (!saved.fields.containsKey(item)) {
                throw new AssertionError("The form shows no message on " + item + "; its messages: " + saved.fields
                        + " " + saved.page);
            }
            return this;
        }

        /** The page does not save the object, and shows the message (on a field or on the form). */
        public Submit rejectsWith(String message) {
            notSaved();
            if (!messages().contains(message)) {
                throw new AssertionError("The form does not show \"" + message + "\"; its messages: " + messages());
            }
            return this;
        }

        private void notSaved() {
            if (saved.saved) {
                throw new AssertionError("The form saves" + (messages().isEmpty() ? "" : ": " + messages()));
            }
        }
    }
}
