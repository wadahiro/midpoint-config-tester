package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * A tab of an object's details page as the GUI has it for the user ({@link ObjectForm#tabs}), shown or hidden: on 4.4
 * and later an item of the page's navigation (a panel of the type's {@code objectDetails}), on 4.0 a tab of the
 * page. A tab is named by its {@link #label} as shown, its label {@link #key} or its {@link #identifier}:
 * {@code Governance}, {@code pageRole.governance} (the same on every line for the built-in tabs), {@code orgGovernance}
 * (4.4 and later) or {@code focusTabGovernance} (4.0). Immutable.
 */
public final class Tab {

    private final String label;
    private final String key;
    private final String identifier;
    private final boolean shown;
    private final String hiddenBecause;
    private final List<Tab> subtabs;

    Tab(GuiForms.TabInfo info) {
        this.label = info.label;
        this.key = info.key;
        this.identifier = info.identifier;
        this.shown = info.shown;
        this.hiddenBecause = info.hiddenBecause;
        List<Tab> subs = new ArrayList<>();
        for (GuiForms.TabInfo sub : info.subtabs) {
            subs.add(new Tab(sub));
        }
        this.subtabs = Collections.unmodifiableList(subs);
    }

    /** The label as the GUI renders it, in the JVM's locale (a hidden tab's translated the same way). */
    public String label() {
        return label;
    }

    /**
     * The label's localization key ({@code pageRole.governance}), or null: a literal label (a 4.0 {@code objectForm}
     * whose title is no key, a panel whose label is a text).
     */
    public String key() {
        return key;
    }

    /**
     * The line's identifier of the tab: on 4.4 and later the panel's identifier ({@code orgGovernance}, as an
     * {@code objectDetails} panel names it), on 4.0 the local name of the tab's URI ({@code focusTabGovernance}, as an
     * {@code objectForm}'s {@code panelUri} names it); null for a 4.0 tab of a custom {@code objectForm}.
     */
    public String identifier() {
        return identifier;
    }

    /** Whether the GUI shows the tab to the user. */
    public boolean shown() {
        return shown;
    }

    /**
     * Why the GUI does not show the tab, or null: shown. 4.4 and later: {@code visibility hidden} (the panel's
     * {@code visibility}, as the merged {@code objectDetails} give it), {@code applicableForOperation modify: not on a
     * new object}; 4.0: {@code no objectForm of OrgType names focusTabGovernance} (the user's {@code objectForms}),
     * {@code not on a new object} (the tab's own rule).
     */
    public String hiddenBecause() {
        return hiddenBecause;
    }

    /** The tab's sub-tabs (4.4 and later: the panel's sub-panels, e.g. the assignments' Role, Org...); 4.0: none. */
    public List<Tab> subtabs() {
        return subtabs;
    }

    /** Whether the tab is named so: its label, its label key or its identifier. */
    boolean is(String name) {
        return name.equals(label) || name.equals(key) || name.equals(identifier);
    }

    /** {@code Governance}, a hidden tab with why: {@code Governance (visibility hidden)}. */
    String describe() {
        return shown ? label : label + " (" + hiddenBecause + ")";
    }

    @Override
    public String toString() {
        return label + (identifier != null ? " [" + identifier + "]" : "") + (shown ? "" : " (" + hiddenBecause + ")");
    }
}
