package com.github.wadahiro.midpoint.tester;

/**
 * The midPoint line the shared tests run on, for the assertions that pin a difference between lines (each says what
 * differs and where in midPoint).
 */
final class Lines {

    private Lines() {
    }

    /** Whether the line is {@code line} or later (e.g. {@code atLeast(mp, "4.10")}). */
    static boolean atLeast(MidPoint mp, String line) {
        return compare(mp.line(), line) >= 0;
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
