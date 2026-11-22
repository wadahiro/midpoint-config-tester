package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * The GUI's object forms, run by midPoint's own GUI ({@code configtester.NewObjectForm}, {@code EditObjectForm}): the
 * line module's {@code GuiFormsImpl} opens the object's details page in WicketTester, types into its fields and
 * saves, so the validations, the items shown and the saving are the GUI's. It needs midPoint's GUI module on the test
 * class path (a test dependency of the deployment), and Wicket's tester on 4.8 and later; without them a form fails
 * with what to add. Core has no GUI or Wicket type: the implementation is loaded by name.
 */
public interface GuiForms {

    /**
     * The page: a new object (oid null; its archetype and the role, org or service it is a member of) or an existing
     * one, the user, the items typed.
     */
    final class Spec {
        public final Class<? extends ObjectType> type;
        /** The object edited, or null for a new one. */
        public final String oid;
        public final String archetypeOid;
        /** The role, org or service a new object is a member of, or null. */
        public final String memberOid;
        public final Class<? extends ObjectType> memberType;
        /**
         * The relation of the membership, set on its targetRef; null: none set, as "create child" of the organization
         * tree makes it (an org's parentOrgRef then always).
         */
        public final QName relation;
        /** The user filling it in, or null: the administrator. */
        public final String user;
        /** The items typed into, by their paths of local names, in this order. */
        public final Map<String, Object> items;
        /** The rows of the Assignments and Inducements tabs whose Unassign is pressed before Save, in this order. */
        public final List<Removal> removals;
        /**
         * Whether the page is the user's own profile page (the side menu's Profile, which loads the logged-in user
         * and takes no OID) rather than the details page of {@link #oid} (then the user's OID).
         */
        public final boolean selfProfile;

        public Spec(Class<? extends ObjectType> type, String oid, String archetypeOid, String memberOid,
                Class<? extends ObjectType> memberType, QName relation, String user, Map<String, Object> items) {
            this(type, oid, archetypeOid, memberOid, memberType, relation, user, items, List.of());
        }

        public Spec(Class<? extends ObjectType> type, String oid, String archetypeOid, String memberOid,
                Class<? extends ObjectType> memberType, QName relation, String user, Map<String, Object> items,
                List<Removal> removals) {
            this(type, oid, archetypeOid, memberOid, memberType, relation, user, items, removals, false);
        }

        public Spec(Class<? extends ObjectType> type, String oid, String archetypeOid, String memberOid,
                Class<? extends ObjectType> memberType, QName relation, String user, Map<String, Object> items,
                List<Removal> removals, boolean selfProfile) {
            this.type = type;
            this.oid = oid;
            this.archetypeOid = archetypeOid;
            this.memberOid = memberOid;
            this.memberType = memberType;
            this.relation = relation;
            this.user = user;
            this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
            this.removals = Collections.unmodifiableList(new ArrayList<>(removals));
            this.selfProfile = selfProfile;
        }
    }

    /**
     * A row of the Assignments tab (or the Inducements tab) whose Unassign the user presses: the row of the target with
     * the relation (null: default, which the GUI shows alike for a relation stored as default or as none).
     */
    final class Removal {
        public final boolean inducement;
        public final String targetOid;
        /** The target as a failure names it ({@code Role ar-meta-1}). */
        public final String target;
        public final QName relation;

        public Removal(boolean inducement, String targetOid, String target, QName relation) {
            this.inducement = inducement;
            this.targetOid = targetOid;
            this.target = target;
            this.relation = relation;
        }
    }

    /** A row of the Assignments or Inducements tab as the GUI renders it for the user. */
    final class RowInfo {
        /** The target's OID, or null: a row without a target (a construction, a policy rule...). */
        public final String targetOid;
        /** The target's type as the reference has it, or null. */
        public final QName targetType;
        /** The relation as stored (null: none). */
        public final QName relation;
        /** The assignment as the row holds it (as the user read the object). */
        public final Object assignment;
        /** Whether the row shows an Unassign button, and whether it is enabled. */
        public final boolean unassignShown;
        public final boolean unassignEnabled;

        public RowInfo(String targetOid, QName targetType, QName relation, Object assignment, boolean unassignShown,
                boolean unassignEnabled) {
            this.targetOid = targetOid;
            this.targetType = targetType;
            this.relation = relation;
            this.assignment = assignment;
            this.unassignShown = unassignShown;
            this.unassignEnabled = unassignEnabled;
        }
    }

