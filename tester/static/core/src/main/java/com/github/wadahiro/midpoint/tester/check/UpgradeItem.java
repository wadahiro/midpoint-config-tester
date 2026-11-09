package com.github.wadahiro.midpoint.tester.check;

import com.evolveum.midpoint.prism.path.ItemPath;

/** One item of ninja verify's upgrade validator, as {@link StaticLine} gives it. */
public final class UpgradeItem {

    /** {@code CRITICAL}, {@code NECESSARY}, {@code OPTIONAL}; null when the validator gives none. */
    public final String priority;
    /** The validator's status, e.g. {@code WARNING}, {@code INFO}. */
    public final String status;
    /** The item, with container value IDs as the repository numbers them. */
    public final ItemPath path;
    public final String message;

    public UpgradeItem(String priority, String status, ItemPath path, String message) {
        this.priority = priority;
        this.status = status;
        this.path = path;
        this.message = message;
    }
}
