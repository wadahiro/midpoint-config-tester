package com.github.wadahiro.midpoint.tester;

/**
 * A driver call that midPoint reported as failed. The evaluations up to the failure are in {@link #run()}, e.g. to
 * see which mapping threw:
 *
 * <pre>{@code
 * OperationFailed failed = assertThrows(OperationFailed.class, () -> mp.addObject(user));
 * assertThat(failed.run().mapping("employee-number").error()).hasMessageContaining("...");
 * }</pre>
 */
public final class OperationFailed extends AssertionError {

    private static final long serialVersionUID = 1L;

    private final transient Run run;

    public OperationFailed(String message, Run run, Throwable cause) {
        super(message, cause);
        this.run = run;
    }

    public Run run() {
        return run;
    }
}
