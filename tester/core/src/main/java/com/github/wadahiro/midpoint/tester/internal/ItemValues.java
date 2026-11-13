package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismReferenceDefinition;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.prism.xml.XmlTypeConverter;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * The items of a test's writes, the same on every line and for every way of writing (through the model, past it):
 * a path of local names resolved in the definitions ({@link #locate}), and the values as the item's definition takes
 * them ({@link #typed}), converted by the tester rather than by prism, which converts differently on each line (4.0
 * keeps {@code "6"} a string in an int item, 4.4 and 4.8 refuse an {@link Instant} in a dateTime item).
 */
public final class ItemValues {

    private static final Set<String> NUMBERS = Set.of("int", "long", "short", "byte", "integer", "decimal", "double",
            "float", "nonNegativeInteger", "positiveInteger", "unsignedInt", "unsignedLong", "unsignedShort");

    private ItemValues() {
    }

    /** An item's path with the namespaces of its definitions, and its definition. */
    public static final class Located {
        public final ItemPath path;
        public final ItemDefinition<?> definition;
        /** Whether the path goes through a multi-valued container (a field of one value of it). */
        public final boolean throughMultiValuedContainer;

        Located(ItemPath path, ItemDefinition<?> definition, boolean throughMultiValuedContainer) {
            this.path = path;
            this.definition = definition;
            this.throughMultiValuedContainer = throughMultiValuedContainer;
        }
    }

    /**
     * The item at the path of local names ({@code extension/hrId}, {@code activation/validTo}) in the definitions
     * under the root (an object's); fails ({@link IllegalArgumentException}) for a name it does not have.
     */
    public static Located locate(PrismContainerDefinition<?> root, String path, String what) {
        List<Object> segments = new ArrayList<>();
        PrismContainerDefinition<?> container = root;
        ItemDefinition<?> found = null;
        boolean throughMultiValued = false;
        String[] names = path.split("/");
        for (int i = 0; i < names.length; i++) {
            found = null;
            if (container != null) {
                for (ItemDefinition<?> definition : container.getDefinitions()) {
                    if (definition.getItemName().getLocalPart().equals(names[i])) {
                        found = definition;
                        break;
                    }
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("No item " + names[i] + (names.length > 1 ? " of " + path : "")
                        + " in " + what);
            }
            segments.add(found.getItemName());
            boolean last = i + 1 == names.length;
            if (!last && found instanceof PrismContainerDefinition && found.isMultiValue()) {
                throughMultiValued = true;
            }
            container = !last && found instanceof PrismContainerDefinition ? (PrismContainerDefinition<?>) found : null;
        }
        return new Located(ItemPath.create(segments), found, throughMultiValued);
    }

    /**
     * The values for a delta builder: container values (of a structured item, which has no Java class) as prism
     * values, which the builder takes as they are, other values as given (real values).
     */
    public static Object[] forDelta(Object[] values) {
        for (Object value : values) {
            if (!(value instanceof com.evolveum.midpoint.prism.PrismValue)) {
                return values;
            }
        }
        return java.util.Arrays.copyOf(values, values.length, com.evolveum.midpoint.prism.PrismValue[].class);
    }

    public static Object[] typed(PrismContext prismContext, String path, ItemDefinition<?> definition,
            Object... values) {
        Object[] out = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = typed(prismContext, path, definition, values[i]);
        }
        return out;
    }

    /**
     * The value as the item takes it. A string is read as the item's type: a number, a boolean ({@code true},
     * {@code false}, {@code 1}, {@code 0}), a date ({@code 2030-04-01T09:00:00+09:00}, {@code 2030-04-01T00:00:00Z},
     * a local date or date and time in the JVM's time zone), a QName ({@code {ns}local}), an enumeration by its XML
     * value, a polystring by its original. A Java value is taken as its type allows: a number widened or narrowed, a
     * date from an {@link Instant}, {@link ZonedDateTime}, {@link OffsetDateTime}, {@link LocalDate} (the start of the
     * day) or {@link LocalDateTime}. A value the item cannot take fails ({@link IllegalArgumentException}).
     */
    public static Object typed(PrismContext prismContext, String path, ItemDefinition<?> definition, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(path + ": a value cannot be null (no values: empty it)");
        }
        if (definition instanceof PrismReferenceDefinition) {
            if (value instanceof ObjectReferenceType || value instanceof PrismReferenceValue) {
                return value;
            }
            throw notAValue(path, definition, value);
        }
        if (definition instanceof PrismContainerDefinition) {
            if (value instanceof PrismContainerValue || value instanceof Containerable) {
                return value;
            }
            throw notAValue(path, definition, value);
        }
        QName type = definition.getTypeName();
        if (PolyStringType.COMPLEX_TYPE.equals(type)) {
            if (value instanceof String) {
                return PolyString.fromOrig((String) value);
            }
            if (value instanceof PolyString || value instanceof PolyStringType) {
                return value;
            }
            throw notAValue(path, definition, value);
        }
        Class<?> javaType = prismContext.getSchemaRegistry().determineClassForType(type);
        if (javaType != null && javaType.isEnum()) {
            if (javaType.isInstance(value)) {
                return value;
            }
            if (value instanceof String) {
                try {
                    Method fromValue = javaType.getMethod("fromValue", String.class);
                    return fromValue.invoke(null, value);
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    throw notAValue(path, definition, value);
                }
            }
            throw notAValue(path, definition, value);
        }
        if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(type.getNamespaceURI())) {
            String local = type.getLocalPart();
            if (NUMBERS.contains(local)) {
                if (value instanceof Number || value instanceof String) {
                    try {
                        return XmlTypeConverter.toJavaValue(value.toString().trim(), type);
                    } catch (RuntimeException e) {
                        throw notAValue(path, definition, value);
                    }
                }
                throw notAValue(path, definition, value);
            }
            if ("boolean".equals(local)) {
                if (value instanceof Boolean) {
                    return value;
                }
                if (value instanceof String) {
                    switch (((String) value).trim()) {
                        case "true": case "1": return Boolean.TRUE;
                        case "false": case "0": return Boolean.FALSE;
                        default: throw notAValue(path, definition, value);
                    }
                }
                throw notAValue(path, definition, value);
            }
            if ("dateTime".equals(local)) {
                return dateTime(path, definition, value);
            }
            if ("QName".equals(local)) {
                if (value instanceof QName) {
                    return value;
                }
                if (value instanceof String && ((String) value).startsWith("{")) {
                    return QName.valueOf((String) value);
                }
                throw notAValue(path, definition, value);
            }
            if ("string".equals(local) || "anyURI".equals(local) || "token".equals(local)) {
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    return String.valueOf(value);
                }
                throw notAValue(path, definition, value);
            }
            return value;
        }
        if (javaType != null) {
            if (javaType.isInstance(value)) {
                return value;
            }
            throw notAValue(path, definition, value);
        }
        // a simple type of the extension schema (no Java class): its values are strings, from its enumeration if any
        if (!(value instanceof String)) {
            throw notAValue(path, definition, value);
        }
        if (definition instanceof PrismPropertyDefinition) {
            Collection<?> allowed = ((PrismPropertyDefinition<?>) definition).getAllowedValues();
            if (allowed != null && !allowed.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (Object displayable : allowed) {
                    values.add(String.valueOf(((com.evolveum.midpoint.util.DisplayableValue<?>) displayable)
                            .getValue()));
                }
                if (!values.contains(value)) {
                    throw new IllegalArgumentException(path + " (" + typeName(definition) + "): not a value: " + value
                            + "; values: " + values);
                }
            }
        }
        return value;
    }

    private static XMLGregorianCalendar dateTime(String path, ItemDefinition<?> definition, Object value) {
        if (value instanceof XMLGregorianCalendar) {
            return (XMLGregorianCalendar) value;
        }
        ZonedDateTime time;
        if (value instanceof Instant) {
            time = ((Instant) value).atZone(java.time.ZoneOffset.UTC);
        } else if (value instanceof ZonedDateTime) {
            time = (ZonedDateTime) value;
        } else if (value instanceof OffsetDateTime) {
            time = ((OffsetDateTime) value).toZonedDateTime();
        } else if (value instanceof LocalDateTime) {
            time = ((LocalDateTime) value).atZone(ZoneId.systemDefault());
        } else if (value instanceof LocalDate) {
            time = ((LocalDate) value).atStartOfDay(ZoneId.systemDefault());
        } else if (value instanceof java.util.Date) {
            time = ((java.util.Date) value).toInstant().atZone(java.time.ZoneOffset.UTC);
        } else if (value instanceof String) {
            time = parse(path, definition, ((String) value).trim());
        } else {
            throw notAValue(path, definition, value);
        }
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar.from(time));
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No XML date factory", e);
        }
    }

    private static ZonedDateTime parse(String path, ItemDefinition<?> definition, String text) {
        try {
            return OffsetDateTime.parse(text).toZonedDateTime();
        } catch (DateTimeParseException e) {
            // not with an offset
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            // not a local date and time
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            throw notAValue(path, definition, text);
        }
    }

    private static IllegalArgumentException notAValue(String path, ItemDefinition<?> definition, Object value) {
        return new IllegalArgumentException(path + " (" + typeName(definition) + "): not a value: " + value
                + (value != null && !(value instanceof String) ? " (" + value.getClass().getSimpleName() + ")" : ""));
    }

    private static String typeName(ItemDefinition<?> definition) {
        QName type = definition.getTypeName();
        String prefix = XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(type.getNamespaceURI()) ? "xsd:" : "";
        return prefix + type.getLocalPart();
    }
}
