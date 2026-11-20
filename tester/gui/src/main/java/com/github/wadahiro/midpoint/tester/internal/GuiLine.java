package com.github.wadahiro.midpoint.tester.internal;

import java.util.List;
import java.util.concurrent.Future;
import javax.xml.namespace.QName;

import org.apache.wicket.Component;
import org.apache.wicket.Page;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * What {@link GuiFormsImpl} needs of the line's GUI, whose classes differ by line (GuiLineImpl of the line module):
 * the details page of a type and how the GUI opens it for a new object (an org's parentOrgRef of a new member), a
 * page's URL, the item panels' wrappers and value rows, the background execution of a save, the page's tabs.
 */
interface GuiLine {

    /** The page the GUI shows an object of the type in (its details page), or null. */
    Class<? extends Page> detailsPage(Class<? extends ObjectType> type);

    /** The page the GUI shows a new object of the type in (dispatching to a new object's details page), or null. */
    Class<? extends Page> newObjectPageClass(Class<? extends ObjectType> type);

    /**
     * Whether the GUI puts an org a new member is created in with this relation into its parentOrgRef too (the
     * members panel's "+": initNewObjectWithReference).
     */
    boolean storedIntoParentOrgRef(QName relation);

    /** The page for a new object, as the GUI makes it when it dispatches to the details page of a new object. */
    Page newObjectPage(Class<? extends Page> page, PrismObject<?> object) throws Exception;

    /** The URL the page is mounted at (its PageDescriptor), or null. */
    String mountUrl(Class<?> page);

    /** The item wrapper of an item panel, or null when the component is not one. */
    Object wrapper(Component component);

    /** The wrapper's item path. */
    ItemPath path(Object wrapper);

    /** Whether the wrapper's item has a validation (an object template's item/validation/server). */
    boolean validated(Object wrapper);

    /** Whether the wrapper's item is multi-valued: its panel has a value row per value and add / remove buttons. */
    boolean multiValue(Object wrapper);

    /**
     * The value rows of an item panel (the items of its {@code values} list), the hidden ones too (a removed value's
     * row is hidden: 4.0 the row, 4.4 and later its value panel).
     */
    List<org.apache.wicket.MarkupContainer> valueRows(Component itemPanel);

    /**
     * The item panel's button that adds an empty value row, or null: 4.4 and later the header's {@code add}
     * (ItemHeaderPanel), 4.0 the {@code addButton} of each row (ItemPanel.createButtons).
     */
    Component addValueButton(Component itemPanel);

    /**
     * The row's button that removes its value, or null: 4.4 and later the value panel's {@code removeButton}
     * (PrismValuePanel), 4.0 the row's {@code buttonContainer:removeButton}.
     */
    Component removeValueButton(org.apache.wicket.MarkupContainer row);

    /** The background execution of the changes a save started on the page, or null (none, or executed already). */
    Future<?> saving(Page page);

    /**
     * The tabs of the rendered details page, shown or hidden, in the GUI's order: 4.4 and later the items of its
     * details navigation, 4.0 (and 4.4's old design) the tabs of its tabbed panel.
     */
    List<GuiForms.TabInfo> tabs(Page page);

    /** The first descendant of the container with the id, or null. */
    static Component descendant(org.apache.wicket.MarkupContainer container, String id) {
        Component[] found = new Component[1];
        container.visitChildren((component, visit) -> {
            if (id.equals(component.getId())) {
                found[0] = component;
                visit.stop();
            }
        });
        return found[0];
    }
}
