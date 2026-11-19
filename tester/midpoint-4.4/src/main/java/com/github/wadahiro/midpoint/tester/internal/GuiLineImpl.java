package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import javax.xml.namespace.QName;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;
import org.apache.wicket.markup.html.list.ListItem;

import com.evolveum.midpoint.gui.api.prism.ItemStatus;
import com.evolveum.midpoint.gui.api.prism.wrapper.ItemWrapper;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.impl.component.menu.DetailsNavigationPanel;
import com.evolveum.midpoint.gui.impl.page.admin.AbstractPageObjectDetails;
import com.evolveum.midpoint.gui.impl.page.admin.component.ProgressPanel;
import com.evolveum.midpoint.gui.impl.prism.panel.ItemPanel;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.component.TabbedPanel;
import com.evolveum.midpoint.web.component.objectdetails.AbstractObjectMainPanel;
import com.evolveum.midpoint.web.security.MidPointApplication;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ContainerPanelConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationTypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserInterfaceElementVisibilityType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * The GUI of 4.4 for {@link GuiFormsImpl}: its details pages (WebComponentUtil: the new design's or the old one's, as
 * the user's GUI profile says), item panels, progress (the new design's; the old pages poll), the tabs (the new
 * design's details navigation, the old one's tabbed panel: {@link LegacyTabs}).
 */
final class GuiLineImpl implements GuiLine {

    @Override
    public Class<? extends Page> detailsPage(Class<? extends ObjectType> type) {
        return WebComponentUtil.getObjectDetailsPage(type);
    }

    @Override
    public Class<? extends Page> newObjectPageClass(Class<? extends ObjectType> type) {
        return WebComponentUtil.getNewlyCreatedObjectPage(type);
    }

    @Override
    public boolean storedIntoParentOrgRef(QName relation) {
        // WebComponentUtil.initNewObjectWithReference: as the system configuration's relation definitions say
        return MidPointApplication.get().getRelationRegistry().isStoredIntoParentOrgRef(relation);
    }

    @Override
    public Page newObjectPage(Class<? extends Page> page, PrismObject<?> object) throws Exception {
        // as WebComponentUtil.dispatchToObjectDetailsPage(object, true, component)
        return WebComponentUtil.isNewDesignEnabled()
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
            return wrapper instanceof ItemWrapper ? wrapper : null;
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
    public Future<?> saving(Page page) {
        Future<?>[] found = new Future<?>[1];
        page.visitChildren(ProgressPanel.class, (panel, visit) -> {
            var process = ((ProgressPanel) panel).getReporterModel().getObject();
            if (process != null && process.getFuture() != null) {
                found[0] = process.getFuture();
                visit.stop();
            }
        });
        return found[0];
    }

    @Override
    public List<GuiForms.TabInfo> tabs(Page page) {
        // as newObjectPage: the user's GUI profile decides the design of the page
        return WebComponentUtil.isNewDesignEnabled() ? navigation(page) : legacyTabs(page);
    }

    /** The old design: AbstractObjectMainPanel's mainForm:tabPanel, as on 4.0. */
    private static List<GuiForms.TabInfo> legacyTabs(Page page) {
        List<AbstractObjectMainPanel<?>> panels = new ArrayList<>();
        page.visitChildren(AbstractObjectMainPanel.class, (panel, visit) -> {
            panels.add((AbstractObjectMainPanel<?>) panel);
            visit.stop();
        });
        if (panels.isEmpty()) {
            throw new IllegalStateException(page.getClass().getSimpleName() + " has no object main panel");
        }
        AbstractObjectMainPanel<?> main = panels.get(0);
        TabbedPanel<?> tabbed = (TabbedPanel<?>) main.get("mainForm:" + AbstractObjectMainPanel.ID_TAB_PANEL);
        return LegacyTabs.read(tabbed.getTabs().getObject(), main.getObject().getDefinition().getTypeName()
                .getLocalPart(), main.getObjectWrapper().getStatus() == ItemStatus.ADDED);
    }

    /**
     * The new design: the items of the page's details navigation (DetailsNavigationPanel), whose list holds every
     * panel of the page's merged objectDetails, the hidden ones too (each item hidden by its VisibleBehaviour:
     * isMenuItemVisible, the panel's visibility and applicableForOperation); the label as the item renders it. A
     * sub-navigation never renders under a hidden item: the sub-tabs are the panel's sub-panels, decided by the same
     * rule.
     */
    private static List<GuiForms.TabInfo> navigation(Page page) {
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

    private static ContainerPanelConfigurationType config(Object item) {
        return (ContainerPanelConfigurationType) item;
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
                : WebComponentUtil.getTranslatedPolyString(config.getDisplay().getLabel());
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
}
