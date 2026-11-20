package com.github.wadahiro.midpoint.tester.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.Duration;
import javax.xml.datatype.XMLGregorianCalendar;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.markup.html.form.AbstractSingleSelectChoice;
import org.apache.wicket.markup.html.form.FormComponent;
import org.apache.wicket.util.convert.IConverter;

import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * The values {@link GuiFormsImpl} types into the GUI's fields: a value given as Java value or text, as the type of
 * the field's model ({@link FormComponent#getType()}) has it, shown as the field's converter shows it, and compared
 * with what the field's model holds after typing.
 */
final class FieldValues {

    /** wicket-datetime's DateTimeField, which 4.0's and 4.4's DateInput (DatePanel) extends. */
    private static final String DATE_TIME_FIELD = "org.apache.wicket.extensions.yui.calendar.DateTimeField";

    private FieldValues() {
    }

    /**
     * The value as the field's type: a number from a number or its text, a date ({@link #date}), a duration from a
     * {@code javax.xml.datatype.Duration}, a {@code java.time.Duration} or its text, a text from a PolyString (its
     * orig) or any value; null when the value is a text the type does not read (typed as it is: the field's converter
     * decides). A value of another kind fails, naming what the field takes.
     */
    static Object coerce(String item, Class<?> type, Object value) {
        if (value instanceof PolyString) {
            value = ((PolyString) value).getOrig();
        } else if (value instanceof PolyStringType) {
            value = ((PolyStringType) value).getOrig();
        }
        if (type == null || type == String.class || type == Object.class) {
            return String.valueOf(value);
        }
        if (type.isInstance(value)) {
            return value;
        }
        String text = value instanceof String ? ((String) value).trim() : null;
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            BigDecimal number;
            if (value instanceof Number) {
                number = new BigDecimal(value.toString());
            } else if (text != null) {
                try {
                    number = new BigDecimal(text);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                throw takes(item, value, "a number (" + type.getSimpleName() + ")");
            }
            return number(item, value, type, number);
        }
        if (Date.class.isAssignableFrom(type)) {
            return date(item, value, false);
        }
        if (Duration.class.isAssignableFrom(type)) {
            if (value instanceof java.time.Duration) {
                text = value.toString();
            }
            if (text == null) {
                throw takes(item, value, "a duration (javax.xml.datatype.Duration, java.time.Duration or its ISO"
                        + " text such as P1D)");
            }
            try {
                return DatatypeFactory.newInstance().newDuration(text);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                return null;
            } catch (DatatypeConfigurationException e) {
                throw new IllegalStateException(e);
            }
        }
        if (text != null) {
            return null;
        }
        throw takes(item, value, type.getSimpleName());
    }

    private static Object number(String item, Object value, Class<?> type, BigDecimal number) {
        try {
            if (type == Integer.class || type == int.class) {
                return number.intValueExact();
            }
            if (type == Long.class || type == long.class) {
                return number.longValueExact();
            }
            if (type == Short.class || type == short.class) {
                return number.shortValueExact();
            }
            if (type == BigInteger.class) {
                return number.toBigIntegerExact();
            }
        } catch (ArithmeticException e) {
            throw new AssertionError("Cannot fill in " + item + ": its field takes a whole number ("
                    + type.getSimpleName() + "), not " + value);
        }
        if (type == Double.class || type == double.class) {
            return number.doubleValue();
        }
        if (type == Float.class || type == float.class) {
            return number.floatValue();
        }
        if (type == BigDecimal.class) {
            return number;
        }
        throw takes(item, value, type.getSimpleName());
    }

