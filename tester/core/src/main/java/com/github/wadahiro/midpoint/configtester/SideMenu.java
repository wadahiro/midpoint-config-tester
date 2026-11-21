package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AuthorizationDecisionType;
import com.github.wadahiro.midpoint.tester.CompiledAuthorization;
import com.github.wadahiro.midpoint.tester.GuiPages;
import com.github.wadahiro.midpoint.tester.internal.GuiForms;

/**
 * The side menu of midPoint's GUI as it shows for a user ({@link Actor#sideMenu}): its sections (SELF SERVICE,
 * ADMINISTRATION, CONFIGURATION, ADDITIONAL; 4.11 INTEGRATION) with their items and sub-items, built by the GUI itself
 * for the user logged in (a page rendered in the JVM, as the forms are: no browser). The GUI lists an item when any of
 * the user's authorizations names one of its page's actions, {@code #guiAll} or {@code #all}, whatever its decision and
 * object (Wicket's roles of the session; 4.11 and later first hide an item whose action a deny authorization names);
 * opening the page is the security enforcer's decision ({@link Actor#canOpen}), so a page a deny authorization names
 * is listed and refused before 4.11.
 *
 * <pre>{@code
 * t.actor(Users.JACK).sideMenu().hasItem(MenuPage.REQUEST_ACCESS).hasNoSection("ADMINISTRATION");
 * t.actor(Users.HELPDESK).sideMenu().hasItem("/admin/users").hasNoItem(MenuPage.ROLES);
 * assertThat(t.actor(Users.JACK).sideMenu().labels()).containsExactly("Home", "Profile", "Credentials", "Request access");
 * }</pre>
 *
 * An item is named by its page ({@link MenuPage}, the same on every line), by its URL after the GUI's context
 * ({@code /self/requestAccess}, any URL the page is mounted at), by its label as the GUI renders it (in the JVM's
 * locale: "Request access") or by its label's key ({@code PageRequestAccess.title}). A failed claim says what the
 * item's page asks for and which of the user's authorizations name it, and lists the menu; the test's failure then
 * lists the user's authorizations.
 */
public final class SideMenu {

    private final MidPointConfigTester tester;
    private final Actor actor;
    private final List<Section> sections;

    SideMenu(MidPointConfigTester tester, Actor actor, List<GuiForms.MenuInfo> infos) {
        this.tester = tester;
        this.actor = actor;
        List<Section> all = new ArrayList<>();
        for (GuiForms.MenuInfo info : infos) {
            all.add(new Section(info));
        }
        this.sections = Collections.unmodifiableList(all);
    }

    // ---- sections ---------------------------------------------------------------------------------------------

