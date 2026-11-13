package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.github.wadahiro.midpoint.tester.internal.ItemValues;

/**
 * A value of a structured item of the extension schema (a {@code complexType} annotated {@code a:container}), to
 * write or to claim: its fields by local name, each with its values, a field that is structured itself a
 * {@code Struct} again. The values are read as the fields' types, as an item's are ({@link NewFocus#set}):
 *
 * <pre>{@code
 * Struct.of("street", "Main", "city", "Prague", "phone", Struct.of("kind", "mobile", "number", "123"))
 * Struct.xml("<address><street>Main</street><city>Prague</city></address>")   // parsed by the item's definition
 * t.user("jdoe").hasItem("extension/address", Struct.of("street", "Main", "city", "Prague"))  // the whole value
 * }</pre>
 */
public final class Struct {

    private final List<Object[]> fields = new ArrayList<>();
    private final String xml;

    private Struct(String xml) {
        this.xml = xml;
    }

    /** The fields and their values, in pairs: {@code "street", "Main", "city", "Prague"}. */
    public static Struct of(Object... fieldsAndValues) {
        if (fieldsAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Struct.of takes fields and values in pairs: "
                    + Arrays.toString(fieldsAndValues));
        }
        Struct struct = new Struct(null);
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            Object value = fieldsAndValues[i + 1];
            struct.set(String.valueOf(fieldsAndValues[i]),
                    value instanceof Collection ? ((Collection<?>) value).toArray() : new Object[] {value});
        }
        return struct;
    }

    /** The value as XML, its root element the item's ({@code <address>...</address>}). */
    public static Struct xml(String fragment) {
        return new Struct(fragment);
    }

    /** The field's values (several for a multi-valued field); a field given again adds its values. */
    public Struct set(String field, Object... values) {
        if (xml != null) {
            throw new IllegalStateException("A Struct of XML has its fields in the XML");
        }
        fields.add(new Object[] {field, values});
        return this;
    }

    @Override
    public String toString() {
        if (xml != null) {
            return xml;
        }
        List<String> parts = new ArrayList<>();
        for (Object[] field : fields) {
            for (Object value : (Object[]) field[1]) {
                parts.add(field[0] + " " + value);
            }
        }
        return "(" + String.join(", ", parts) + ")";
    }

    // ---- for the writes and the claims --------------------------------------------------------------------

    /**
     * The values as the item takes them: a {@code Struct} of a structured item as its container value, any other
     * value typed by {@link ItemValues#typed}.
     */
    static Object[] values(PrismContext prismContext, String path, ItemDefinition<?> definition, Object... values) {
        Object[] out = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Struct) {
                if (!(definition instanceof PrismContainerDefinition)) {
                    throw new IllegalArgumentException(path + " is not structured: its values are no Struct");
                }
                out[i] = ((Struct) values[i]).value(prismContext, path, (PrismContainerDefinition<?>) definition);
            } else {
                if (definition instanceof PrismContainerDefinition
                        && ((PrismContainerDefinition<?>) definition).getCompileTimeClass() == null
                        && !(values[i] instanceof PrismContainerValue)) {
                    throw new IllegalArgumentException(path + " is structured: give its values as Struct.of(...)");
                }
                out[i] = ItemValues.typed(prismContext, path, definition, values[i]);
            }
        }
        return out;
    }

    /**
     * For a delete of a structured item's values: the values the item has (stored) that equal these as text, by their
     * IDs (prism deletes a container value only on an exact match); another item's values as they are.
     */
    static Object[] byIds(String path, ItemDefinition<?> definition, Item<?, ?> stored, Object[] values) {
        if (!(definition instanceof PrismContainerDefinition)
                || ((PrismContainerDefinition<?>) definition).getCompileTimeClass() != null) {
            return values;
        }
        List<PrismContainerValue<?>> has = new ArrayList<>();
        if (stored instanceof PrismContainer) {
            has.addAll(((PrismContainer<?>) stored).getValues());
        }
        Object[] out = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            String wanted = Values.text(values[i]);
            PrismContainerValue<?> found = null;
            for (PrismContainerValue<?> candidate : has) {
                if (Values.text(candidate).equals(wanted)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("No value of " + path + " equal to " + wanted + "; it has: "
                        + Values.texts(has));
            }
            PrismContainerValue<?> byId = found.clone();
            byId.clear();
            byId.setId(found.getId());
            out[i] = byId;
        }
        return out;
    }

    /** The container value of the item's definition, with no parent (to add to an item or a delta). */
    PrismContainerValue<?> value(PrismContext prismContext, String path, PrismContainerDefinition<?> definition) {
        try {
            if (xml != null) {
                Item<?, ?> parsed = prismContext.parserFor(xml).language(PrismContext.LANG_XML).definition(definition)
                        .parseItem();
                if (!(parsed instanceof PrismContainer) || ((PrismContainer<?>) parsed).getValues().size() != 1) {
                    throw new IllegalArgumentException(path + ": the XML is not one value of it: " + xml);
                }
                return ((PrismContainer<?>) parsed).getValues().get(0).clone();
            }
            PrismContainer<?> container = definition.instantiate();
            PrismContainerValue<?> value = container.createNewValue();
            for (Object[] field : fields) {
                String name = (String) field[0];
                Object[] values = (Object[]) field[1];
                ItemDefinition<?> fieldDefinition = null;
                for (ItemDefinition<?> candidate : definition.getDefinitions()) {
                    if (candidate.getItemName().getLocalPart().equals(name)) {
                        fieldDefinition = candidate;
                        break;
                    }
                }
                if (fieldDefinition == null) {
                    throw new IllegalArgumentException("No field " + name + " in " + path + " ("
                            + definition.getTypeName().getLocalPart() + ")");
                }
                String fieldPath = path + "/" + name;
                Object[] typed = values(prismContext, fieldPath, fieldDefinition, values);
                if (fieldDefinition instanceof PrismContainerDefinition) {
                    PrismContainer<?> nested = value.findOrCreateContainer(fieldDefinition.getItemName());
                    for (Object one : typed) {
                        add(nested, (PrismContainerValue<?>) one);
                    }
                } else {
                    @SuppressWarnings("unchecked")
                    PrismProperty<Object> property = (PrismProperty<Object>) value.findOrCreateProperty(
                            fieldDefinition.getItemName());
                    for (Object one : typed) {
                        property.addRealValue(one);
                    }
                }
            }
            return value.clone();
        } catch (SchemaException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void add(PrismContainer<?> container, PrismContainerValue<?> value) throws SchemaException {
        ((PrismContainer) container).add(value);
    }
}
