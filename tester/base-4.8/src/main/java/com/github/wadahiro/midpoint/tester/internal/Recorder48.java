package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.common.mapping.AbstractMappingImpl;
import com.evolveum.midpoint.model.common.mapping.RecordingMappingImpl;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Itemable;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectValue;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.PrismValueDeltaSetTriple;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.repo.common.expression.ExpressionEvaluationContext;
import com.evolveum.midpoint.repo.common.expression.Source;
import com.evolveum.midpoint.schema.config.ConfigurationItemOrigin;
import com.evolveum.midpoint.schema.expression.TypedValue;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.MappingType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.Evaluation;

/**
 * The recorder hooks of 4.8: fills a {@link Record} when a mapping ({@link RecordingMappingImpl}) or an expression
 * evaluator ({@link EvaluatorHook}) runs.
 *
 * A mapping's owner is the object of its {@link ConfigurationItemOrigin} (precise for templates and assignments, the
 * resource for resource mappings; the object a delta is for). Its position is the place of its {@code MappingType}
 * value in the owner object as loaded; for a copy the place the origin's path points to (role constructions, focus
 * mappings, conditions), or the copy's own container IDs (resource object types and association types), else the
 * one equivalent value ({@link MappingPlacement}). See docs/line-anatomy.md, "Mapping placement".
 */
final class Recorder48 implements EvaluatorHook.Listener, RecordingMappingImpl.Listener {

    private final Field conditionResultOld;
    private final Field conditionResultNew;

    Recorder48() {
        try {
            conditionResultOld = AbstractMappingImpl.class.getDeclaredField("conditionResultOld");
            conditionResultNew = AbstractMappingImpl.class.getDeclaredField("conditionResultNew");
            conditionResultOld.setAccessible(true);
            conditionResultNew.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Unexpected mapping implementation", e);
        }
    }

    // ---- mappings -------------------------------------------------------------------------------------------

    @Override
    public Object started(RecordingMappingImpl<?, ?> mapping) {
        Record record = Recorder.begin(Evaluation.Kind.MAPPING);
        if (record == null) {
            return null;
        }
        try {
            MappingType bean = mapping.getMappingBean();
            record.mappingName = bean.getName();
            record.description = mapping.getContextDescription();
            identify(record, mapping, bean);
        } catch (RuntimeException e) {
            failed(e);
        }
        return record;
    }

    @Override
    public void finished(Object token, RecordingMappingImpl<?, ?> mapping, Throwable error, long nanos) {
        Record record = (Record) token;
        if (record == null) {
            return;
        }
        record.nanos = nanos;
        try {
            record.target = mapping.getOutputPath() != null ? mapping.getOutputPath().toString()
                    : LineSupport.mappingTargetName(mapping) != null
                            ? LineSupport.mappingTargetName(mapping).getLocalPart() : null;
            variables(record, mapping.recordedVariables());
            sources(record, mapping.recordedSources());
            record.conditionOld = (Boolean) conditionResultOld.get(mapping);
            record.conditionNew = (Boolean) conditionResultNew.get(mapping);
            output(record, mapping.getOutputTriple());
            record.error = error;
        } catch (RuntimeException | IllegalAccessException e) {
            failed(e);
        } finally {
            Recorder.end(record);
        }
    }

