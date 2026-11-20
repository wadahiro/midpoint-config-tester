package com.github.wadahiro.midpoint.configtester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.wadahiro.midpoint.configtester.names.ObjectRef;
import com.github.wadahiro.midpoint.tester.internal.AccessRequestModel;

/**
 * An entry of the request-access page's catalog ({@link RequestAccessPage#entries}): a folder of the role catalog's
 * org tree (4.8 and later a menu item, 4.0 and 4.4 a node of the Role catalog tab's tree), a collection, a tab (4.0,
 * 4.4: All roles...). Immutable; its roles are searched when asked, as the requester, for the page's target, relation
 * and type.
 */
public final class CatalogEntry {

    /** What the entry is. */
    public enum Kind { ORG_FOLDER, COLLECTION, ALL_ROLES, ALL_ORGS, ALL_SERVICES, ROLES_OF_TEAMMATE, USER_ASSIGNMENTS }

    private final RequestAccessPage page;
    final AccessRequestModel.Entry model;
    private final List<CatalogEntry> children = new ArrayList<>();

    CatalogEntry(RequestAccessPage page, AccessRequestModel.Entry model) {
        this.page = page;
        this.model = model;
        for (AccessRequestModel.Entry child : model.children) {
            children.add(new CatalogEntry(page, child));
        }
    }

    public Kind kind() {
        return Kind.valueOf(model.kind);
    }

    /**
     * The label as the page shows it: an org's or a collection's display name or name; the fixed entries' English
     * labels ({@code All roles}, 4.0 and 4.4 {@code All roles view}, {@code Roles of teammate}).
     */
    public String label() {
        return model.label;
    }

    /**
     * The org's or collection's name, or the fixed entry's key: {@code roleCatalog} (4.0, 4.4: the tab, the catalog
     * org), {@code allRoles}, {@code allOrgs}, {@code allServices}, {@code userAssignments}, {@code rolesOfTeammate}.
     */
    public String key() {
        return model.key;
    }

    /** The org or the object collection, or null. */
    public ObjectRef object() {
        return model.objectOid == null ? null : ObjectRef.of(model.objectType.getLocalPart(),
                model.objectName != null ? model.objectName : model.objectOid, model.objectOid);
    }

    /** The sub-folders (4.8 and later down to {@code roleCatalogDepth}; 4.0 and 4.4 the tree). */
    public List<CatalogEntry> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * The objects the entry lists for the page's target, relation and type, searched as the requester, by name. Roles
     * of teammate and the user's assignments list nothing without a teammate ({@link #rolesOfTeammate}).
     */
    public List<ObjectRef> roles() {
        return page.listed(this, null);
    }

    /** Roles of teammate (4.8+) / User's assignments (4.0, 4.4): the entry's list for this teammate. */
    public List<ObjectRef> rolesOfTeammate(ObjectRef teammate) {
        if (kind() != Kind.ROLES_OF_TEAMMATE && kind() != Kind.USER_ASSIGNMENTS) {
            throw new IllegalArgumentException(this + " is no roles of teammate entry");
        }
        return page.listed(this, teammate);
    }

    /** Whether the entry lists the role. */
    public boolean shows(ObjectRef role) {
        return roles().contains(role);
    }

    /** Whether the entry lists the role and lets the requester add it ({@link RequestAccessPage#offers}). */
    public boolean offers(ObjectRef role) {
        return shows(role) && page.addEnabled(role);
    }

    /** {@code rc-it (folder)}, {@code allRoles}. */
    @Override
    public String toString() {
        switch (kind()) {
            case ORG_FOLDER:
                return model.label + " (folder)";
            case COLLECTION:
                return model.label + " (collection)";
            default:
                return model.key;
        }
    }
}
