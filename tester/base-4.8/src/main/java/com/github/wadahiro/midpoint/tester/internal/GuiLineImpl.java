package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.markup.html.list.ListItem;

import com.evolveum.midpoint.authentication.api.authorization.PageDescriptor;
import com.evolveum.midpoint.gui.api.prism.ItemStatus;
import com.evolveum.midpoint.gui.api.prism.wrapper.ItemWrapper;
import com.evolveum.midpoint.gui.api.util.GuiDisplayTypeUtil;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.impl.component.menu.DetailsNavigationPanel;
import com.evolveum.midpoint.gui.impl.page.admin.AbstractPageObjectDetails;
import com.evolveum.midpoint.gui.impl.prism.panel.ItemPanel;
import com.evolveum.midpoint.gui.impl.util.DetailsPageUtil;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.web.security.MidPointApplication;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ContainerPanelConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationTypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserInterfaceElementVisibilityType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * The GUI of 4.8 and later for {@link GuiFormsImpl}: its details pages (DetailsPageUtil), item panels, progress, the
 * items of the details navigation.
 */
final class GuiLineImpl implements GuiLine {

    @Override
    public Class<? extends Page> detailsPage(Class<? extends ObjectType> type) {
        return DetailsPageUtil.getObjectDetailsPage(type);
    }

    @Override
    public Class<? extends Page> newObjectPageClass(Class<? extends ObjectType> type) {
        return DetailsPageUtil.getNewlyCreatedObjectPage(type);
    }

    @Override
    public Class<? extends Page> selfProfilePage() {
        // PageSelfProfile (4.8 to 4.11) sends to the page of the logged-in focus: PageUserSelfProfile
        // (/self/profile/user)
        return com.evolveum.midpoint.gui.api.util.WebComponentUtil.resolveSelfPage();
    }

    @Override
    public Class<? extends Page> selfCredentialsPage() {
        // 4.8 to 4.11: the PropagatePasswordPanel of the logged-in focus, its Change password button
        return com.evolveum.midpoint.gui.impl.page.self.credentials.PageSelfCredentials.class;
    }

    @Override
    public boolean storedIntoParentOrgRef(QName relation) {
        // DetailsPageUtil.initNewObjectWithReference: as the system configuration's relation definitions say
        return MidPointApplication.get().getRelationRegistry().isStoredIntoParentOrgRef(relation);
    }

    @Override
    public Page newObjectPage(Class<? extends Page> page, PrismObject<?> object) throws Exception {
        // as DetailsPageUtil.dispatchToObjectDetailsPage(object, true, component)
        return DetailsPageUtil.isNewDesignEnabled()
                ? page.getConstructor(PrismObject.class).newInstance(object)
                : page.getConstructor(PrismObject.class, boolean.class).newInstance(object, true);
    }

    @Override
    public String mountUrl(Class<?> page) {
        PageDescriptor descriptor = page.getAnnotation(PageDescriptor.class);
        return descriptor != null && descriptor.urls().length > 0 ? descriptor.urls()[0].mountUrl() : null;
    }

    @Override
    public Object wrapper(Component component) {
        if (component instanceof ItemPanel) {
            Object wrapper = ((ItemPanel<?, ?>) component).getModelObject();
            return wrapper instanceof ItemWrapper ? (ItemWrapper<?, ?>) wrapper : null;
        }
        return null;
    }

    @Override
    public ItemPath path(Object wrapper) {
        return ((ItemWrapper<?, ?>) wrapper).getPath();
    }

    @Override
    public boolean validated(Object wrapper) {
        return ((ItemWrapper<?, ?>) wrapper).getFormComponentValidator() != null;
    }

    @Override
    public boolean multiValue(Object wrapper) {
        return ((ItemWrapper<?, ?>) wrapper).isMultiValue();
    }

    @Override
    public List<MarkupContainer> valueRows(Component itemPanel) {
        List<MarkupContainer> out = new ArrayList<>();
        Component values = ((MarkupContainer) itemPanel).get("valuesContainer:values");
        if (values instanceof MarkupContainer) {
            for (Component row : (MarkupContainer) values) {
                out.add((MarkupContainer) row);
            }
        }
        return out;
    }