    /**
     * Owner and position: the mapping bean's own place when it is still part of its object (templates; role mappings on
     * 4.10), else the place midPoint's origin path points to (role constructions, focus mappings, conditions, template
     * items on 4.8 and 4.9), else the place of the copy's container IDs (resource object types and association types),
     * else the one value of the owner equivalent to the bean ({@link MappingPlacement}).
     */
    private static void identify(Record record, RecordingMappingImpl<?, ?> mapping, MappingType bean) {
        ConfigurationItemOrigin origin = mapping.recordedOrigin();
        PrismObject<?> owner = null;
        ItemPath originPath = null;
        if (origin instanceof ConfigurationItemOrigin.InObject) {
            ConfigurationItemOrigin.InObject inObject = (ConfigurationItemOrigin.InObject) origin;
            owner = inObject.getOriginatingPrismObject();
            // an approximate origin (e.g. a resource mapping) has no usable path
            originPath = origin.toApproximate() != origin ? inObject.getPath() : null;
        } else if (origin instanceof ConfigurationItemOrigin.InDelta) {
            // e.g. the condition of an assignment being added: the object the delta is for
            ConfigurationItemOrigin.InDelta inDelta = (ConfigurationItemOrigin.InDelta) origin;
            owner = inDelta.getTargetPrismObject();
            originPath = inDelta.getPath();
        } else if (mapping.getOriginObject() != null) {
            owner = mapping.getOriginObject().asPrismObject();
        }
        PrismContainerValue<?> value = bean.asPrismContainerValue();
        Objectable root = value.getRootObjectable();
        if (root instanceof ObjectType && (owner == null || ((ObjectType) root).getOid() != null
                && ((ObjectType) root).getOid().equals(owner.getOid()))) {
            owner = root.asPrismObject();
            record.elementPath = elementPath(value);
        } else if (owner != null) {
            if (originPath != null && !originPath.isEmpty()) {
                record.elementPath = originElementPath(owner, originPath, bean);
            }
            if (record.elementPath == null && root == null) {
                record.elementPath = detachedElementPath(value, owner);
            }
            if (record.elementPath == null) {
                record.elementPath = MappingPlacement.place(owner, value);
            }
        }
        if (owner != null) {
            record.ownerType = owner.getCompileTimeClass() != null ? owner.getCompileTimeClass().getSimpleName() : null;
            record.ownerOid = owner.getOid();
            record.ownerName = owner.getName() != null ? owner.getName().getOrig() : null;
        }
    }

    /**
     * The place of the mapping that midPoint's origin path points to. The path is not always the mapping's own: a
     * focus mapping's ends with the mapping's ID right after {@code focusMappings} (no {@code mapping} step), an
     * assignment's condition has the assignment's. So the path is followed as far as it goes in the owner, and the
     * mapping is the value below that place with the bean's ID (when it has one) and name, else the only one
     * equivalent to the bean. A path that leads to a mapping in full is the mapping's own: that mapping is the place
     * even when the bean is no longer equivalent to it (the multi-source mappings of a template, which midPoint copies
     * and gives the identity sources and the target).
     */
    private static String originElementPath(PrismObject<?> owner, ItemPath path, MappingType bean) {
        List<?> segments = path.getSegments();
        PrismContainerValue<?> base = null;
        boolean whole = false;
        for (int n = segments.size(); n > 0 && base == null; n--) {
            Object found;
            try {
                found = owner.find(ItemPath.create(segments.subList(0, n)));
            } catch (RuntimeException e) {
                continue;
            }
            if (found instanceof PrismContainer && ((PrismContainer<?>) found).size() == 1) {
                found = ((PrismContainer<?>) found).getValue();
            }
            if (found instanceof PrismContainerValue) {
                base = (PrismContainerValue<?>) found;
                whole = n == segments.size();
            }
        }
        if (base == null) {
            return null;
        }
        if (isMapping(base) && (matches(base, bean) || whole
                && java.util.Objects.equals(((MappingType) base.asContainerable()).getName(), bean.getName()))) {
            return elementPath(base);
        }
        List<PrismContainerValue<?>> found = new ArrayList<>();
        collectMappings(base, bean, found);
        return found.size() == 1 ? elementPath(found.get(0)) : null;
    }

    private static void collectMappings(PrismContainerValue<?> value, MappingType bean,
            List<PrismContainerValue<?>> found) {
        for (Item<?, ?> item : value.getItems()) {
            if (!(item instanceof PrismContainer)) {
                continue;
            }
            for (PrismContainerValue<?> child : ((PrismContainer<?>) item).getValues()) {
                if (isMapping(child) && matches(child, bean)) {
                    found.add(child);
                } else {
                    collectMappings(child, bean, found);
                }
            }
        }
    }

