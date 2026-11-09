package com.github.wadahiro.midpoint.common;

/**
 * Compares midPoint release lines ({@code "4.0"}, {@code "4.4"}, {@code "4.10"}, ...) by their numbers, so that a
 * behaviour introduced in one line can be stated as "before 4.8" rather than as a list of line names.
 */
public final class MidPointLine {

    private MidPointLine() {
    }

    /** Whether {@code line} is older than {@code other}, e.g. {@code isBefore("4.4", "4.8")}. */
    public static boolean isBefore(String line, String other) {
        return compare(line, other) < 0;
    }

    /** Orders release lines numerically ({@code "4.8"} before {@code "4.10"}). */
    public static int compare(String line, String other) {
        String[] a = line.split("\\.");
        String[] b = other.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }
}