    /**
     * A date from a {@code java.util.Date}, {@code Instant}, {@code ZonedDateTime}, {@code OffsetDateTime},
     * {@code XMLGregorianCalendar}, {@code Calendar}, a {@code LocalDateTime} or {@code LocalDate} (in the JVM's time
     * zone, as the GUI's field reads its text) or the ISO text of one of these; another text: null, or (strict, a field
     * of several inputs) a failure.
     */
    static Date date(String item, Object value, boolean strict) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Instant) {
            return Date.from((Instant) value);
        }
        if (value instanceof ZonedDateTime) {
            return Date.from(((ZonedDateTime) value).toInstant());
        }
        if (value instanceof OffsetDateTime) {
            return Date.from(((OffsetDateTime) value).toInstant());
        }
        if (value instanceof XMLGregorianCalendar) {
            return ((XMLGregorianCalendar) value).toGregorianCalendar().getTime();
        }
        if (value instanceof Calendar) {
            return ((Calendar) value).getTime();
        }
        if (value instanceof LocalDateTime) {
            return Date.from(((LocalDateTime) value).atZone(ZoneId.systemDefault()).toInstant());
        }
        if (value instanceof LocalDate) {
            return Date.from(((LocalDate) value).atStartOfDay(ZoneId.systemDefault()).toInstant());
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            for (java.util.function.Function<String, Instant> parse : java.util.List.<java.util.function.Function<String,
                    Instant>>of(Instant::parse, t -> OffsetDateTime.parse(t).toInstant(),
                    t -> ZonedDateTime.parse(t).toInstant(),
                    t -> LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant(),
                    t -> LocalDate.parse(t).atStartOfDay(ZoneId.systemDefault()).toInstant())) {
                try {
                    return Date.from(parse.apply(text));
                } catch (DateTimeParseException e) {
                    // the next form
                }
            }
            if (!strict) {
                return null;
            }
        }
        throw takes(item, value, "a date and time (java.util.Date, Instant, ZonedDateTime, OffsetDateTime,"
                + " XMLGregorianCalendar, LocalDateTime in the JVM's time zone, or the ISO text of one)");
    }

    private static AssertionError takes(String item, Object value, String what) {
        return new AssertionError("Cannot fill in " + item + ": its field takes " + what + ", not " + value + " ("
                + value.getClass().getSimpleName() + ")");
    }

    /** The value as the field's converter shows it (the text a user types), or its text. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static String render(FormComponent<?> input, Object value) {
        if (value == null) {
            return "";
        }
        Class<?> type = input.getType() != null ? input.getType() : value.getClass();
        IConverter converter = input.getConverter(type);
        if (converter != null && type.isInstance(value)) {
            try {
                String text = converter.convertToString(value, input.getLocale());
                if (text != null) {
                    return text;
                }
            } catch (RuntimeException e) {
                // a converter that does not show this value: its text
            }
        }
        return String.valueOf(value);
    }

    static boolean isEmpty(Object held) {
        if (held == null) {
            return true;
        }
        if (held instanceof PolyString) {
            held = ((PolyString) held).getOrig();
        } else if (held instanceof PolyStringType) {
            held = ((PolyStringType) held).getOrig();
        }
        return held == null || String.valueOf(held).isEmpty();
    }

    /**
     * Whether the model holds the value typed: a date to the millisecond (a {@code Date} or an
     * {@code XMLGregorianCalendar}), a number by its value, a PolyString by its orig, a text also without the spaces
     * around it (a text field trims its input).
     */
    static boolean same(Object expected, Object held) {
        if (expected instanceof Date) {
            Long millis = millis(held);
            return millis != null && millis == ((Date) expected).getTime();
        }
        if (expected instanceof Number && held instanceof Number) {
            return new BigDecimal(expected.toString()).compareTo(new BigDecimal(held.toString())) == 0;
        }
        if (held instanceof PolyString) {
            held = ((PolyString) held).getOrig();
        } else if (held instanceof PolyStringType) {
            held = ((PolyStringType) held).getOrig();
        }
        if (expected instanceof String && held instanceof String) {
            return expected.equals(held) || ((String) expected).trim().equals(((String) held).trim());
        }
        return expected.equals(held);
    }

    private static Long millis(Object held) {
        if (held instanceof Date) {
            return ((Date) held).getTime();
        }
        if (held instanceof XMLGregorianCalendar) {
            return ((XMLGregorianCalendar) held).toGregorianCalendar().getTimeInMillis();
        }
        return null;
    }

    /** A held value in a failure: a date as its instant. */
    static String describe(Object held) {
        Long millis = millis(held);
        return millis != null ? Instant.ofEpochMilli(millis).toString() : String.valueOf(held);
    }

    /** Why the field holds another value than typed, when it is known: a date field takes no seconds. */
    static String why(Object expected, Object held) {
        Long millis = millis(held);
        if (expected instanceof Date && millis != null) {
            long typed = ((Date) expected).getTime();
            if (typed / 60_000 == millis / 60_000 || typed % 60_000 != 0) {
                return ": the GUI's date field takes a time to the minute, without seconds; pass "
                        + Instant.ofEpochMilli(typed - Math.floorMod(typed, 60_000L)) + " (typed: "
                        + Instant.ofEpochMilli(typed) + ")";
            }
        }
        return "";
    }

    /** Whether the input is a DateTimeField: a date, hours, minutes and (12-hour locales) AM / PM. */
    static boolean isDateTimeField(Object input) {
        for (Class<?> c = input.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(DATE_TIME_FIELD)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The inputs of a DateTimeField (by page-relative path) and their texts of the date, in the JVM's time zone (as
     * the field computes the date: DateInput.computeDateTime): the date first (its change sets the field's value, and
     * the re-rendered field shows its hours and minutes), then hours, minutes and AM / PM, each change computing the
     * value from the date and the others; none: all empty.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static Map<String, String> dateTimeParts(FormComponent<?> field, Date date) {
        MarkupContainer container = (MarkupContainer) field;
        FormComponent<?> day = (FormComponent<?>) container.get("date");
        FormComponent<?> hours = (FormComponent<?>) container.get("hours");
        FormComponent<?> minutes = (FormComponent<?>) container.get("minutes");
        Component amOrPm = container.get("amOrPmChoice");
        boolean twelve = amOrPm != null && amOrPm.isVisible();
        Map<String, String> parts = new LinkedHashMap<>();
        IConverter converter = day.getConverter(Date.class);
        parts.put(day.getPageRelativePath(), date == null ? "" : converter.convertToString(date, day.getLocale()));
        if (date == null) {
            return parts;
        }
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.setTime(date);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        parts.put(hours.getPageRelativePath(), String.valueOf(twelve ? (hour % 12 == 0 ? 12 : hour % 12) : hour));
        parts.put(minutes.getPageRelativePath(), String.valueOf(calendar.get(Calendar.MINUTE)));
        if (twelve) {
            AbstractSingleSelectChoice<Object> choice = (AbstractSingleSelectChoice<Object>) amOrPm;
            Object wanted = null;
            for (Object option : choice.getChoices()) {
                if (option instanceof Enum && ((Enum<?>) option).name().equals(hour < 12 ? "AM" : "PM")) {
                    wanted = option;
                }
            }
            int index = choice.getChoices().indexOf(wanted);
            parts.put(amOrPm.getPageRelativePath(), choice.getChoiceRenderer().getIdValue(wanted, index));
        }
        return parts;
    }
}