    private static boolean isMapping(PrismContainerValue<?> value) {
        return value.getCompileTimeClass() != null && MappingType.class.isAssignableFrom(value.getCompileTimeClass());
    }

    /** The same mapping: the same ID when the bean has one, and the same name; without an ID, equivalent. */
    private static boolean matches(PrismContainerValue<?> value, MappingType bean) {
        MappingType candidate = (MappingType) value.asContainerable();
        if (!java.util.Objects.equals(candidate.getName(), bean.getName())) {
            return false;
        }
        Long id = bean.asPrismContainerValue().getId();
        return id != null ? id.equals(value.getId()) : value.equivalent(bean.asPrismContainerValue());
    }

    /**
     * Where a detached copy of a mapping was in its owner. Resource object types and association types are parsed into
     * definitions that keep copies of parts of the {@code schemaHandling} values with their container IDs: whole object
     * types on 4.8 and 4.9, single attribute definitions on 4.10, association definitions without their item on 4.9+.
     * The topmost value of the copy that has an ID is found in the owner by that ID (unique in an object) and the name
     * of its item, then the copy's path below it is followed by ID, else by position.
     */
    private static String detachedElementPath(PrismContainerValue<?> value, PrismObject<?> owner) {
        List<PrismContainerValue<?>> chain = new ArrayList<>();
        chain.add(value);
        PrismContainerValue<?> top = value;
        while (top.getParent() instanceof Item && ((Item<?, ?>) top.getParent()).getParent() instanceof PrismContainerValue) {
            top = (PrismContainerValue<?>) ((Item<?, ?>) top.getParent()).getParent();
            chain.add(0, top);
        }
        int first = 0;
        while (first < chain.size() && chain.get(first).getId() == null) {
            first++;
        }
        if (first == chain.size()) {
            return null;
        }
        PrismContainerValue<?> start = chain.get(first);
        // the copied value may have lost its item (4.10 attribute definitions): then the ID alone
        QName itemName = start.getParent() instanceof Item ? ((Item<?, ?>) start.getParent()).getElementName() : null;
        PrismContainerValue<?> current = findById(owner.getValue(), itemName, start.getId());
        for (int i = first + 1; current != null && i < chain.size(); i++) {
            PrismContainerValue<?> step = chain.get(i);
            Item<?, ?> copyItem = (Item<?, ?>) step.getParent();
            PrismContainer<?> item = current.findContainer(copyItem.getElementName());
            if (item == null) {
                return null;
            }
            int index = indexOf(copyItem.getValues(), step);
            current = step.getId() != null ? item.findValue(step.getId())
                    : item.size() == copyItem.size() && index >= 0 ? item.getValues().get(index) : null;
        }
        return current != null ? elementPath(current) : null;
    }

