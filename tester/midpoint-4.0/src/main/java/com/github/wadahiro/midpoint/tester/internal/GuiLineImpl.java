package com.github.wadahiro.midpoint.tester.internal;

import java.util.concurrent.Future;

import org.apache.wicket.Component;
import org.apache.wicket.Page;

import com.evolveum.midpoint.gui.api.prism.ItemWrapper;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.impl.prism.ItemPanel;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * The GUI of 4.0 for {@link GuiFormsImpl}: its details pages (WebComponentUtil; a new object's page made with
 * isNewObject), item panels; its progress panel polls.
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
    public Future<?> saving(Page page) {
        return null;
    }
}
