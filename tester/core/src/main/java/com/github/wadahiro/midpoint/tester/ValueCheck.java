package com.github.wadahiro.midpoint.tester;

import java.util.Map;
import java.util.stream.Collectors;

/** A value checked against a value policy ({@link MidPoint#validateValue}): each limitation and whether it held. */
public final class ValueCheck {

    private final Map<String, Boolean> limitations;
    private final Run run;

    public ValueCheck(Map<String, Boolean> limitations, Run run) {
        this.limitations = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(limitations));
        this.run = run;
    }

    public boolean isValid() {
        return !limitations.containsValue(false);
    }

    /**
     * Limitation name to whether the value meets it. A {@code checkExpression} is named by its display label, or
     * {@code ValuePolicy.checkExpression} without one.
     */
    public Map<String, Boolean> limitations() {
        return limitations;
    }

    /** The names of the limitations the value does not meet. */
    public java.util.List<String> failed() {
        return limitations.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).sorted()
                .collect(Collectors.toList());
    }

    /** The evaluations, e.g. of the policy's {@code checkExpression}s. */
    public Run run() {
        return run;
    }

    @Override
    public String toString() {
        return (isValid() ? "valid" : "invalid " + failed());
    }
}
