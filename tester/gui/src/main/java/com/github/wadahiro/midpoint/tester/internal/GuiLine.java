package com.github.wadahiro.midpoint.tester.internal;

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
 * page's URL, the item panels' wrappers, the background execution of a save.
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

    /** The background execution of the changes a save started on the page, or null (none, or executed already). */
    Future<?> saving(Page page);
}
