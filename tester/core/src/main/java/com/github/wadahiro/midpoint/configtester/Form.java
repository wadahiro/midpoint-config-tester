package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.configtester.names.ObjectRef;

/**
 * An object's form as the GUI shows it to a user ({@link Actor#form}): midPoint applies the user's authorizations to
 * the object's definition ({@code getEditObjectDefinition}, request phase), and the GUI hides the items the user may
 * not read and shows read-only those they may not change. Paths are without namespaces
 * ({@code activation/administrativeStatus}, {@code extension/hrId}).
 *
 * <pre>{@code
 * Form form = s.actor(Users.HELPDESK).form(Users.JACK);
 * assertThat(form.modifiableItems()).containsExactly("telephoneNumber");
 * assertThat(form.canRead("costCenter")).isTrue();
 * }</pre>
 */
public final class Form {

    private final String user;
    private final ObjectRef object;
    private final PrismObjectDefinition<?> definition;

    Form(String user, ObjectRef object, PrismObjectDefinition<?> definition) {
        this.user = user;
        this.object = object;
        this.definition = definition;
    }

    /** Whether the user may read the object at all (else the GUI does not open it, and no item is readable). */
    public boolean opens() {
        return definition != null;
    }

    /** Whether the form shows the item. */
    public boolean canRead(String path) {
        ItemDefinition<?> item = item(path);
        return item != null && item.canRead();
    }

    /** Whether the form lets the user change the item. */
    public boolean canModify(String path) {
        ItemDefinition<?> item = item(path);
        return item != null && item.canModify();
    }

    public boolean canRead(ItemRef item) {
        return canRead(item.path());
    }

    public boolean canModify(ItemRef item) {
        return canModify(item.path());
    }

    /** The object's top-level items the form shows, in the schema's order. */
    public List<String> readableItems() {
        List<String> out = new ArrayList<>();
        for (ItemDefinition<?> item : items()) {
            if (item.canRead()) {
                out.add(item.getItemName().getLocalPart());
            }
        }
        return out;
    }

    /**
     * The object's top-level items the form lets the user change, in the schema's order; a container is listed when
     * the user may change any of its items ({@code activation} for {@code activation/administrativeStatus}).
     */
    public List<String> modifiableItems() {
        List<String> out = new ArrayList<>();
        for (ItemDefinition<?> item : items()) {
            if (item.canModify()) {
                out.add(item.getItemName().getLocalPart());
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "form of " + object + " for " + user + (definition == null ? " (not readable)"
                : ": reads " + readableItems() + ", modifies " + modifiableItems());
    }

    private List<ItemDefinition<?>> items() {
        List<ItemDefinition<?>> out = new ArrayList<>();
        if (definition != null) {
            // raw on 4.0 (List<? extends ItemDefinition>)
            for (Object item : definition.getDefinitions()) {
                out.add((ItemDefinition<?>) item);
            }
        }
        return out;
    }

    private ItemDefinition<?> item(String path) {
        if (definition == null) {
            return null;
        }
        List<Object> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            segments.add(new QName(segment));
        }
        return definition.findItemDefinition(ItemPath.create(segments.toArray()));
    }
}
