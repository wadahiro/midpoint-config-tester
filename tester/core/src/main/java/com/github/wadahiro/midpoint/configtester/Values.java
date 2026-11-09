package com.github.wadahiro.midpoint.configtester;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.xml.datatype.XMLGregorianCalendar;

import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/** Values as the assertions compare and show them: text. */
final class Values {

    private Values() {
    }

    /**
     * A value as text: a polystring's original, an enumeration's XML value ({@code enabled}), a date as an
     * {@link Instant} ({@code 2030-04-01T00:00:00Z}), a reference's OID, an assignment or the activation as
     * {@link Describe} shows them.
     */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof com.evolveum.midpoint.prism.PrismContainerValue) {
            // the output of a mapping to a container (an assignment, the activation)
            Object containerable = ((com.evolveum.midpoint.prism.PrismContainerValue<?>) value).asContainerable();
            return containerable != null ? text(containerable) : value.toString();
        }
        if (value instanceof com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType
                || value instanceof com.evolveum.midpoint.xml.ns._public.common.common_3.ActivationType) {
            return Describe.of(value);
        }
        if (value instanceof PolyString) {
            return ((PolyString) value).getOrig();
        }
        if (value instanceof PolyStringType) {
            return ((PolyStringType) value).getOrig();
        }
        if (value instanceof XMLGregorianCalendar) {
            return ((XMLGregorianCalendar) value).toGregorianCalendar().toInstant().toString();
        }
        if (value instanceof ObjectReferenceType) {
            return ((ObjectReferenceType) value).getOid();
        }
        if (value instanceof Enum) {
            try {
                Method xmlValue = value.getClass().getMethod("value");
                return String.valueOf(xmlValue.invoke(value));
            } catch (ReflectiveOperationException e) {
                return ((Enum<?>) value).name();
            }
        }
        return value.toString();
    }

    static List<String> texts(Collection<?> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (Object value : values) {
                out.add(text(value));
            }
        }
        return out;
    }

    static List<String> texts(Object... values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Collection) {
                out.addAll(texts((Collection<?>) value));
            } else {
                out.add(text(value));
            }
        }
        return out;
    }

    /** The same values, in any order (each value as often). */
    static boolean sameValues(List<String> expected, List<String> actual) {
        List<String> rest = new ArrayList<>(actual);
        for (String e : expected) {
            if (!rest.remove(e)) {
                return false;
            }
        }
        return rest.isEmpty();
    }
}
