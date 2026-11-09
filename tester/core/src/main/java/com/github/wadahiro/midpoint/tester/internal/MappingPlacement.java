package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Itemable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectValue;
import com.evolveum.midpoint.prism.PrismValue;

/**
 * Where a mapping's value is in the object that holds it, as {@link Record#elementPath}, for lines whose mappings do
 * not say where they come from (4.0, 4.4 have no {@code ConfigurationItemOrigin}; the mapping only knows its origin
 * object):
 * <ol>
 *   <li>the value is still part of an object (templates, roles as loaded): its own place there;</li>
 *   <li>else the value is a copy (resource object type definitions, constructions): the one value of the origin
 *       object that is equivalent to it (prism content equality, container IDs included). Several equivalent values
 *       (two identical mappings in one object) give no place; the index then locates the mapping by its name or by
 *       the canonical form of its expression, as for other lines.</li>
 * </ol>
 * Only prism API that is the same on every line is used.
 */
public final class MappingPlacement {

    private MappingPlacement() {
    }

    /** The object the value is part of, or null for a detached value. */
    public static PrismObject<?> rootObject(PrismContainerValue<?> value) {
        PrismValue root = value.getRootValue();
        return root instanceof PrismObjectValue ? ((PrismObjectValue<?>) root).asObjectable().asPrismObject() : null;
    }

    /**
     * The value's place in {@code owner}: its own place if it is part of the owner, else the place of the only value of
     * the owner equivalent to it; null when there is none.
     */
    public static String place(PrismObject<?> owner, PrismContainerValue<?> value) {
        PrismObject<?> root = rootObject(value);
        if (root != null && (owner == null || root == owner
                || root.getOid() != null && root.getOid().equals(owner.getOid()))) {
            return elementPath(value);
        }
        if (owner == null) {
            return null;
        }
        List<PrismContainerValue<?>> found = new ArrayList<>();
        collectEquivalent(owner.getValue(), value, found);
        return found.size() == 1 ? elementPath(found.get(0)) : null;
    }

    private static void collectEquivalent(PrismContainerValue<?> container, PrismContainerValue<?> value,
            List<PrismContainerValue<?>> found) {
        for (Item<?, ?> item : container.getItems()) {
            if (!(item instanceof PrismContainer)) {
                continue;
            }
            for (PrismContainerValue<?> child : ((PrismContainer<?>) item).getValues()) {
                if (child.getCompileTimeClass() != null && child.getCompileTimeClass() == value.getCompileTimeClass()
                        && child.equivalent(value)) {
                    found.add(child);
                } else {
                    collectEquivalent(child, value, found);
                }
            }
        }
    }

    /**
     * The value's place below its object's root, e.g. {@code inducement[1]/construction[1]/attribute[1]/outbound[1]}
     * (positions among the item's values, from 1); null if the value is not part of an object.
     */
    public static String elementPath(PrismContainerValue<?> value) {
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
}