    @Override
    public Component addValueButton(Component itemPanel) {
        return ((MarkupContainer) itemPanel).get("header:add");
    }

    @Override
    public Component removeValueButton(MarkupContainer row) {
        return GuiLine.descendant(row, "removeButton");
    }

    /**
     * The details navigation's {@code assignments} / {@code inducements} item, then its {@code allAssignments} /
     * {@code allInducements} (AbstractPageObjectDetails.replacePanel): the AbstractAssignmentTypePanel shown.
     */
    @Override
    public Component openAssignments(org.apache.wicket.util.tester.WicketTester tester, boolean inducements) {
        if (!AssignmentTabs.navigate(tester, inducements ? "inducements" : "assignments",
                inducements ? "allInducements" : "allAssignments")) {
            return null;
        }
        return AssignmentTabs.panel(tester.getLastRenderedPage(), "AbstractAssignmentTypePanel");
    }

    /** InlineMenuButtonColumn's AjaxCompositedIconButton of the row: its basic icon (getIconCompositedBuilder). */
    @Override
    public boolean isUnassignButton(Component component) {
        if (!(component instanceof com.evolveum.midpoint.gui.impl.component.AjaxCompositedIconButton)) {
            return false;
        }
        Object icon = AssignmentTabs.field(component, "icon");
        return icon instanceof com.evolveum.midpoint.gui.impl.component.icon.CompositedIcon
                && AssignmentTabs.hasClasses(
                        ((com.evolveum.midpoint.gui.impl.component.icon.CompositedIcon) icon).getBasicIcon(),
                        com.evolveum.midpoint.gui.api.GuiStyleConstants.CLASS_DELETE_MENU_ITEM);
    }

    /** AbstractAssignmentTypePanel.createToolbarButtonsList: the AjaxIconButton of the assignment icon. */
    @Override
    public Component newAssignmentButton(Component panel) {
        Component[] found = new Component[1];
        ((MarkupContainer) panel).visitChildren(com.evolveum.midpoint.web.component.AjaxIconButton.class,
                (button, visit) -> {
                    if (com.evolveum.midpoint.gui.api.GuiStyleConstants.EVO_ASSIGNMENT_ICON
                            .equals(button.getDefaultModelObject())) {
                        found[0] = button;
                        visit.stop();
                    }
                });
        return found[0];
    }

    /**
     * The items of the page's details navigation (DetailsNavigationPanel): its list holds every panel of the page's
     * merged objectDetails, the hidden ones too (each item hidden by its VisibleBehaviour: isMenuItemVisible, the
     * panel's visibility and applicableForOperation); the label as the item renders it. A sub-navigation renders only
     * when expanded (4.10 and later) and never under a hidden item: the sub-tabs are the panel's sub-panels, decided
     * by the same rule.
     */
    @Override
    public List<GuiForms.TabInfo> tabs(Page page) {
        List<DetailsNavigationPanel<?>> found = new ArrayList<>();
        page.visitChildren(DetailsNavigationPanel.class, (panel, visit) -> {
            found.add((DetailsNavigationPanel<?>) panel);
            visit.stop();
        });
        if (found.isEmpty() || !(page instanceof AbstractPageObjectDetails)) {
            throw new IllegalStateException(page.getClass().getSimpleName() + " has no details navigation");
        }
        ItemStatus status = ((AbstractPageObjectDetails<?, ?>) page).getObjectDetailsModels().getObjectStatus();
        List<GuiForms.TabInfo> out = new ArrayList<>();
        for (Component child : (MarkupContainer) found.get(0).get("menu")) {
            ListItem<?> item = (ListItem<?>) child;
            ContainerPanelConfigurationType config = config(item.getModelObject());
            Component label = item.get("navLinkStyle:navItemLink:navItem");
            String text = label != null ? String.valueOf(label.getDefaultModelObject()) : translated(config);
            boolean shown = item.determineVisibility();
            out.add(new GuiForms.TabInfo(text, key(config, text), config.getIdentifier(), shown,
                    hiddenBecause(config, status), subtabs(config, status, shown)));
        }
        return out;
    }

