package com.github.wadahiro.midpoint.tester.internal;

import java.util.concurrent.Future;
import javax.xml.namespace.QName;

import org.apache.wicket.Component;
import org.apache.wicket.Page;

import com.evolveum.midpoint.gui.api.prism.wrapper.ItemWrapper;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.impl.page.admin.component.ProgressPanel;
import com.evolveum.midpoint.gui.impl.prism.panel.ItemPanel;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.security.MidPointApplication;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * The GUI of 4.4 for {@link GuiFormsImpl}: its details pages (WebComponentUtil: the new design's or the old one's, as
 * the user's GUI profile says), item panels, progress (the new design's; the old pages poll).
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
}
