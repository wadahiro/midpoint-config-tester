package com.github.wadahiro.midpoint.configtester;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The effects claimed so far in an exhaustive verification ({@link Claims}): a selection's terminal call
 * ({@code exactly}, {@code none}, {@code single}) claims what it selected.
 */
final class Ledger {

    private final Set<Object> claimed = Collections.newSetFromMap(new IdentityHashMap<>());

    synchronized void claim(Collection<?> effects) {
        claimed.addAll(effects);
    }

    synchronized boolean isClaimed(Object effect) {
        return claimed.contains(effect);
    }

    /** Claims the effects if there is a ledger; outside a verification there is none. */
    static void claim(Ledger ledger, Collection<?> effects) {
        if (ledger != null) {
            ledger.claim(effects);
        }
    }
}
