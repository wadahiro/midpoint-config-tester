package com.github.wadahiro.midpoint.tester;

import java.time.Instant;
import javax.xml.datatype.XMLGregorianCalendar;

import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SynchronizationSituationType;

/**
 * A shadow of a focus on a resource as the repository has it ({@link MidPoint#shadow}): what synchronization decided
 * about it, without reading the target system. The same on every line (4.0 to 4.10).
 */
public final class Shadow {

    private final ShadowType object;
    private final boolean linked;

    /** Created by the tester. */
    public Shadow(ShadowType object, boolean linked) {
        this.object = object;
        this.linked = linked;
    }

    public String oid() {
        return object.getOid();
    }

    /** The shadow's name (the naming attribute's value, e.g. the DN). */
    public String name() {
        return object.getName() != null ? object.getName().getOrig() : null;
    }

    public String resourceOid() {
        return object.getResourceRef() != null ? object.getResourceRef().getOid() : null;
    }

    /** {@code account}, {@code entitlement}, {@code generic}; null if not classified. */
    public String kind() {
        return object.getKind() != null ? object.getKind().value() : null;
    }

    public String intent() {
        return object.getIntent();
    }

    /** The synchronization situation ({@code LINKED}, {@code UNLINKED}, {@code UNMATCHED}, {@code DISPUTED}, {@code DELETED}). */
    public SynchronizationSituationType situation() {
        return object.getSynchronizationSituation();
    }

    /** When synchronization last processed the shadow; null if never. */
    public Instant synchronizationTimestamp() {
        return instant(object.getSynchronizationTimestamp());
    }

    /** When synchronization last processed the shadow fully (a reconciliation, an import); null if never. */
    public Instant fullSynchronizationTimestamp() {
        return instant(object.getFullSynchronizationTimestamp());
    }

    /**
     * Whether the focus links it as its projection ({@code linkRef} with the default relation); a dead shadow is kept
     * with the relation {@code related}.
     */
    public boolean linked() {
        return linked;
    }

    /** Whether the object is gone from the target system (midPoint keeps the shadow for a while). */
    public boolean dead() {
        return Boolean.TRUE.equals(object.isDead());
    }

    /** The shadow as the repository has it. */
    public ShadowType object() {
        return object;
    }

    private static Instant instant(XMLGregorianCalendar calendar) {
        return calendar != null ? calendar.toGregorianCalendar().toInstant() : null;
    }

    @Override
    public String toString() {
        return kind() + "/" + intent() + " " + name() + " " + situation() + (linked ? " linked" : "")
                + (dead() ? " dead" : "");
    }
}
