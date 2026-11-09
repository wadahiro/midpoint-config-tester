package com.github.wadahiro.midpoint.tester;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.audit.api.AuditEventRecord;
import com.evolveum.midpoint.model.api.context.AssignmentPath;
import com.evolveum.midpoint.model.api.context.AssignmentPathSegment;
import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismPropertyValue;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.Referencable;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.prism.util.ItemDeltaItem;
import com.evolveum.midpoint.prism.util.ObjectDeltaObject;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;
import com.evolveum.prism.xml.ns._public.types_3.ProtectedStringType;
import com.github.wadahiro.midpoint.tester.internal.ConfigIndex;
import com.github.wadahiro.midpoint.tester.internal.LineSupport;

/**
 * Turns evaluations into text that is stable from run to run (and, as far as possible, from line to line): what
 * {@link Evaluation#toString()} shows.
 * <ul>
 *   <li>Values as short text: objects as {@code User:jdoe(oid)}, references as {@code ref Role:Employee(oid)},
 *       strings quoted; a variable present with a null value as {@code null}.</li>
 *   <li>OIDs: those of the configuration files given to the runtime and midPoint's own ({@code 00000000-...}) are
 *       kept; generated ones are replaced by {@code gen:<name>} when the evaluations show the object's name, else by
 *       {@code <gen>}.</li>
 *   <li>The output triple as two sets: old = zero + minus, new = zero + plus (lines and code paths split a value
 *       between zero and plus differently).</li>
 *   <li>Random values masked: the output of {@code generate} expressions, protected strings.</li>
 *   <li>Line-specific class names folded: packages and an {@code Impl} suffix dropped ({@link #className}); errors as
 *       their root cause's simple class name and first message line ({@link #error}).</li>
 * </ul>
 */
public final class Normalizer {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_VALUES = 10;
    private static final Normalizer PLAIN = new Normalizer(Map.of(), false);

    /** OID to name of the objects seen in the evaluations. */
    private final Map<String, String> names;
    private final boolean replaceOids;

    private Normalizer(Map<String, String> names, boolean replaceOids) {
        this.names = names;
        this.replaceOids = replaceOids;
    }

    /** Values as text, OIDs as they are. */
    public static Normalizer plain() {
        return PLAIN;
    }

    /** For these evaluations: generated OIDs are named after the objects the evaluations show. */
    public static Normalizer of(Collection<Evaluation> evaluations) {
        Map<String, String> names = new HashMap<>();
        for (Evaluation evaluation : evaluations) {
            evaluation.variables().values().forEach(v -> collectNames(v, names));
            evaluation.zeroSet().forEach(v -> collectNames(v, names));
            evaluation.plusSet().forEach(v -> collectNames(v, names));
        }
        return new Normalizer(names, true);
    }

    // ---- evaluations ----------------------------------------------------------------------------------------

    /** The new output values (zero + plus) as normalized text, sorted. */
    public SortedSet<String> newValues(Evaluation evaluation) {
        List<Object> values = new ArrayList<>(evaluation.zeroSet());
        values.addAll(evaluation.plusSet());
        return outputSet(evaluation, values);
    }

    /** The old output values (zero + minus) as normalized text, sorted. */
    public SortedSet<String> oldValues(Evaluation evaluation) {
        List<Object> values = new ArrayList<>(evaluation.zeroSet());
        values.addAll(evaluation.minusSet());
        return outputSet(evaluation, values);
    }

    private SortedSet<String> outputSet(Evaluation evaluation, List<Object> values) {
        SortedSet<String> out = new TreeSet<>();
        boolean random = isRandom(evaluation);
        for (Object value : values) {
            out.add(random ? "<random>" : outputValue(value));
        }
        return out;
    }

    /**
     * An output value: as {@link #value}, except containers (other than assignments) and other beans of midPoint's
     * schema, whose content is given as JSON (as variables, only their type is shown).
     */
    public String outputValue(Object v) {
        Object real = v instanceof PrismPropertyValue ? ((PrismPropertyValue<?>) v).getRealValue()
                : v instanceof PrismContainerValue ? ((PrismContainerValue<?>) v).asContainerable() : v;
        if (real != null && !(real instanceof AssignmentType) && !(real instanceof Objectable)
                && (real instanceof Containerable || isSchemaBean(real))) {
            String json = LineSupport.serializeRealValue(real);
            if (json != null) {
                return text(className(real.getClass()).replaceAll("Type$", "") + json);
            }
            if (!(real instanceof Containerable)) {
                return text(beanProperties(real));
            }
        }
        return value(v);
    }

