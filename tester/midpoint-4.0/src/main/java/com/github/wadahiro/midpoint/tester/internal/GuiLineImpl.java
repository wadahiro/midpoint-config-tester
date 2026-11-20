package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import javax.xml.namespace.QName;

import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.Page;

import com.evolveum.midpoint.gui.api.prism.ItemStatus;
import com.evolveum.midpoint.gui.api.prism.ItemWrapper;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.impl.prism.ItemPanel;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.component.TabbedPanel;
import com.evolveum.midpoint.web.component.objectdetails.AbstractObjectMainPanel;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * The GUI of 4.0 for {@link GuiFormsImpl}: its details pages (WebComponentUtil; a new object's page made with
 * isNewObject; an org a new member is created in is its parentOrgRef with any relation), item panels; its progress
 * panel polls; the tabs of the main panel's tabbed panel ({@link LegacyTabs}).
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
        // WebComponentUtil.initNewObjectWithReference: every org reference, whatever its relation
        return true;
    }

    @Override
    public Page newObjectPage(Class<? extends Page> page, PrismObject<?> object) throws Exception {
        // as WebComponentUtil.dispatchToObjectDetailsPage(object, true, component)
        return page.getConstructor(PrismObject.class, boolean.class).newInstance(object, true);
    }

    @Override
    public String mountUrl(Class<?> page) {
        PageDescriptor descriptor = page.getAnnotation(PageDescriptor.class);
        if (descriptor == null) {
            return null;
        }
        if (descriptor.urls().length > 0) {
            return descriptor.urls()[0].mountUrl();
        }
        return descriptor.url().length > 0 ? descriptor.url()[0] : null;
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
        return ((ItemWrapper<?, ?, ?, ?>) wrapper).getPath();
    }

    @Override
    public boolean validated(Object wrapper) {
        return ((ItemWrapper<?, ?, ?, ?>) wrapper).getFormComponentValidator() != null;
    }

    @Override
    public boolean multiValue(Object wrapper) {
        return ((ItemWrapper<?, ?, ?, ?>) wrapper).isMultiValue();
    }

    @Override
    public List<MarkupContainer> valueRows(Component itemPanel) {
        List<MarkupContainer> out = new ArrayList<>();
        Component values = ((MarkupContainer) itemPanel).get("values");
        if (values instanceof MarkupContainer) {
            for (Component row : (MarkupContainer) values) {
                out.add((MarkupContainer) row);
            }
        }
        return out;
    }

    @Override
    public Component addValueButton(Component itemPanel) {
        // each row has one (ItemPanel.createButtons), shown when the item is multi-valued: the first shown row's
        for (MarkupContainer row : valueRows(itemPanel)) {
            Component add = row.get("buttonContainer:addButton");
            if (add != null && add.isVisibleInHierarchy()) {
                return add;
            }
        }
        return null;
    }

    @Override
    public Component removeValueButton(MarkupContainer row) {
        return row.get("buttonContainer:removeButton");
    }

    @Override
    public Future<?> saving(Page page) {
        return null;
    }

    @Override
    public List<GuiForms.TabInfo> tabs(Page page) {
        // AbstractObjectMainPanel: mainForm:tabPanel, every tab of the type, the hidden ones too
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
     * The tabbed panel's Assignments / Inducements tab clicked ({@code focusTabAssignments} /
     * {@code focusTabInducements}): its SwitchAssignmentTypePanel opens on All, an AssignmentPanel.
     */
    @Override
    public Component openAssignments(org.apache.wicket.util.tester.WicketTester tester, boolean inducements) {
        return LegacyTabs.open(tester, this, inducements);
    }

    /** InlineMenuButtonColumn's AjaxIconButton of the row: its model is its icon (getButtonIconCssClass). */
    @Override
    public boolean isUnassignButton(Component component) {
        return component instanceof com.evolveum.midpoint.web.component.AjaxIconButton
                && AssignmentTabs.hasClasses(component.getDefaultModelObjectAsString(),
                        com.evolveum.midpoint.gui.api.GuiStyleConstants.CLASS_DELETE_MENU_ITEM);
    }

    /** AssignmentPanel.initCustomButtonToolbar: the {@code newItemButton}. */
    @Override
    public Component newAssignmentButton(Component panel) {
        return GuiLine.descendant((MarkupContainer) panel, "newItemButton");
    }
}
