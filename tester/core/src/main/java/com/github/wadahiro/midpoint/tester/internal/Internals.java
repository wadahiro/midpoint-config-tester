package com.github.wadahiro.midpoint.tester.internal;

import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.TargetSystem;

/** What the tester's own packages reach of the runtime that the public API does not show. */
public final class Internals {

    private Internals() {
    }

    /** The test's clock with the runs it starts ({@code MidPointConfigTester#clock()} drives it). */
    public static Time time(MidPoint mp) {
        return ((MidPointImpl) mp).time();
    }

    /**
     * Runs the calls with a task's partial error (failed on some of its objects) not failing a call if
     * {@code allowed}; a fatal one still does.
     */
    public static void allowingPartialErrors(MidPoint mp, boolean allowed, Runnable calls) {
        ((MidPointImpl) mp).allowingPartialErrors(allowed, calls);
    }

    /**
     * What a failed assertion on an account's status adds: why the status midPoint writes is not the one the system
     * reads (a simulated status attribute the stored schema does not declare), or "".
     */
    public static String statusHint(TargetSystem system) {
        return system instanceof ManagedTarget ? ((ManagedTarget) system).statusHint() : "";
    }
}
