package com.github.wadahiro.midpoint.configtester;

import com.github.wadahiro.midpoint.tester.OperationFailed;

/**
 * An operation midPoint refused ({@link MidPointConfigTester#expectFailure}): its message and what kind of refusal it was.
 * Nothing of the operation was executed.
 */
public final class Failure {

    private final OperationFailed failure;

    Failure(OperationFailed failure) {
        this.failure = failure;
    }

    /** midPoint's message (with the tester's description of the operation). */
    public String message() {
        return failure.getMessage();
    }

    /** midPoint refused it by a policy rule (an exclusion, a password policy...). */
    public boolean isPolicyViolation() {
        return causedBy("PolicyViolationException");
    }

    /** The acting user was not authorized. */
    public boolean isNotAuthorized() {
        return causedBy("SecurityViolationException") || causedBy("AuthorizationException");
    }

    public Failure hasMessageContaining(String text) {
        if (message() == null || !message().contains(text)) {
            throw new AssertionError("Expected the failure's message to contain '" + text + "', but it is: "
                    + message(), failure);
        }
        return this;
    }

    /** The tester's exception, with midPoint's as the cause. */
    public OperationFailed exception() {
        return failure;
    }

    @Override
    public String toString() {
        return message();
    }

    private boolean causedBy(String simpleName) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getClass().getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        if (failure.run() != null && failure.run().failure() != null) {
            for (Throwable t = failure.run().failure(); t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t.getClass().getSimpleName().equals(simpleName)) {
                    return true;
                }
            }
        }
        // a policy violation that midPoint records in the result only
        String message = failure.getMessage();
        return message != null && message.contains(simpleName);
    }
}
