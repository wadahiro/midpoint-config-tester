package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.ajax.markup.html.AjaxFallbackLink;
import org.apache.wicket.markup.html.link.Link;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.util.tester.WicketTester;

/**
 * What the lines' {@link GuiLine#openAssignments} share, with Wicket's types only (the GUI's classes differ by line):
 * clicking the details navigation of the new design (4.4 and later) to a sub-panel, finding the panel of the rows by
 * its class, an icon's CSS classes, a click as the browser makes it.
 */
final class AssignmentTabs {

    private AssignmentTabs() {
    }

    /**
     * The new design's details navigation (DetailsNavigationPanel: its {@code menu} list of items, each with
     * {@code navLinkStyle:navItemLink} and a {@code navLinkStyle:subNavigation} of the sub-panels): the item of the
     * panel's identifier clicked when its sub-navigation is not shown (4.10 and later expand it on a click; 4.8 always
     * renders it), then the sub-item of {@code child} (else the first one shown). False: the page shows no such item.
     */
    static boolean navigate(WicketTester tester, String parent, String child) {
        ListItem<?> item = navigationItem(tester.getLastRenderedPage(), parent);
        if (item == null || !item.determineVisibility() || !item.isVisibleInHierarchy()) {
            return false;
        }
        Component link = subItemLink(item, child);
        if (link == null) {
            click(tester, item.get("navLinkStyle:navItemLink"));
            item = navigationItem(tester.getLastRenderedPage(), parent);
            link = item == null ? null : subItemLink(item, child);
        }
        if (link == null) {
            // a grouping panel without a sub-panel shown: the item itself
            return item != null;
        }
        click(tester, link);
        return true;
    }

    /** The top navigation's item of the panel's identifier, or null. */
    private static ListItem<?> navigationItem(Page page, String identifier) {
        MarkupContainer navigation = (MarkupContainer) first(page, "DetailsNavigationPanel", false);
        if (navigation == null) {
            return null;
        }
        return item((MarkupContainer) navigation.get("menu"), identifier);
    }

    private static ListItem<?> item(MarkupContainer menu, String identifier) {
        if (menu == null) {
            return null;
        }
        for (Component child : menu) {
            ListItem<?> item = (ListItem<?>) child;
            if (identifier.equals(identifier(item.getModelObject()))) {
                return item;
            }
        }
        return null;
    }

    /** The link of the sub-item {@code child} shown, else of the first sub-item shown; null: none shown. */
    private static Component subItemLink(ListItem<?> item, String child) {
        Component sub = item.get("navLinkStyle:subNavigation:menu");
        if (!(sub instanceof MarkupContainer) || !sub.isVisibleInHierarchy()) {
            return null;
        }
        ListItem<?> wanted = item((MarkupContainer) sub, child);
        if (wanted != null && wanted.isVisibleInHierarchy()) {
            return wanted.get("navLinkStyle:navItemLink");
        }
        for (Component other : (MarkupContainer) sub) {
            if (other.isVisibleInHierarchy()) {
                return ((MarkupContainer) other).get("navLinkStyle:navItemLink");
            }
        }
        return null;
    }

    /** The identifier of a navigation item's panel (4.10 and later a ContainerPanelDto of it). */
    private static String identifier(Object item) {
        Object config = item;
        if (item != null && !item.getClass().getSimpleName().equals("ContainerPanelConfigurationType")) {
            config = call(item, "getContainerPanelConfig");
        }
        Object identifier = call(config, "getIdentifier");
        return identifier == null ? null : String.valueOf(identifier);
    }

    /**
     * The first component of the page (visible in its hierarchy when {@code visible}) that is an instance of a class of
     * this simple name, or of a subclass; null: none.
     */
    static Component first(MarkupContainer container, String simpleName, boolean visible) {
        Component[] found = new Component[1];
        container.visitChildren((component, visit) -> {
            if (is(component, simpleName) && (!visible || component.isVisibleInHierarchy())) {
                found[0] = component;
                visit.stop();
            }
        });
        return found[0];
    }

    /**
     * The panel of the rows of the tab just opened: the first one of the class shown, else the first one hidden (4.0:
     * a user who may not read the item gets a panel without its list), else the page (a tab without the panel: no
     * rows).
     */
    static Component panel(Page page, String simpleName) {
        Component shown = first(page, simpleName, true);
        if (shown != null) {
            return shown;
        }
        Component hidden = first(page, simpleName, false);
        return hidden != null ? hidden : page;
    }

    /** Whether the object's class or a superclass has this simple name. */
    static boolean is(Object object, String simpleName) {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the icon's CSS classes have every class of {@code wanted} ({@code "fa fa-minus"}). */
    static boolean hasClasses(String icon, String wanted) {
        if (icon == null || wanted == null) {
            return false;
        }
        List<String> classes = Arrays.asList(icon.trim().split("\\s+"));
        for (String c : wanted.trim().split("\\s+")) {
            if (!classes.contains(c)) {
                return false;
            }
        }
        return true;
    }

    /** A click as the browser makes it: an Ajax link's (a submit link submits its form), else a plain link's. */
    static void click(WicketTester tester, Component link) {
        if (link instanceof Link && !(link instanceof AjaxFallbackLink)) {
            tester.clickLink(link.getPageRelativePath(), false);
        } else {
            // its click behavior as executeAjaxEvent runs it: a submit link's form serialized (multipart when the form
            // is, as 4.4's details form is)
            tester.executeAjaxEvent(link, "click");
        }
    }

    /** The descendants of the container visible in its hierarchy that the predicate accepts. */
    static List<Component> visible(MarkupContainer container, java.util.function.Predicate<Component> accepted) {
        List<Component> out = new ArrayList<>();
        container.visitChildren((component, visit) -> {
            if (component.isVisibleInHierarchy() && accepted.test(component)) {
                out.add(component);
            }
        });
        return out;
    }

    static Object call(Object target, String method) {
        try {
            if (target == null) {
                return null;
            }
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The value of the object's private field of the name (its class's or a superclass's), or null. */
    static Object field(Object object, String name) {
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException e) {
                // a superclass's
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
