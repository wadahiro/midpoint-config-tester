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

        public Spec(Class<? extends ObjectType> type, String oid, String archetypeOid, String memberOid,
                Class<? extends ObjectType> memberType, QName relation, String user, Map<String, Object> items) {
            this.type = type;
            this.oid = oid;
            this.archetypeOid = archetypeOid;
            this.memberOid = memberOid;
            this.memberType = memberType;
            this.relation = relation;
            this.user = user;
            this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
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

    /** The page opened, the items typed into, then the value into the item. */
    Field type(Spec spec, String item, Object value);

    /** The page opened, the items typed into, then Save. */
    Saved save(Spec spec);

    /** The page opened: its tabs, shown or hidden, in the GUI's order. */
    List<TabInfo> tabs(Spec spec);

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
