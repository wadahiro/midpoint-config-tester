package com.github.wadahiro.midpoint.tester;

import java.util.List;

import com.evolveum.midpoint.xml.ns._public.common.common_3.DisplayType;

/**
 * Dashboards ({@link MidPoint#dashboards()}): widgets evaluated by midPoint's {@code DashboardService}, as the GUI's
 * dashboard page does; the run has the data field expressions and the variations' conditions.
 *
 * <pre>{@code
 * Dashboards.Widget w = mp.dashboards().widget("admin-dashboard", "employees");
 * assertThat(w.number()).isEqualTo("2 users");
 * assertThat(w.color()).isEqualTo("red");
 * Selection conditions = w.run().select().element("condition");
 * }</pre>
 */
public interface Dashboards {

    /** The widget with this identifier of the dashboard (by name or OID). */
    Widget widget(String dashboard, String identifier);

    /** Every widget of the dashboard, in order (each with its own run). */
    List<Widget> widgets(String dashboard);

    final class Widget {
        private final String identifier;
        private final Run run;
        private final String number;
        private final DisplayType display;

        public Widget(String identifier, Run run, String number, DisplayType display) {
            this.identifier = identifier;
            this.run = run;
            this.number = number;
            this.display = display;
        }

        public String identifier() {
            return identifier;
        }

        public Run run() {
            return run;
        }

        /** The text the widget shows (value and unit data fields). */
        public String number() {
            return number;
        }

        /** The display after the variations that apply. */
        public DisplayType display() {
            return display;
        }

        public String color() {
            return display != null ? display.getColor() : null;
        }

        @Override
        public String toString() {
            return identifier + ": " + number + (color() != null ? " (" + color() + ")" : "");
        }
    }
}
