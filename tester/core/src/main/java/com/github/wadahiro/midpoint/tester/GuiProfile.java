package com.github.wadahiro.midpoint.tester;

import java.util.ArrayList;
import java.util.List;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.xml.ns._public.common.common_3.GuiObjectDetailsSetType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RichHyperlinkType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserInterfaceElementVisibilityType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserInterfaceFeatureType;

/**
 * A logged-in user's GUI profile as midPoint compiled it at login ({@code Access.guiProfile()}): the
 * {@code adminGuiConfiguration} of the system configuration, then of every role, org and archetype that gives one
 * (assigned or induced, as midPoint evaluates them), merged by midPoint ({@code CompiledGuiProfile}; 4.0:
 * {@code CompiledUserProfile}).
 */
public final class GuiProfile {

    private final List<CollectionView> views = new ArrayList<>();
    private final List<RichHyperlinkType> additionalMenuLinks = new ArrayList<>();
    private final List<RichHyperlinkType> userDashboardLinks = new ArrayList<>();
    private final List<UserInterfaceFeatureType> features = new ArrayList<>();
    private GuiObjectDetailsSetType objectDetails;
    private String defaultTimezone;
    private String preferredDataLanguage;
    private Object compiled;

    /** The object collection views of every type, in midPoint's order. */
    public List<CollectionView> views() {
        return views;
    }

    /** The views of the type (e.g. {@code UserType.COMPLEX_TYPE}). */
    public List<CollectionView> views(QName type) {
        List<CollectionView> out = new ArrayList<>();
        for (CollectionView view : views) {
            if (QNames.match(view.type(), type)) {
                out.add(view);
            }
        }
        return out;
    }

    /** The view of the type with this identifier, null if there is none. */
    public CollectionView view(QName type, String identifier) {
        for (CollectionView view : views(type)) {
            if (identifier.equals(view.identifier())) {
                return view;
            }
        }
        return null;
    }

    /** The links of the side menu's "Additional" section. */
    public List<RichHyperlinkType> additionalMenuLinks() {
        return additionalMenuLinks;
    }

    /** The links of the user's (self service) dashboard. */
    public List<RichHyperlinkType> userDashboardLinks() {
        return userDashboardLinks;
    }

    /** The GUI features ({@code feature}: an identifier and a visibility). */
    public List<UserInterfaceFeatureType> features() {
        return features;
    }

    /** The visibility of the feature with this identifier; null if no configuration names it. */
    public UserInterfaceElementVisibilityType featureVisibility(String identifier) {
        for (UserInterfaceFeatureType feature : features) {
            if (identifier.equals(feature.getIdentifier())) {
                return feature.getVisibility();
            }
        }
        return null;
    }

    /**
     * Whether the GUI shows the feature, as midPoint decides it ({@code isFeatureVisible} of the compiled profile,
     * with no automatic predicate): visible or automatic, not hidden, vacant or a feature no configuration names.
     */
    public boolean isFeatureVisible(String identifier) {
        try {
            return (Boolean) compiled.getClass().getMethod("isFeatureVisible", String.class).invoke(compiled,
                    identifier);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("isFeatureVisible of " + compiled.getClass().getName(), e);
        }
    }

    /** The object details pages' configuration (their panels), null if none. */
    public GuiObjectDetailsSetType objectDetails() {
        return objectDetails;
    }

    public String defaultTimezone() {
        return defaultTimezone;
    }

    public String preferredDataLanguage() {
        return preferredDataLanguage;
    }

    /** midPoint's compiled profile itself ({@code CompiledGuiProfile}; 4.0: {@code CompiledUserProfile}). */
    public Object compiled() {
        return compiled;
    }

    // ---- filled by the line's drivers ----------------------------------------------------------------------------

    public GuiProfile objectDetails(GuiObjectDetailsSetType value) {
        objectDetails = value;
        return this;
    }

    public GuiProfile defaultTimezone(String value) {
        defaultTimezone = value;
        return this;
    }

    public GuiProfile preferredDataLanguage(String value) {
        preferredDataLanguage = value;
        return this;
    }

    public GuiProfile compiled(Object value) {
        compiled = value;
        return this;
    }

    /**
     * An object collection view of the compiled profile. Its visibility, whether it is the type's default view and
     * the operation it is for are 4.4+ (null, false and null on 4.0).
     */
    public static final class CollectionView {

        private final QName type;
        private final String identifier;
        private final String label;
        private final Integer displayOrder;
        private final UserInterfaceElementVisibilityType visibility;
        private final boolean defaultView;
        private final String operation;
        private final boolean inMenu;

        public CollectionView(QName type, String identifier, String label, Integer displayOrder,
                UserInterfaceElementVisibilityType visibility, boolean defaultView, String operation,
                boolean inMenu) {
            this.type = type;
            this.identifier = identifier;
            this.label = label;
            this.displayOrder = displayOrder;
            this.visibility = visibility;
            this.defaultView = defaultView;
            this.operation = operation;
            this.inMenu = inMenu;
        }

        /** The type of the objects it lists. */
        public QName type() {
            return type;
        }

        public String identifier() {
            return identifier;
        }

        /** Its display label (the original), null if it has none. */
        public String label() {
            return label;
        }

        public Integer displayOrder() {
            return displayOrder;
        }

        public UserInterfaceElementVisibilityType visibility() {
            return visibility;
        }

        public boolean defaultView() {
            return defaultView;
        }

        /** The operation it is for ({@code applicableForOperation}: {@code add}, {@code modify}), null for any. */
        public String operation() {
            return operation;
        }

        /**
         * Whether the line's side menu lists it under its type's section (users, roles...; on 4.0 not orgs or
         * tasks), by the GUI's rule: 4.8+, not the default view, not hidden or vacant, for no operation or modify;
         * 4.4, the same with a collection, the default view too; 4.0, a collection whose reference the user gets, an
         * archetype or an object collection. The menu's authorization of the type's views is not part of it (the
         * matrix's view rows add it: {@code #usersAll} or {@code #usersView} for users).
         */
        public boolean inMenu() {
            return inMenu;
        }

        @Override
        public String toString() {
            return QNames.local(type) + "/" + identifier + (label == null ? "" : " (" + label + ")");
        }
    }

    /** QName matching as midPoint's views do it: a type without a namespace matches by its local part. */
    private static final class QNames {

        static boolean match(QName a, QName b) {
            if (a == null || b == null) {
                return a == b;
            }
            if (a.getNamespaceURI().isEmpty() || b.getNamespaceURI().isEmpty()) {
                return a.getLocalPart().equals(b.getLocalPart());
            }
            return a.equals(b);
        }

        static String local(QName name) {
            return name == null ? "?" : name.getLocalPart();
        }
    }
}
