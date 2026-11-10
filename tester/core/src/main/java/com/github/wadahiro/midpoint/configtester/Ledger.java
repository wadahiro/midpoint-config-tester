package com.github.wadahiro.midpoint.configtester;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The effects claimed so far in an exhaustive verification ({@link Claims}): a selection's terminal call
 * ({@code exactly}, {@code none}, {@code single}, {@code ignored}) claims what it selected.
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

    /** {@code ignored()} claims without asserting, which means nothing outside a verification. */
    static void requireLedger(Ledger ledger) {
        if (ledger == null) {
            throw new IllegalStateException(
                    "ignored() claims an effect in Effects.claims() (Kotlin: effects.verifyAll { }); here it has no meaning");
        }
    }
}
