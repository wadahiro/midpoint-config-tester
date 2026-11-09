package com.github.wadahiro.midpoint.tester;

import java.util.List;

/** What a bulk action ({@link MidPoint#bulkAction}) returned: the console output and the data output's values. */
public final class BulkActionOutput {

    private final String console;
    private final List<Object> data;

    public BulkActionOutput(String console, List<Object> data) {
        this.console = console;
        this.data = data;
    }

    /** What the actions logged to the console (e.g. {@code log}, {@code execute-script} with {@code log}). */
    public String console() {
        return console;
    }

    /** The real values of the data output (objects as {@code PrismObject}, other values as they are). */
    public List<Object> data() {
        return data;
    }

    @Override
    public String toString() {
        return data.size() + " item(s); console: " + console;
    }
}