    /** The Assignments or Inducements tab as the GUI renders it for the user. */
    final class Rows {
        /** Whether the page shows the tab; when not, {@link #tabs} says why and the rest is empty. */
        public final boolean tabShown;
        public final List<TabInfo> tabs;
        public final List<RowInfo> rows;
        /** Whether the tab shows its "+" (new assignment), and whether it is enabled. */
        public final boolean assignShown;
        public final boolean assignEnabled;

        public Rows(boolean tabShown, List<TabInfo> tabs, List<RowInfo> rows, boolean assignShown,
                boolean assignEnabled) {
            this.tabShown = tabShown;
            this.tabs = Collections.unmodifiableList(new ArrayList<>(tabs));
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.assignShown = assignShown;
            this.assignEnabled = assignEnabled;
        }
    }

    /**
     * The values of a multi-valued item, as the GUI's value rows change them (an item of {@link Spec#items} whose
     * value is this): exactly these values ({@link #exactly}, null: not given), or the values removed, then those
     * added, the others kept.
     */
    final class ValueRows {
        /** The item's values after the change, or null: only {@link #removed} and {@link #added}. */
        public final List<Object> exactly;
        public final List<Object> added;
        public final List<Object> removed;

        private ValueRows(List<Object> exactly, List<Object> added, List<Object> removed) {
            this.exactly = exactly == null ? null : Collections.unmodifiableList(new ArrayList<>(exactly));
            this.added = Collections.unmodifiableList(new ArrayList<>(added));
            this.removed = Collections.unmodifiableList(new ArrayList<>(removed));
        }

        public static ValueRows exactly(java.util.Collection<?> values) {
            return new ValueRows(new ArrayList<>(values), List.of(), List.of());
        }

        /**
         * This change and a value added: an item typed into before (a single value) is its first value, a list of
         * exact values gets it, else it is added to the rows (and no longer removed).
         */
        public static ValueRows add(Object before, Object value) {
            ValueRows rows = of(before);
            if (rows.exactly != null) {
                List<Object> exactly = new ArrayList<>(rows.exactly);
                exactly.add(value);
                return new ValueRows(exactly, List.of(), List.of());
            }
            List<Object> added = new ArrayList<>(rows.added);
            added.add(value);
            List<Object> removed = new ArrayList<>(rows.removed);
            removed.remove(value);
            return new ValueRows(null, added, removed);
        }

        /** This change and a value removed (see {@link #add}). */
        public static ValueRows remove(Object before, Object value) {
            ValueRows rows = of(before);
            if (rows.exactly != null) {
                List<Object> exactly = new ArrayList<>(rows.exactly);
                exactly.remove(value);
                return new ValueRows(exactly, List.of(), List.of());
            }
            List<Object> removed = new ArrayList<>(rows.removed);
            removed.add(value);
            List<Object> added = new ArrayList<>(rows.added);
            added.remove(value);
            return new ValueRows(null, added, removed);
        }

        private static ValueRows of(Object before) {
            if (before == null) {
                return new ValueRows(null, List.of(), List.of());
            }
            if (before instanceof ValueRows) {
                return (ValueRows) before;
            }
            return exactly(before instanceof java.util.Collection ? (java.util.Collection<?>) before
                    : List.of(before));
        }

        @Override
        public String toString() {
            if (exactly != null) {
                return String.valueOf(exactly);
            }
            return (removed.isEmpty() ? "" : "-" + removed) + (added.isEmpty() ? "" : (removed.isEmpty() ? "" : " ")
                    + "+" + added);
        }
    }

    /** What the field shows after a value is typed into it. */
    final class Field {
        /** Whether the item has a validation in the page (an object template's item/validation/server). */
        public final boolean validated;
        /** The messages the field shows (none: the value passes). */
        public final List<String> messages;
        /** Why the field is not there to type into (hidden, read-only, no such item), or null. */
        public final String absent;

        public Field(boolean validated, List<String> messages, String absent) {
            this.validated = validated;
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
            this.absent = absent;
        }
    }

    /** What saving the page did. */
    final class Saved {
        /** The page saved the object (the changes executed without an error). */
        public final boolean saved;
        /** The messages on the fields, by item path, in the page's order. */
        public final Map<String, List<String>> fields;
        /** The other messages of the page: the form's (the validation on saving) and the operation's result. */
        public final List<String> page;

        public Saved(boolean saved, Map<String, List<String>> fields, List<String> page) {
            this.saved = saved;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            this.page = Collections.unmodifiableList(new ArrayList<>(page));
        }
    }