    /** The item's panel: its model object (4.10 and later a ContainerPanelDto of it). */
    private static ContainerPanelConfigurationType config(Object item) {
        if (item instanceof ContainerPanelConfigurationType) {
            return (ContainerPanelConfigurationType) item;
        }
        try {
            return (ContainerPanelConfigurationType) item.getClass().getMethod("getContainerPanelConfig").invoke(item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No panel in the navigation's item " + item + ": " + e, e);
        }
    }

    private static List<GuiForms.TabInfo> subtabs(ContainerPanelConfigurationType config, ItemStatus status,
            boolean parentShown) {
        List<GuiForms.TabInfo> out = new ArrayList<>();
        for (ContainerPanelConfigurationType sub : config.getPanel()) {
            String text = translated(sub);
            boolean shown = parentShown && visible(sub, status);
            String because = !visible(sub, status) ? hiddenBecause(sub, status) : "its tab is hidden";
            out.add(new GuiForms.TabInfo(text, key(sub, text), sub.getIdentifier(), shown, because,
                    subtabs(sub, status, shown)));
        }
        return out;
    }

    /** DetailsNavigationPanel.isMenuItemVisible. */
    private static boolean visible(ContainerPanelConfigurationType config, ItemStatus status) {
        if (!WebComponentUtil.getElementVisibility(config.getVisibility())) {
            return false;
        }
        OperationTypeType operation = config.getApplicableForOperation();
        if (status == ItemStatus.NOT_CHANGED) {
            return operation == null || operation == OperationTypeType.MODIFY;
        }
        return status == ItemStatus.ADDED && (operation == null || operation == OperationTypeType.ADD);
    }

    /** Why isMenuItemVisible hides the panel: its visibility, or the operation it is applicable for. */
    private static String hiddenBecause(ContainerPanelConfigurationType config, ItemStatus status) {
        UserInterfaceElementVisibilityType visibility = config.getVisibility();
        if (!WebComponentUtil.getElementVisibility(visibility)) {
            return "visibility " + visibility.value();
        }
        OperationTypeType operation = config.getApplicableForOperation();
        if (operation != null && status == ItemStatus.ADDED && operation != OperationTypeType.ADD) {
            return "applicableForOperation " + operation.value() + ": not on a new object";
        }
        if (operation != null && status == ItemStatus.NOT_CHANGED && operation != OperationTypeType.MODIFY) {
            return "applicableForOperation " + operation.value() + ": not on an existing object";
        }
        return "hidden by the page";
    }

    /** The label as the navigation renders it: "N/A" without one. */
    private static String translated(ContainerPanelConfigurationType config) {
        return config.getDisplay() == null || config.getDisplay().getLabel() == null ? "N/A"
                : GuiDisplayTypeUtil.getTranslatedLabel(config.getDisplay());
    }

    /** The label's key: its translation key, or its orig when that translates (a key in orig); null: a literal. */
    private static String key(ContainerPanelConfigurationType config, String rendered) {
        PolyStringType label = config.getDisplay() != null ? config.getDisplay().getLabel() : null;
        if (label == null) {
            return null;
        }
        if (label.getTranslation() != null && label.getTranslation().getKey() != null) {
            return label.getTranslation().getKey();
        }
        return label.getOrig() != null && !label.getOrig().equals(rendered) ? label.getOrig() : null;
    }

    // ---- the side menu ------------------------------------------------------------------------------------------

    /**
     * LeftMenuPanel.getItems(): the SideBarMenuItems the GUI built for the user (createMenuItems: a section's main
     * items added when their page is authorized by the user's roles, SecurityUtils.isMenuAuthorized, the experimental
     * flag and an empty heading dropped; a sub-item when its page is, LeftMenuAuthzUtil's actions for a type's list and
     * views; an empty section dropped). The lists of items are private: read by reflection.
     */
    @Override
    public List<GuiForms.MenuInfo> sideMenu(Page page) {
        com.evolveum.midpoint.gui.impl.component.menu.LeftMenuPanel panel =
                (com.evolveum.midpoint.gui.impl.component.menu.LeftMenuPanel) page.get("sidebarMenu");
        if (panel == null) {
            throw new IllegalStateException(page.getClass().getSimpleName() + " has no side menu");
        }
        List<GuiForms.MenuInfo> out = new ArrayList<>();
        for (com.evolveum.midpoint.web.component.menu.SideBarMenuItem section : panel.getItems()) {
            List<GuiForms.MenuInfo> mains = new ArrayList<>();
            for (Object main : menuItems(section)) {
                com.evolveum.midpoint.web.component.menu.MainMenuItem item =
                        (com.evolveum.midpoint.web.component.menu.MainMenuItem) main;
                List<GuiForms.MenuInfo> subs = new ArrayList<>();
                for (Object sub : menuItems(item)) {
                    subs.add(menuEntry(page, (com.evolveum.midpoint.web.component.menu.BaseMenuItem) sub, null,
                            List.of()));
                }
                String targetUrl = item instanceof com.evolveum.midpoint.web.component.menu.AdditionalMenuItem
                        ? ((com.evolveum.midpoint.web.component.menu.AdditionalMenuItem) item).getTargetUrl() : null;
                mains.add(menuEntry(page, item, targetUrl, subs));
            }
            String key = section.getName();
            out.add(new GuiForms.MenuInfo(key, menuLabel(page, key), null, null, List.of(), null,
                    menuVisible(section), "the subscription state hides it", mains));
        }
        return out;
    }

    /** A main item or a sub-item: its label, page, URL, the page's actions, parameters. */
    private GuiForms.MenuInfo menuEntry(Page page, com.evolveum.midpoint.web.component.menu.BaseMenuItem item,
            String targetUrl, List<GuiForms.MenuInfo> subs) {
        Class<?> pageClass = item.getPageClass();
        String key = item.getNameModel();
        String label = menuLabel(page, key);
        List<String> actions = new ArrayList<>();
        if (pageClass != null) {
            PageDescriptor descriptor = pageClass.getAnnotation(PageDescriptor.class);
            if (descriptor != null) {
                for (com.evolveum.midpoint.authentication.api.authorization.AuthorizationAction action
                        : descriptor.action()) {
                    actions.add(action.actionUri());
                }
            }
        }
        String url = targetUrl != null ? targetUrl : pageClass != null ? mountUrl(pageClass) : null;
        return new GuiForms.MenuInfo(label.equals(key) ? null : key, label,
                pageClass != null ? pageClass.getName() : null, url, actions, menuParams(item.getParams()),
                menuVisible(item), "the subscription state hides it", subs);
    }

    /** The key translated by the page, as SideBarMenuPanel renders it; a literal label (a view's) stays. */
    private static String menuLabel(Page page, String key) {
        if (key == null) {
            return "";
        }
        String label = page.getString(key, null, "");
        return label.isEmpty() ? key : label;
    }

    private static String menuParams(org.apache.wicket.request.mapper.parameter.PageParameters params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String name : params.getNamedKeys()) {
            out.add(name + "=" + params.get(name).toString());
        }
        return String.join("&", out);
    }

    /** The private {@code items} of a SideBarMenuItem or a MainMenuItem (null: none added). */
    private static List<?> menuItems(Object holder) {
        try {
            java.lang.reflect.Field field = holder.getClass().getDeclaredField("items");
            field.setAccessible(true);
            Object value = field.get(holder);
            return value == null ? List.of() : (List<?>) value;
        } catch (NoSuchFieldException e) {
            // a subclass (AdditionalMenuItem, an anonymous MainMenuItem): the field is MainMenuItem's
            Class<?> type = holder.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("items");
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    return value == null ? List.of() : (List<?>) value;
                } catch (NoSuchFieldException | IllegalAccessException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new IllegalStateException("No items in " + holder.getClass().getName());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SideBarMenuPanel and MainMenuPanel show an entry when its isVisible says so (4.8+: the subscription state). */
    private static boolean menuVisible(Object entry) {
        if (entry instanceof com.evolveum.midpoint.web.component.menu.SideBarMenuItem) {
            return ((com.evolveum.midpoint.web.component.menu.SideBarMenuItem) entry).isVisible();
        }
        return ((com.evolveum.midpoint.web.component.menu.BaseMenuItem) entry).isVisible();
    }
}
