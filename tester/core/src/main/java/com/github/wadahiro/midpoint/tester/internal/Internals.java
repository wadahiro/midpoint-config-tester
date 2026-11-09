package com.github.wadahiro.midpoint.tester.internal;

import com.github.wadahiro.midpoint.tester.MidPoint;

/** What the tester's own packages reach of the runtime that the public API does not show. */
public final class Internals {

    private Internals() {
    }

    /** The test's clock with the runs it starts ({@code MidPointConfigTester#clock()} drives it). */
    public static Time time(MidPoint mp) {
        return ((MidPointImpl) mp).time();
    }
}
