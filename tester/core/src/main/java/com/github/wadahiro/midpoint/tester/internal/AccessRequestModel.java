package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AssignmentConstraintsType;

/**
 * The request-access page of the line as its configuration makes it for the logged-in requester
 * ({@link LineDrivers#accessRequestModel}): who it lets them request for, the relation step, the catalog's entries with
 * the query of each (before the GUI ANDs the filter of what may be assigned to the target), and how it tells a role
 * held already. 4.0 and 4.4: PageAssignmentShoppingCart from the system configuration's {@code roleManagement}; 4.8
 * and later: PageRequestAccess from the compiled GUI profile's {@code accessRequest}. Computed, not rendered.
 */
public final class AccessRequestModel {

    /** The page's URL ({@code /self/requestAccess}, {@code /self/assignmentShoppingCart}) and class. */
    public String url;
    public String page;
    /** The wizard of 4.8 and later (person of interest, relation, catalog, cart), else the shopping cart. */
    public boolean wizard;
    /** Where the configuration comes from, for failures. */
    public String configuration;

    /** The Myself and Others choices (4.8+: targetSelection; 4.0, 4.4: always). */
    public boolean allowMyself = true;
    public boolean allowOthers = true;
    /**
     * The filter of the users the requester may pick (4.8+: the object part of their #assign authorizations, request
     * phase, {@code getAccessibleForAssignmentObjectsFilter}), or null: any user their search finds.
     */
    public ObjectFilter targetFilter;
    /** 4.8+: the groups of targetSelection, by identifier: the target filter AND the group's collection. */
    public final Map<String, ObjectFilter> groups = new LinkedHashMap<>();

    /** The configured default relation (4.8+: relationSelection/defaultRelation), or null: {@code default}. */
    public QName defaultRelation;
    /** 4.8+: relationSelection/allowOtherRelations (null: allowed). */
    public Boolean allowOtherRelations;

    /** The entries, in the page's order. */
    public final List<Entry> entries = new ArrayList<>();

    /** roleManagement/defaultAssignmentConstraints, or null. */
    public AssignmentConstraintsType constraints;
    /** How a held role is told: 4.8+ the target's roleMembershipRef, 4.0 and 4.4 its assignments. */
    public boolean heldByMembership;

    /** An entry of the catalog: an org folder, a collection, a tab. */
    public static final class Entry {
        /** {@code ORG_FOLDER}, {@code COLLECTION}, {@code ALL_ROLES}..., as {@code configtester.CatalogEntry.Kind}. */
        public String kind;
        public String label;
        public String key;
        /** The org or object collection, or null. */
        public String objectOid;
        public QName objectType;
        public String objectName;
        /** The type the entry lists and its filter (null: all of the type); null type: roles of a teammate. */
        public Class<? extends AbstractRoleType> type;
        public ObjectFilter filter;
        /**
         * An org folder's place in the tree: its level (4.8+: the menu's, the catalog org's children 1; 4.0, 4.4: the
         * tree's, the catalog org 0) and the deepest level the page shows (4.8+: {@code roleCatalogDepth}; 4.0, 4.4:
         * null, the tree has no limit).
         */
        public int level;
        public Integer maxLevel;
        /**
         * The sub-folders, read when first asked ({@link AccessRequests#children}): the GUI reads a folder's children
         * when the user expands it (4.0, 4.4: OrgTreeProvider.getChildren; 4.8+: the menu item's LoadableModel).
         */
        public final List<Entry> children = new ArrayList<>();
        public boolean childrenRead;
    }
}