    /** The container value with that ID in an item of that name (any if null), anywhere below {@code value}. */
    private static PrismContainerValue<?> findById(PrismContainerValue<?> value, QName itemName, long id) {
        for (Item<?, ?> item : value.getItems()) {
            if (!(item instanceof PrismContainer)) {
                continue;
            }
            for (PrismContainerValue<?> child : ((PrismContainer<?>) item).getValues()) {
                if (id == (child.getId() != null ? child.getId() : -1L)
                        && (itemName == null || QNameUtil.match(itemName, item.getElementName()))) {
                    return child;
                }
                PrismContainerValue<?> found = findById(child, itemName, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** {@link #elementPath(PrismContainerValue)} of what {@code find} returned: a value, or a single-valued item. */
    private static String elementPath(Object found) {
        if (found instanceof PrismContainer && ((PrismContainer<?>) found).size() == 1) {
            found = ((PrismContainer<?>) found).getValue();
        }
        return found instanceof PrismContainerValue && !(found instanceof PrismObjectValue)
                ? elementPath((PrismContainerValue<?>) found) : null;
    }

    /**
     * The value's place below its object's root, e.g. {@code inducement[1]/construction[1]/attribute[1]/outbound[1]}
     * (positions among the item's values, from 1); null if the value is not part of an object.
     */
    static String elementPath(PrismContainerValue<?> value) {
        List<String> segments = new ArrayList<>();
        PrismValue current = value;
        while (true) {
            Itemable parent = current.getParent();
            if (!(parent instanceof Item)) {
                return null;
            }
            Item<?, ?> item = (Item<?, ?>) parent;
            if (item instanceof PrismObject) {
                break;
            }
            int index = indexOf(item.getValues(), current);
            if (index < 0) {
                return null;
            }
            segments.add(0, item.getElementName().getLocalPart() + "[" + (index + 1) + "]");
            current = item.getParent();
            if (current == null) {
                return null;
            }
        }
        return String.join("/", segments);
    }

    private static int indexOf(List<?> values, Object value) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == value) {
                return i;
            }
        }
        return -1;
    }

    // ---- expression evaluators ------------------------------------------------------------------------------

    @Override
    public Object before(String fingerprint, Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof ExpressionEvaluationContext)) {
            return null;
        }
        Record record = Recorder.begin(Evaluation.Kind.EXPRESSION);
        if (record == null) {
            return null;
        }
        try {
            ExpressionEvaluationContext context = (ExpressionEvaluationContext) args[0];
            record.fingerprint = fingerprint;
            record.part = context.getLocalContextDescription();
            record.description = context.getContextDescription();
            variables(record, context.getVariables());
            sources(record, context.getSources());
            if (record.parent != null && record.parent.kind == Evaluation.Kind.MAPPING
                    && "expression".equals(record.part)) {
                // the mapping evaluates its expression for the states its condition holds in
                record.conditionOld = !context.isSkipEvaluationMinus();
                record.conditionNew = !context.isSkipEvaluationPlus();
            }
        } catch (RuntimeException e) {
            failed(e);
        }
        return record;
    }

    @Override
    public void after(Object token, Object output, Throwable error) {
        Record record = (Record) token;
        if (record == null) {
            return;
        }
        try {
            output(record, output instanceof PrismValueDeltaSetTriple ? (PrismValueDeltaSetTriple<?>) output : null);
            record.error = error;
        } catch (RuntimeException e) {
            failed(e);
        } finally {
            Recorder.end(record);
        }
    }

    // ---- values ---------------------------------------------------------------------------------------------

    private static void variables(Record record, VariablesMap variables) {
        if (variables == null) {
            return;
        }
        for (Map.Entry<String, TypedValue<?>> e : variables.entrySet()) {
            // the unnamed variable is the default object of relative paths, not something a script can use
            if (e.getKey() != null) {
                record.variables.put(e.getKey(), e.getValue() != null ? e.getValue().getValue() : null);
            }
        }
    }

    private static void sources(Record record, Collection<? extends Source<?, ?>> sources) {
        if (sources == null) {
            return;
        }
        for (Source<?, ?> source : sources) {
            String name = source.getName() != null ? source.getName().getLocalPart() : "?";
            record.sources.put(name, new Evaluation.SourceValues(realValues(source.getItemOld()),
                    realValues(source.getItemNew())));
        }
    }

    private static List<Object> realValues(Item<?, ?> item) {
        return item == null ? new ArrayList<>() : realValues(item.getValues());
    }

    /** The triple's sets, copied now: midPoint keeps changing the triple after the evaluation. */
    private static void output(Record record, PrismValueDeltaSetTriple<?> triple) {
        if (triple == null) {
            return;
        }
        record.hasOutput = true;
        record.zero = realValues(triple.getZeroSet());
        record.plus = realValues(triple.getPlusSet());
        record.minus = realValues(triple.getMinusSet());
    }

    private static List<Object> realValues(Collection<? extends PrismValue> values) {
        List<Object> out = new ArrayList<>();
        if (values != null) {
            for (PrismValue value : values) {
                out.add(value != null ? value.getRealValue() : null);
            }
        }
        return out;
    }

    private static void failed(Exception e) {
        // the recorder must never change what midPoint does
        System.err.println("Evaluation recorder failed: " + e);
    }
}
