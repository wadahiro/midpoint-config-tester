package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.github.wadahiro.midpoint.tester.internal.ItemValues;

/**
 * An object's items by a path of local names ({@code costCenter}, {@code extension/hrId}, {@code assignment/targetRef}),
 * as {@link Focus#hasItem} and {@link ObjectView#hasItem} read them.
 */
final class ItemReading {

    private ItemReading() {
    }

    /** The real values at the path, through every value of a multi-valued container on the way ({@code assignment}). */
    static Collection<?> realValues(PrismObject<? extends ObjectType> object, String path) {
        List<PrismContainerValue<?>> containers = new ArrayList<>();
        containers.add(object.getValue());
        String[] segments = path.split("/");
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            List<PrismContainerValue<?>> next = new ArrayList<>();
            for (PrismContainerValue<?> container : containers) {
                Item<?, ?> item = container.findItem(ItemPath.create(new QName(segments[i])));
                if (item == null) {
                    continue;
                }
                if (i == segments.length - 1) {
                    // a structured item of the extension schema has no Java class to give its values as
                    out.addAll(item instanceof PrismContainer && ((PrismContainer<?>) item).getCompileTimeClass() == null
                            ? item.getValues() : item.getRealValues());
                } else {
                    for (Object value : item.getValues()) {
                        if (value instanceof PrismContainerValue) {
                            next.add((PrismContainerValue<?>) value);
                        }
                    }
                }
            }
            containers = next;
        }
        return out;
    }

    /**
     * The expected values as the item's type has them, so that they compare as midPoint's values do ({@code "5"} and
     * {@code 5}, a date with an offset and the same instant); as given when the path has no definition.
     */
    static Object[] expected(PrismContext prismContext, Class<? extends ObjectType> type, String path,
            Object[] expected) {
        com.evolveum.midpoint.prism.ItemDefinition<?> definition;
        try {
            definition = ItemValues.locate(prismContext.getSchemaRegistry()
                    .findObjectDefinitionByCompileTimeClass(type), path, type.getSimpleName()).definition;
        } catch (IllegalArgumentException e) {
            return expected;
        }
        boolean structured = definition instanceof PrismContainerDefinition
                && ((PrismContainerDefinition<?>) definition).getCompileTimeClass() == null;
        if (!(definition instanceof PrismPropertyDefinition) && !structured) {
            return expected;
        }
        List<Object> out = new ArrayList<>();
        for (Object value : expected) {
            for (Object one : value instanceof Collection ? (Collection<?>) value : List.of(value)) {
                out.add(Struct.values(prismContext, path, definition, one)[0]);
            }
        }
        return out.toArray();
    }
}
