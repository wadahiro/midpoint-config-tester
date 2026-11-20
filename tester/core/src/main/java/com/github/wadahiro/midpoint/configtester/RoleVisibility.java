package com.github.wadahiro.midpoint.configtester;

import java.util.Collections;
import java.util.List;

/**
 * Why a role is or is not on the request-access page ({@link RequestAccessPage#explain}): the steps the GUI's query
 * takes, each decided on its own. Immutable.
 */
public final class RoleVisibility {

    private final List<CatalogEntry> listedIn;
    private final boolean assignable;
    private final boolean foundByRequester;
    private final boolean addEnabled;
    private final String heldAs;

    RoleVisibility(List<CatalogEntry> listedIn, boolean assignable, boolean foundByRequester, boolean addEnabled,
            String heldAs) {
        this.listedIn = Collections.unmodifiableList(listedIn);
        this.assignable = assignable;
        this.foundByRequester = foundByRequester;
        this.addEnabled = addEnabled;
        this.heldAs = heldAs;
    }

    /** The entries whose own query (without authorizations, as the administrator) matches the role. */
    public List<CatalogEntry> listedIn() {
        return listedIn;
    }

    /**
     * Whether the requester may assign it to the target with the page's relation: the filter of
     * {@code getAssignableRoleSpecification(target, type, 0)} (global or of the relation) matches it.
     */
    public boolean assignable() {
        return assignable;
    }

    /** Whether the requester's search of the role's type finds it ({@code #search} or {@code #read}). */
    public boolean foundByRequester() {
        return foundByRequester;
    }

    /** Whether its add is enabled: not held by the target already as the line and the assignment constraints say. */
    public boolean addEnabled() {
        return addEnabled;
    }

    /** The relations the target holds it with ({@code default, manager}), or null: not held. */
    public String heldAs() {
        return heldAs;
    }

    @Override
    public String toString() {
        return "listed in " + listedIn + ", assignable " + assignable + ", found by the requester " + foundByRequester
                + ", add enabled " + addEnabled + (heldAs != null ? ", held as " + heldAs : "");
    }
}
