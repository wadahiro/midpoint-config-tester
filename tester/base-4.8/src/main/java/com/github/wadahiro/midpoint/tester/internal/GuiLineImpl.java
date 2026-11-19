package com.github.wadahiro.midpoint.tester.internal;

import java.util.concurrent.Future;

import org.apache.wicket.Component;
import org.apache.wicket.Page;

import com.evolveum.midpoint.authentication.api.authorization.PageDescriptor;
import com.evolveum.midpoint.gui.api.prism.wrapper.ItemWrapper;
import com.evolveum.midpoint.gui.impl.page.admin.component.ProgressPanel;
import com.evolveum.midpoint.gui.impl.prism.panel.ItemPanel;
import com.evolveum.midpoint.gui.impl.util.DetailsPageUtil;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/** The GUI of 4.8 and later for {@link GuiFormsImpl}: its details pages (DetailsPageUtil), item panels, progress. */
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
}
