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
     * The page of the logged-in user's own profile (the side menu's Profile): 4.0 {@code PageSelfProfile}
     * ({@code /self/profile}); 4.4 the design's user self-profile page {@code PageSelfProfile} sends to
     * ({@code WebComponentUtil.resolveSelfPage}: {@code /self/profile/user}, the new design's
     * {@code /self/profile/userNew}); 4.8+ {@code PageUserSelfProfile} ({@code /self/profile/user}). The page takes no
     * parameter: it loads the logged-in focus.
     */
    Class<? extends Page> selfProfilePage();

    /**
     * The page of the logged-in user's own credentials (the side menu's Credentials, {@code /self/credentials} on every
     * line): 4.0 and 4.4 {@code web.page.self.PageSelfCredentials} (the change password panel of a
     * {@code MyPasswordsDto}, the page's Save), 4.8+ {@code gui.impl.page.self.credentials.PageSelfCredentials} (its
     * {@code PropagatePasswordPanel} on the logged-in focus, the panel's Change password). The page takes no
     * parameter.
     */
    Class<? extends Page> selfCredentialsPage();

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

    /**
     * Opens the Assignments tab (inducements: the Inducements tab) of the rendered details page as the user clicks it,
     * on its "All" sub-panel (4.4 and later: {@code allAssignments} / {@code allInducements}, else the first sub-panel
     * shown; 4.0 and 4.4's old design: the tab, whose type switch opens on All), and returns the panel of its rows; null
     * when the page shows the user no such tab.
     */
    Component openAssignments(org.apache.wicket.util.tester.WicketTester tester, boolean inducements);

    /**
     * Whether the component is a row's Unassign button: a button of the row's menu column whose icon is the GUI's
     * delete icon ({@code GuiStyleConstants.CLASS_DELETE_MENU_ITEM}; 4.0 an AjaxIconButton's icon, 4.4 and later an
     * AjaxCompositedIconButton's basic icon).
     */
    boolean isUnassignButton(Component component);

    /**
     * The "+" (new assignment) of the panel of the rows, or null: 4.0 and 4.4's old design the {@code newItemButton},
     * 4.4 and later the toolbar's button with the assignment icon.
     */
    Component newAssignmentButton(Component panel);

    /**
     * The side menu of the rendered page as the GUI built it for the logged-in user ({@link GuiForms.MenuInfo}): 4.4
     * and later the items of its LeftMenuPanel, filtered as the GUI adds them (a page's authorization by the user's
     * roles, the experimental flag, empty headings and sections dropped); 4.0 the items PageBase builds, each with the
     * visibility SideBarMenuPanel and MainMenuPanel give it at rendering (the hidden ones too, with why).
     */
    List<GuiForms.MenuInfo> sideMenu(Page page);

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