    /**
     * A bean of midPoint's schema the JSON serializer does not know (4.8+'s {@code RecipientExpressionResultType}, the
     * result of a notifier's recipient expression): its non-empty properties by name, in parentheses (not braces: its
     * content is what is compared), e.g. {@code RecipientExpressionResult(address="jdoe@example.com")}.
     */
    private String beanProperties(Object bean) {
        SortedMap<String, String> properties = new TreeMap<>();
        for (Method m : bean.getClass().getMethods()) {
            String name = m.getName();
            if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class
                    || !(name.startsWith("get") && name.length() > 3 || name.startsWith("is") && name.length() > 2)) {
                continue;
            }
            Object value;
            try {
                value = m.invoke(bean);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (value == null || value instanceof Collection && ((Collection<?>) value).isEmpty()) {
                continue;
            }
            String property = name.substring(name.startsWith("get") ? 3 : 2);
            properties.put(Character.toLowerCase(property.charAt(0)) + property.substring(1), value(value));
        }
        StringBuilder sb = new StringBuilder(className(bean.getClass()).replaceAll("Type$", "")).append('(');
        properties.forEach((name, value) -> sb.append(sb.charAt(sb.length() - 1) == '(' ? "" : ", ")
                .append(name).append('=').append(value));
        return sb.append(')').toString();
    }

    /** A JAXB bean of midPoint's schema without a short form of its own (e.g. {@code InformationType}). */
    private boolean isSchemaBean(Object v) {
        return v.getClass().getName().startsWith("com.evolveum.") && raw(v).equals(className(v.getClass()));
    }

    /** Whether the output is random by design: a {@code generate} expression (or a mapping whose expression is one). */
    static boolean isRandom(Evaluation evaluation) {
        if ("generate".equals(evaluation.evaluator())) {
            return true;
        }
        Evaluation expression = evaluation.isMapping() ? evaluation.expression() : null;
        return expression != null && "generate".equals(expression.evaluator());
    }

    /** A header line ({@link #header}) and the inputs and outputs, one per line. */
    public String describe(Evaluation e) {
        StringBuilder sb = new StringBuilder(header(e)).append('\n');
        e.variables().forEach((name, value) -> sb.append("  var ").append(name).append(" = ").append(value(value))
                .append('\n'));
        e.sources().forEach((name, source) -> sb.append("  src ").append(name).append(" = ")
                .append(values(source.oldValues())).append(" -> ").append(values(source.newValues())).append('\n'));
        if (e.conditionOld() != null || e.conditionNew() != null) {
            sb.append("  condition old=").append(e.conditionOld()).append(" new=").append(e.conditionNew()).append('\n');
        }
        if (e.error() != null) {
            sb.append("  error ").append(text(e.error().getClass().getSimpleName() + ": " + e.error().getMessage()))
                    .append('\n');
        } else if (e.hasOutput()) {
            sb.append("  old=").append(oldValues(e)).append(" new=").append(newValues(e)).append('\n');
        } else {
            sb.append("  no output\n");
        }
        return sb.toString();
    }

