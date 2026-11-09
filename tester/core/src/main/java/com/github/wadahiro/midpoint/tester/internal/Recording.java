package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.List;

/** The evaluations of one driver call, in the order they started (on every thread). */
public final class Recording {

    /** Resolves a record's configuration location; see {@link ConfigIndex}. */
    public interface Locator {
        com.github.wadahiro.midpoint.tester.Location locate(Record record);
    }

    final Locator locator;
    private final List<Record> records = new ArrayList<>();

    Recording(Locator locator) {
        this.locator = locator;
    }

    synchronized Record add(com.github.wadahiro.midpoint.tester.Evaluation.Kind kind, Record parent) {
        Record record = new Record(this, records.size() + 1, kind, parent);
        records.add(record);
        if (parent != null) {
            synchronized (parent.children) {
                parent.children.add(record);
            }
        }
        return record;
    }

    public synchronized List<Record> records() {
        return new ArrayList<>(records);
    }
}