    /** The sections the user sees, in the GUI's order. */
    public List<Section> sections() {
        List<Section> out = new ArrayList<>();
        for (Section section : sections) {
            if (section.shown()) {
                out.add(section);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The labels of the sections the user sees ({@code SELF SERVICE}, {@code ADMINISTRATION}...). */
    public List<String> sectionLabels() {
        List<String> out = new ArrayList<>();
        for (Section section : sections()) {
            out.add(section.label());
        }
        return Collections.unmodifiableList(out);
    }

    /** The section by its label or key, shown or (4.0) hidden; none fails. */
    public Section section(String labelOrKey) {
        for (Section section : sections) {
            if (section.is(labelOrKey)) {
                return section;
            }
        }
        throw new IllegalArgumentException(subject() + " has no section " + labelOrKey + "; " + text());
    }

    /** Whether the user sees the section (by its label or key). */
    public boolean showsSection(String labelOrKey) {
        for (Section section : sections()) {
            if (section.is(labelOrKey)) {
                return true;
            }
        }
        return false;
    }

    /** The user sees the section (by its label or key). */
    public SideMenu hasSection(String labelOrKey) {
        if (!showsSection(labelOrKey)) {
            throw new AssertionError(subject() + " shows no section " + labelOrKey + "; " + text());
        }
        return this;
    }

    /** The user does not see the section: the GUI drops a section none of whose items they see. */
    public SideMenu hasNoSection(String labelOrKey) {
        if (showsSection(labelOrKey)) {
            throw new AssertionError(subject() + " shows the section " + labelOrKey + "; " + text());
        }
        return this;
    }

    // ---- items ------------------------------------------------------------------------------------------------

    /**
     * The items the user sees, of every section, in the GUI's order: a main item, then its sub-items (a heading
     * without a page, "Users" on 4.4 and later, is an item too).
     */
    public List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (Section section : sections()) {
            for (Item item : section.items()) {
                out.add(item);
                out.addAll(item.items());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The labels of the items the user sees, as the GUI renders them (in the JVM's locale). */
    public List<String> labels() {
        List<String> out = new ArrayList<>();
        for (Item item : items()) {
            out.add(item.label());
        }
        return Collections.unmodifiableList(out);
    }

    /** The item of the page, shown or (4.0) hidden; none fails. */
    public Item item(MenuPage page) {
        Item found = find(page::matchesItem, false);
        if (found == null) {
            throw new IllegalArgumentException(subject() + " has no item of " + page + "; " + text());
        }
        return found;
    }

    /** The item by its URL, label or label key, shown or (4.0) hidden; none fails. */
    public Item item(String urlLabelOrKey) {
        Item found = find(item -> item.is(urlLabelOrKey), false);
        if (found == null) {
            throw new IllegalArgumentException(subject() + " has no item " + urlLabelOrKey + "; " + text());
        }
        return found;
    }

    /** Whether the user sees an item of the page. */
    public boolean shows(MenuPage page) {
        return find(page::matchesItem, true) != null;
    }

    /** Whether the user sees the item (by its URL, label or label key). */
    public boolean shows(String urlLabelOrKey) {
        return find(item -> item.is(urlLabelOrKey), true) != null;
    }

    /** The user sees an item of the page; pair it with {@link Actor#canOpen}. */
    public SideMenu hasItem(MenuPage page) {
        if (!shows(page)) {
            throw new AssertionError(subject() + " shows no item of " + page + ": " + whyHidden(page::matchesItem,
                    page.urls()) + "; " + text());
        }
        return this;
    }

    /** The user sees the item (by its URL, label or label key). */
    public SideMenu hasItem(String urlLabelOrKey) {
        if (!shows(urlLabelOrKey)) {
            throw new AssertionError(subject() + " shows no item " + urlLabelOrKey + ": "
                    + whyHidden(item -> item.is(urlLabelOrKey), urlLabelOrKey.startsWith("/")
                            ? List.of(urlLabelOrKey) : List.of()) + "; " + text());
        }
        return this;
    }

    /** The user sees no item of the page; pair it with {@link Actor#canOpen} false. */
    public SideMenu hasNoItem(MenuPage page) {
        Item shown = find(page::matchesItem, true);
        if (shown != null) {
            throw new AssertionError(subject() + " shows " + shown.describe() + " of " + page + ": " + whyShown(shown)
                    + "; " + text());
        }
        return this;
    }

    /** The user sees no such item (by its URL, label or label key). */
    public SideMenu hasNoItem(String urlLabelOrKey) {
        Item shown = find(item -> item.is(urlLabelOrKey), true);
        if (shown != null) {
            throw new AssertionError(subject() + " shows " + shown.describe() + ": " + whyShown(shown) + "; "
                    + text());
        }
        return this;
    }

    /** A shown item the predicate accepts, else (not only shown) a hidden one; null: none. */
    private Item find(java.util.function.Predicate<Item> predicate, boolean shownOnly) {
        Item hidden = null;
        for (Section section : sections) {
            for (Item item : section.all()) {
                for (Item candidate : item.withSubItems()) {
                    if (predicate.test(candidate)) {
                        if (candidate.shown() && section.shown()) {
                            return candidate;
                        }
                        hidden = hidden != null ? hidden : candidate;
                    }
                }
            }
        }
        return shownOnly ? null : hidden;
    }

    // ---- why --------------------------------------------------------------------------------------------------

    /**
     * Why the menu has no such item: the GUI hid it (4.0: its reason); or no page of the GUI is at the URL; or the
     * page's actions, which the menu asks for, and whether any of the user's authorizations names one.
     */
    private String whyHidden(java.util.function.Predicate<Item> predicate, List<String> urls) {
        Item hidden = find(predicate, false);
        if (hidden != null) {
            String why = "the GUI hides " + hidden.describe() + (hidden.hiddenBecause() != null
                    ? " (" + hidden.hiddenBecause() + ")" : "");
            if (hidden.actions().isEmpty()) {
                return why;
            }
            Set<String> asked = new LinkedHashSet<>(hidden.actions());
            asked.addAll(actor.guiAll());
            asked.add(AuthorizationConstants.AUTZ_ALL_URL);
            List<CompiledAuthorization> naming = actor.namingAny(asked);
            return why + ": the menu lists it when any of " + actor + "'s authorizations names one of " + short_(asked)
                    + " (whatever its decision), and " + (naming.isEmpty() ? "none does" : describe(naming) + " does");
        }
        if (urls.isEmpty()) {
            return "no item has that label or key";
        }
        for (String url : urls) {
            GuiPages.Required required = GuiPages.of().required(url, actor.guiAll());
            if (required.matches().isEmpty()) {
                continue;
            }
            if (required.permitted()) {
                return "the GUI's page at " + url + " (" + required.matches() + ") is one any user opens, not a menu"
                        + " item of this line";
            }
            Set<String> asked = new LinkedHashSet<>(required.actions());
            asked.add(AuthorizationConstants.AUTZ_ALL_URL);
            List<CompiledAuthorization> naming = actor.namingAny(asked);
            if (naming.isEmpty()) {
                return "the menu lists the page at " + url + " when any of " + actor + "'s authorizations names one"
                        + " of " + short_(asked) + " (whatever its decision), and none does";
            }
            boolean denied = naming.stream().anyMatch(
                    c -> c.authorization().getDecision() == AuthorizationDecisionType.DENY);
            return "the page at " + url + " is named by " + describe(naming) + ", yet this line's menu does not"
                    + " list it: " + (denied ? "4.11 and later hide an item whose action a deny authorization names"
                            + " (SecurityEnforcer.isAuthorizationDenied, whatever its object or phase); otherwise " : "")
                    + "not an item of this line's menu (" + required.matches() + "), or hidden for another"
                    + " reason (an experimental page without enableExperimentalFeatures, the type's default view hidden,"
                    + " cases disabled)";
        }
        return "no page of the GUI is at " + String.join(" or ", urls);
    }

    /** Why the menu shows the item: the user's authorizations naming its page's actions. */
    private String whyShown(Item item) {
        if (item.actions().isEmpty()) {
            return item.url() != null ? "a link of the GUI profile (additionalMenuLink), shown to anyone who has it"
                    : "a heading of sub-items the user sees";
        }
        Set<String> asked = new LinkedHashSet<>(item.actions());
        asked.addAll(actor.guiAll());
        asked.add(AuthorizationConstants.AUTZ_ALL_URL);
        List<CompiledAuthorization> naming = actor.namingAny(asked);
        return "its page asks for one of " + short_(asked) + ", named by " + (naming.isEmpty()
                ? "none of " + actor + "'s authorizations (the GUI lists it for another reason)" : describe(naming));
    }

    private static String describe(List<CompiledAuthorization> naming) {
        List<String> out = new ArrayList<>();
        for (CompiledAuthorization compiled : naming) {
            out.add((compiled.authorization().getName() != null ? "'" + compiled.authorization().getName() + "' " : "")
                    + (compiled.authorization().getDecision() != null
                            ? compiled.authorization().getDecision().value() + " " : "allow ")
                    + short_(compiled.authorization().getAction()) + " of " + Actor.source(compiled.source()));
        }
        return String.join(", ", out);
    }

    /** {@code authorization-ui-3#selfAll} of the action URI. */
    static String short_(java.util.Collection<String> actions) {
        List<String> out = new ArrayList<>();
        for (String action : actions) {
            int slash = action.lastIndexOf('/');
            out.add(slash >= 0 ? action.substring(slash + 1) : action);
        }
        return out.toString();
    }

    /** The URL without its query and trailing slash. */
    static String bare(String url) {
        String out = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (out.endsWith("/**")) {
            out = out.substring(0, out.length() - 3);
        }
        while (out.length() > 1 && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private String subject() {
        return actor + ": the side menu";
    }

    /** {@code SELF SERVICE: Home, Profile, Credentials, Request access; ADMINISTRATION: Users > All users, ...}. */
    String text() {
        List<String> parts = new ArrayList<>();
        for (Section section : sections()) {
            List<String> items = new ArrayList<>();
            for (Item item : section.items()) {
                StringBuilder sb = new StringBuilder(item.label());
                if (!item.items().isEmpty()) {
                    List<String> sub = new ArrayList<>();
                    for (Item child : item.items()) {
                        sub.add(child.label());
                    }
                    sb.append(" > ").append(String.join(" | ", sub));
                }
                items.add(sb.toString());
            }
            parts.add(section.label() + ": " + (items.isEmpty() ? "nothing" : String.join(", ", items)));
        }
        List<String> hidden = new ArrayList<>();
        for (Section section : sections) {
            for (Item item : section.all()) {
                for (Item candidate : item.withSubItems()) {
                    if (!candidate.shown() || !section.shown()) {
                        hidden.add(candidate.describe() + (candidate.hiddenBecause() != null
                                ? " (" + candidate.hiddenBecause() + ")" : ""));
                    }
                }
            }
        }
        return "the menu: " + (parts.isEmpty() ? "empty" : String.join("; ", parts))
                + (hidden.isEmpty() ? "" : "; hidden: " + String.join(", ", hidden));
    }

    @Override
    public String toString() {
        return "the side menu of " + actor + ": " + text();
    }

    // ---- entries ----------------------------------------------------------------------------------------------

    /** A section of the side menu (SELF SERVICE, ADMINISTRATION...), with its main items. */
    public static final class Section {

        private final GuiForms.MenuInfo info;
        private final List<Item> items;

        Section(GuiForms.MenuInfo info) {
            this.info = info;
            List<Item> all = new ArrayList<>();
            for (GuiForms.MenuInfo item : info.items) {
                all.add(new Item(this, null, item));
            }
            this.items = Collections.unmodifiableList(all);
        }

        /** The label as the GUI renders it ({@code SELF SERVICE}). */
        public String label() {
            return info.label;
        }

        /** The label's key ({@code PageAdmin.menu.selfService}). */
        public String key() {
            return info.key;
        }

        /** Whether the user sees it: the GUI drops a section none of whose items they see. */
        public boolean shown() {
            return info.shown;
        }

        /** The main items the user sees, in the GUI's order. */
        public List<Item> items() {
            List<Item> out = new ArrayList<>();
            for (Item item : items) {
                if (item.shown()) {
                    out.add(item);
                }
            }
            return Collections.unmodifiableList(out);
        }

        /** The user sees the item (by its URL, label or key) in this section, as a main item or a sub-item. */
        public Section hasItem(String urlLabelOrKey) {
            for (Item item : items()) {
                for (Item candidate : item.withSubItems()) {
                    if (candidate.shown() && candidate.is(urlLabelOrKey)) {
                        return this;
                    }
                }
            }
            throw new AssertionError("The section " + label() + " shows no item " + urlLabelOrKey + "; its items: "
                    + labels());
        }

        /** The labels of the main items the user sees. */
        public List<String> labels() {
            List<String> out = new ArrayList<>();
            for (Item item : items()) {
                out.add(item.label());
            }
            return Collections.unmodifiableList(out);
        }

        List<Item> all() {
            return items;
        }

        boolean is(String labelOrKey) {
            return labelOrKey != null && (labelOrKey.equals(info.label) || labelOrKey.equals(info.key));
        }

        @Override
        public String toString() {
            return label() + (shown() ? "" : " (hidden)") + ": " + labels();
        }
    }

    /** An item of the side menu: a main item of a section or a sub-item of one. */
    public static final class Item {

        private final Section section;
        private final Item parent;
        private final GuiForms.MenuInfo info;
        private final List<Item> items;

        Item(Section section, Item parent, GuiForms.MenuInfo info) {
            this.section = section;
            this.parent = parent;
            this.info = info;
            List<Item> all = new ArrayList<>();
            for (GuiForms.MenuInfo item : info.items) {
                all.add(new Item(section, this, item));
            }
            this.items = Collections.unmodifiableList(all);
        }

        /** The label as the GUI renders it ("Request access"). */
        public String label() {
            return info.label;
        }

        /** The label's key ({@code PageRequestAccess.title}), or null: a literal label (a view's, a link's). */
        public String key() {
            return info.key;
        }

        /**
         * The URL of the page it opens, after the GUI's context ({@code /self/requestAccess}; a collection view's
         * is its list page's, {@link #params} naming the view), an additional link's target URL, or null: a heading.
         */
        public String url() {
            return info.url;
        }

        /** The simple name of the page it opens ({@code PageRequestAccess}), or null. */
        public String page() {
            if (info.pageClass == null) {
                return null;
            }
            return info.pageClass.substring(info.pageClass.lastIndexOf('.') + 1);
        }

        /** The page's parameters ({@code collectionName=people}), or null. */
        public String params() {
            return info.params;
        }

        /** The actions of the page's descriptor, one of which (or {@code #guiAll}, {@code #all}) the menu asks for. */
        public List<String> actions() {
            return info.actions;
        }

        /** Whether the user sees it (4.0 reads the hidden entries too). */
        public boolean shown() {
            return info.shown;
        }

        /** Why the GUI hides it (4.0), or null. */
        public String hiddenBecause() {
            return info.hiddenBecause;
        }

        /** The section it is in. */
        public Section section() {
            return section;
        }

        /** The main item a sub-item belongs to, or null. */
        public Item parent() {
            return parent;
        }

        /** The sub-items the user sees, in the GUI's order. */
        public List<Item> items() {
            List<Item> out = new ArrayList<>();
            for (Item item : items) {
                if (item.shown()) {
                    out.add(item);
                }
            }
            return Collections.unmodifiableList(out);
        }

        /** This item, then every sub-item, shown or not. */
        List<Item> withSubItems() {
            List<Item> out = new ArrayList<>();
            out.add(this);
            out.addAll(items);
            return out;
        }

        /** The URLs the page is mounted at: its own and those the line's index of the GUI's pages has for it. */
        List<String> urls() {
            List<String> out = new ArrayList<>();
            if (info.url != null) {
                out.add(bare(info.url));
            }
            if (info.pageClass != null) {
                try {
                    for (String url : GuiPages.of().urlsOf(info.pageClass)) {
                        if (!out.contains(url)) {
                            out.add(url);
                        }
                    }
                } catch (IllegalStateException e) {
                    // no index on this line: the page's own URL
                }
            }
            return out;
        }

        boolean is(String urlLabelOrKey) {
            if (urlLabelOrKey == null) {
                return false;
            }
            if (urlLabelOrKey.startsWith("/")) {
                return urls().contains(bare(urlLabelOrKey));
            }
            return urlLabelOrKey.equals(info.label) || urlLabelOrKey.equals(info.key)
                    || urlLabelOrKey.equals(info.url);
        }

        /** {@code 'Request access' (/self/requestAccess) under SELF SERVICE}. */
        String describe() {
            return "'" + label() + "'" + (url() != null ? " (" + url() + (params() != null ? "?" + params() : "")
                    + ")" : "") + " under " + section.label() + (parent != null ? " > " + parent.label() : "");
        }

        @Override
        public String toString() {
            return label() + (url() != null ? " (" + url() + (params() != null ? "?" + params() : "") + ")" : "")
                    + (shown() ? "" : " (hidden)");
        }
    }
}
