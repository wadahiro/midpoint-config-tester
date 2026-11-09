package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.model.api.interaction.DashboardService;
import com.evolveum.midpoint.model.api.interaction.DashboardWidget;
import com.evolveum.midpoint.xml.ns._public.common.common_3.DashboardType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.DashboardWidgetType;
import com.github.wadahiro.midpoint.tester.Dashboards;
import com.github.wadahiro.midpoint.tester.Run;

/** {@link Dashboards} on 4.4: {@code DashboardService.createWidgetData}, with the widget's display source. */
final class DashboardsImpl implements Dashboards {

    private final MidPointImpl mp;

    DashboardsImpl(MidPointImpl mp) {
        this.mp = mp;
    }

    @Override
    public Widget widget(String dashboard, String identifier) {
        DashboardType bean = mp.existing(DashboardType.class, dashboard);
        for (DashboardWidgetType widget : bean.getWidget()) {
            if (identifier.equals(widget.getIdentifier())) {
                return evaluate(bean, widget);
            }
        }
        throw new AssertionError("No widget " + identifier + " in dashboard " + dashboard);
    }

    @Override
    public List<Widget> widgets(String dashboard) {
        DashboardType bean = mp.existing(DashboardType.class, dashboard);
        List<Widget> out = new ArrayList<>();
        for (DashboardWidgetType widget : bean.getWidget()) {
            out.add(evaluate(bean, widget));
        }
        return out;
    }

    private Widget evaluate(DashboardType dashboard, DashboardWidgetType widget) {
        DashboardWidget[] data = new DashboardWidget[1];
        Run run = mp.runAs(null, "widget " + widget.getIdentifier() + " of " + dashboard.getName().getOrig(),
                (task, result) -> {
                    // midPoint does not say which widget an expression is in: identical ones of other widgets
                    data[0] = Recorder.inScope(dashboard.getOid(), "widget", "identifier", widget.getIdentifier(),
                            () -> mp.bean(DashboardService.class).createWidgetData(widget, true, task, result));
                    return MidPointImpl.Outcome.NONE;
                });
        return new Widget(widget.getIdentifier(), run, data[0].getNumberMessage(), data[0].getDisplay());
    }
}
