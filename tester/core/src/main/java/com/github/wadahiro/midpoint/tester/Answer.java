package com.github.wadahiro.midpoint.tester;

/**
 * What a driver call that computes something returned (search results, a generated value, a script's output),
 * together with its {@link Run}: the evaluations midPoint ran to compute it.
 */
public final class Answer<T> {

    private final T value;
    private final Run run;

    public Answer(T value, Run run) {
        this.value = value;
        this.run = run;
    }

    public T value() {
        return value;
    }

    public Run run() {
        return run;
    }

    @Override
    public String toString() {
        return value + " (" + run.operation() + ")";
    }
}
