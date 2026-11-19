package com.github.wadahiro.midpoint.tester.internal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.apache.wicket.behavior.Behavior;
import org.apache.wicket.extensions.markup.html.tabs.ITab;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.StringResourceModel;

/**
 * The tabs of a details page of the old design (4.0; 4.4 with {@code useNewDesign} false), for the line's
 * {@link GuiLine#tabs}: the tabs of the page's tabbed panel, which keeps the hidden ones (a PanelTab is visible as its
 * VisibleEnableBehaviour says: the FocusTabVisibleBehavior of the user's {@code objectForms} of the type, 4.4's
 * ObjectTabVisibleBehavior; and the tab's own rule, e.g. governance and members not on a new object). The tab's
 * behaviour and its URI are private fields of midPoint's classes, read by reflection as the forms read the wrappers.
 */
final class LegacyTabs {

    private LegacyTabs() {
    }

    /**
     * The tabs, in the panel's order.
     *
     * @param type the local name of the page's object type ({@code OrgType}), as the user's objectForms name it
     * @param newObject whether the page is a new object's (ItemStatus ADDED)
     */
    static List<GuiForms.TabInfo> read(List<? extends ITab> tabs, String type, boolean newObject) {
        List<GuiForms.TabInfo> out = new ArrayList<>();
        for (ITab tab : tabs) {
            IModel<String> title = tab.getTitle();
            String label = title != null ? title.getObject() : null;
            String key = title instanceof StringResourceModel ? (String) field(title, "resourceKey") : null;
            // a title that is no key (a custom objectForm's) renders as itself: a literal
            if (key != null && key.equals(label)) {
                key = null;
            }
            // PanelTab's VisibleEnableBehaviour (another tab's field of the name is no behaviour)
            Object behaviour = field(tab, "visible");
            behaviour = behaviour instanceof Behavior ? behaviour : null;
            String uri = behaviour != null ? (String) field(behaviour, "uiAuthorizationUrl") : null;
            String identifier = uri != null ? uri.substring(uri.lastIndexOf('#') + 1) : null;
            boolean shown = tab.isVisible();
            String because = null;
            if (!shown) {
                if (behaviour != null && !visible(behaviour)) {
                    because = "no objectForm of " + type + " names " + identifier;
                    // 4.4's behaviour of a tab not for a new object is an anonymous subclass that hides it there too
                    if (newObject && behaviour.getClass().isAnonymousClass()) {
                        because = "not on a new object, or " + because;
                    }
                } else if (newObject) {
                    because = "not on a new object";
                } else {
                    because = "hidden by the page's own rule";
                }
            }
            out.add(new GuiForms.TabInfo(label, key, identifier, shown, because, List.of()));
        }
        return out;
    }

    private static boolean visible(Object behaviour) {
        try {
            // the behaviour may be an anonymous class: its public method is not accessible as it is
            java.lang.reflect.Method isVisible = behaviour.getClass().getMethod("isVisible");
            isVisible.setAccessible(true);
            return (Boolean) isVisible.invoke(behaviour);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No isVisible() on " + behaviour.getClass().getName() + ": " + e, e);
        }
    }

    /** The value of the object's field of that name (declared by its class or a superclass), or null: none. */
    private static Object field(Object object, String name) {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException e) {
                // a superclass's
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + name + " of " + c.getName() + ": " + e, e);
            }
        }
        return null;
    }
}