    public String describe(List<Evaluation> evaluations) {
        StringBuilder sb = new StringBuilder();
        for (Evaluation e : evaluations) {
            sb.append(describe(e)).append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code #seq mapping 'name' [-> target] [in #parent] = identity @ file:line path}, or {@code #seq <evaluator>
     * (<part>) ...} for an expression, indented by its depth and saying which evaluation it is nested in.
     */
    public String header(Evaluation e) {
        StringBuilder sb = new StringBuilder("#").append(e.sequence()).append(' ');
        if (e.isMapping()) {
            sb.append("mapping ").append(e.mappingName() != null ? "'" + e.mappingName() + "'" : "(unnamed)");
            if (e.target() != null) {
                sb.append(" -> ").append(e.target());
            }
        } else {
            sb.append(e.evaluator());
            if (e.part() != null) {
                sb.append(" (").append(e.part()).append(')');
            }
        }
        if (e.parent() != null) {
            sb.append(" in #").append(e.parent().sequence());
        }
        sb.append(" = ").append(e.identity());
        sb.append(" @ ").append(e.location());
        return text(sb.toString());
    }

    // ---- values ---------------------------------------------------------------------------------------------

    public String values(Collection<?> values) {
        List<String> list = new ArrayList<>();
        if (values != null) {
            int i = 0;
            for (Object v : values) {
                if (i++ == MAX_VALUES) {
                    list.add("... (" + values.size() + ")");
                    break;
                }
                list.add(value(v));
            }
        }
        return list.toString();
    }

    /** One value as short text, with OIDs normalized. */
    public String value(Object v) {
        return text(raw(v));
    }

    /** Replaces generated OIDs in the text (see the class description). */
    public String text(String text) {
        if (!replaceOids || text == null) {
            return text;
        }
        Matcher m = UUID.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String oid = m.group();
            String replacement;
            if (oid.startsWith("00000000-0000-0000-0000-") || ConfigIndex.get().isKnownOid(oid)) {
                replacement = oid;
            } else if (names.containsKey(oid)) {
                replacement = "gen:" + names.get(oid);
            } else {
                replacement = "<gen>";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String raw(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return "\"" + v + "\"";
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof Enum) {
            return v.toString();
        }
        if (v instanceof PolyString) {
            return "\"" + ((PolyString) v).getOrig() + "\"";
        }
        if (v instanceof PolyStringType) {
            return "\"" + ((PolyStringType) v).getOrig() + "\"";
        }
        if (v instanceof ProtectedStringType) {
            return "<protected>";
        }
        if (v instanceof QName) {
            return ((QName) v).getLocalPart();
        }
        if (v instanceof XMLGregorianCalendar) {
            // some lines keep a zero fraction (".000"), others drop it
            XMLGregorianCalendar calendar = (XMLGregorianCalendar) ((XMLGregorianCalendar) v).clone();
            if (calendar.getFractionalSecond() != null && calendar.getFractionalSecond().signum() == 0) {
                calendar.setFractionalSecond(null);
            }
            return calendar.toXMLFormat();
        }
        if (v instanceof ObjectDeltaObject) {
            ObjectDeltaObject<?> odo = (ObjectDeltaObject<?>) v;
            return "ODO(" + object(odo.getOldObject()) + " -> " + object(odo.getNewObject()) + ")";
        }
        if (v instanceof ItemDeltaItem) {
            ItemDeltaItem<?, ?> idi = (ItemDeltaItem<?, ?>) v;
            return "IDI(" + item(idi.getItemOld()) + " -> " + item(idi.getItemNew()) + ")";
        }
        if (v instanceof PrismObject) {
            return object((PrismObject<?>) v);
        }
        if (v instanceof Objectable) {
            return object(((Objectable) v).asPrismObject());
        }
        if (v instanceof PrismPropertyValue) {
            return raw(((PrismPropertyValue<?>) v).getRealValue());
        }
        if (v instanceof PrismReferenceValue) {
            PrismReferenceValue r = (PrismReferenceValue) v;
            return ref(r.getTargetType(), r.getOid(), r.getTargetName());
        }
        if (v instanceof Referencable) {
            Referencable r = (Referencable) v;
            return ref(r.getType(), r.getOid(), r.getTargetName() != null ? r.getTargetName().toPolyString() : null);
        }
        if (v instanceof AssignmentType) {
            AssignmentType a = (AssignmentType) v;
            return "Assignment(" + (a.getTargetRef() != null ? raw(a.getTargetRef())
                    : a.getConstruction() != null ? "construction" : "?") + ")";
        }
        if (v instanceof PrismContainerValue) {
            Containerable c = ((PrismContainerValue<?>) v).asContainerable();
            return c != null ? raw(c) : "PCV";
        }
        if (v instanceof Containerable) {
            return className(v.getClass()).replaceAll("Type$", "");
        }
        if (v instanceof Item) {
            Item<?, ?> item = (Item<?, ?>) v;
            return item.getElementName().getLocalPart() + "=" + values(item.getValues());
        }
        if (v instanceof AssignmentPath) {
            List<String> segments = new ArrayList<>();
            for (AssignmentPathSegment s : ((AssignmentPath) v).getSegments()) {
                segments.add(name(s.getSource()) + "->" + name(s.getTarget()));
            }
            return "AssignmentPath" + segments;
        }
        if (v instanceof ModelContext) {
            return "ModelContext(" + ((ModelContext<?>) v).getState() + ")";
        }
        if (v instanceof Task) {
            return "Task";
        }
        if (v instanceof AuditEventRecord) {
            // the result of an audit recording expression: what it decides, not the record's timestamps and
            // identifiers (they differ from run to run)
            AuditEventRecord r = (AuditEventRecord) v;
            return "AuditEventRecord(" + r.getEventType() + "/" + r.getEventStage()
                    + (r.getParameter() != null ? ", parameter=" + raw(r.getParameter()) : "") + ")";
        }
        if (v instanceof Collection) {
            return values((Collection<?>) v);
        }
        return className(v.getClass());
    }

    /**
     * A class name as the normalized text shows it: the simple name without an {@code Impl} suffix, as implementations move
     * between packages and are renamed from line to line ({@code PrismObjectImpl} and {@code PrismObject} are one).
     */
    public static String className(Class<?> type) {
        String name = type.getSimpleName();
        if (name.isEmpty()) {
            // anonymous and lambda classes: their numbering changes with every build
            name = type.getSuperclass() != null && type.getSuperclass() != Object.class
                    ? type.getSuperclass().getSimpleName() : "Object";
        }
        return name.endsWith("Impl") && name.length() > 4 ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * An error as the normalized text shows it: the root cause's class name ({@link #className}) and the first line of
     * its message (for a Groovy compilation error the next one, without the generated script's name), OIDs
     * normalized.
     */
    public String error(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String[] lines = String.valueOf(root.getMessage()).split("\\R");
        String message = lines.length > 0 ? lines[0].strip() : "";
        if (message.endsWith(":")) {
            // e.g. Groovy's "startup failed:": the cause is on the next line
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    message += " " + lines[i].strip();
                    break;
                }
            }
        }
        // generated script class names (Script12.groovy) change with the order scripts are compiled in
        message = message.replaceAll("\\bScript\\d+\\b", "Script");
        return text(className(root.getClass()) + ": " + message);
    }

    private String item(Item<?, ?> item) {
        return item == null ? "null" : values(item.getValues());
    }

    private static String object(PrismObject<?> o) {
        if (o == null) {
            return "null";
        }
        Class<?> type = o.getCompileTimeClass();
        return (type != null ? type.getSimpleName().replaceAll("Type$", "") : "Object") + ":" + polyName(o)
                + "(" + o.getOid() + ")";
    }

    private static String name(Object o) {
        if (o instanceof Objectable) {
            Objectable ob = (Objectable) o;
            return ob.getClass().getSimpleName().replaceAll("Type$", "") + ":" + polyName(ob.asPrismObject());
        }
        return String.valueOf(o);
    }

    private static String polyName(PrismObject<?> o) {
        return o.getName() != null ? o.getName().getOrig() : "-";
    }

    private static String ref(QName type, String oid, PolyString targetName) {
        return "ref " + (type != null ? type.getLocalPart().replaceAll("Type$", "") : "?") + ":"
                + (targetName != null ? targetName.getOrig() : "") + "(" + oid + ")";
    }

    private static void collectNames(Object v, Map<String, String> names) {
        if (v instanceof ObjectDeltaObject) {
            ObjectDeltaObject<?> odo = (ObjectDeltaObject<?>) v;
            collectNames(odo.getOldObject(), names);
            collectNames(odo.getNewObject(), names);
        } else if (v instanceof Objectable) {
            collectNames(((Objectable) v).asPrismObject(), names);
        } else if (v instanceof PrismObject) {
            PrismObject<?> o = (PrismObject<?>) v;
            if (o.getOid() != null && o.getName() != null) {
                names.putIfAbsent(o.getOid(), o.getName().getOrig());
            }
        }
    }
}