    /**
     * A tab of the page as the GUI has it (4.4 and later: an item of the details navigation; 4.0 and 4.4's old design:
     * a tab of the tabbed panel), shown to the user or not.
     */
    final class TabInfo {
        /** The label as the GUI renders it, in the session's locale (a hidden tab's translated the same way). */
        public final String label;
        /** The label's localization key, or null: a literal label. */
        public final String key;
        /** The line's identifier: 4.4 and later the panel's identifier, 4.0 the local name of the tab's URI; or null. */
        public final String identifier;
        public final boolean shown;
        /** Why the GUI does not show it, or null: shown. */
        public final String hiddenBecause;
        /** Its sub-tabs (4.4 and later: the panel's sub-panels), in the GUI's order. */
        public final List<TabInfo> subtabs;

        public TabInfo(String label, String key, String identifier, boolean shown, String hiddenBecause,
                List<TabInfo> subtabs) {
            this.label = label;
            this.key = key;
            this.identifier = identifier;
            this.shown = shown;
            this.hiddenBecause = shown ? null : hiddenBecause;
            this.subtabs = Collections.unmodifiableList(new ArrayList<>(subtabs));
        }
    }

    /**
     * An entry of the side menu as the GUI built it for the user: a section (SELF SERVICE, ADMINISTRATION...), a main
     * item of a section (Users, Request access; 4.4 and later a heading without a page when it has sub-items) or a
     * sub-item (All users, a collection view, Organization tree). 4.4 and later build the menu of what the user may
     * see (an entry hidden from them is not in it); 4.0 builds every entry and hides at rendering, so its hidden
     * entries are here with why.
     */
    final class MenuInfo {
        /** The label's localization key, or null: a literal label (a collection view's, an additional link's). */
        public final String key;
        /** The label as the GUI renders it, in the session's locale. */
        public final String label;
        /** The page it opens (its class name), or null: a section, or a heading of sub-items. */
        public final String pageClass;
        /** The page's URL (its first mount URL; an additional link's target URL), or null. */
        public final String url;
        /** The actions of the page's descriptor, which the menu asks for with {@code #guiAll} and {@code #all}. */
        public final List<String> actions;
        /** The page parameters ({@code collectionName=people}), or null. */
        public final String params;
        public final boolean shown;
        /** Why the GUI hides it (4.0), or null. */
        public final String hiddenBecause;
        /** Its entries (a section's main items, a main item's sub-items), in the GUI's order. */
        public final List<MenuInfo> items;

        public MenuInfo(String key, String label, String pageClass, String url, List<String> actions, String params,
                boolean shown, String hiddenBecause, List<MenuInfo> items) {
            this.key = key;
            this.label = label;
            this.pageClass = pageClass;
            this.url = url;
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
            this.params = params;
            this.shown = shown;
            this.hiddenBecause = shown ? null : hiddenBecause;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    /** The page opened, the items typed into, then the value into the item. */
    Field type(Spec spec, String item, Object value);

    /**
     * The side menu as the GUI builds it for the user (null: the administrator), read from a page rendered as them:
     * its sections with their main items and sub-items, in the GUI's order.
     */
    List<MenuInfo> menu(String user);

    /** The page opened, the items typed into, then Save. */
    Saved save(Spec spec);

    /** The page opened: its tabs, shown or hidden, in the GUI's order. */
    List<TabInfo> tabs(Spec spec);

    /** The page opened, its Assignments tab (or Inducements tab) opened on its "All" sub-panel: its rows. */
    Rows rows(Spec spec, boolean inducements);

    /** The forms of the deployment's midPoint (one per runtime). */
    static GuiForms of(com.github.wadahiro.midpoint.tester.MidPoint api) {
        return ((MidPointImpl) api).guiForms();
    }

    /** The line's implementation: GuiFormsImpl of the line module, with the GUI module on the class path. */
    static GuiForms load(MidPointImpl mp) {
        for (String required : new String[] {"com.evolveum.midpoint.web.security.MidPointApplication",
                "org.apache.wicket.util.tester.WicketTester"}) {
            try {
                Class.forName(required, false, GuiForms.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("The GUI's forms run midPoint's GUI, which is not on the test class path"
                        + " (no " + required + "): the tester brings com.evolveum.midpoint.gui:admin-gui and on 4.8"
                        + " and later org.apache.wicket:wicket-tester; remove their exclusion from the tester's"
                        + " dependency (docs/guide/environment.md, Classes of the GUI)");
            }
        }
        try {
            return (GuiForms) Class.forName(GuiForms.class.getPackageName() + ".GuiFormsImpl")
                    .getDeclaredConstructor(MidPointImpl.class).newInstance(mp);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No GUI forms in this line: " + e, e);
        }
    }
}
