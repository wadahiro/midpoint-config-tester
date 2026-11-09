package com.github.wadahiro.midpoint.configtester.names;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The midPoint lines on which a generated name exists: the config directories hold it for every line, minus the lines
 * whose overlay removes or replaces it ({@link #except}), or only the overlays of some lines add it ({@link #only}).
 */
public final class Lines {

    /** On every line. */
    public static final Lines ALL = new Lines(null, Set.of());

    private final Set<String> only;
    private final Set<String> except;

    private Lines(Set<String> only, Set<String> except) {
        this.only = only;
        this.except = except;
    }

    /** Only on these lines (an overlay adds it). */
    public static Lines only(String... lines) {
        return new Lines(Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(lines))), Set.of());
    }

    /** On every line but these (their overlay removes it or names it differently). */
    public static Lines except(String... lines) {
        return new Lines(null, Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(lines))));
    }

    public boolean includes(String line) {
        return (only == null || only.contains(line)) && !except.contains(line);
    }

    @Override
    public String toString() {
        if (only != null) {
            return "only on " + String.join(", ", only);
        }
        return except.isEmpty() ? "on every line" : "not on " + String.join(", ", except);
    }
}
