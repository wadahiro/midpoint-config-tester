package com.github.wadahiro.midpoint.configtester;

import java.util.Locale;

import com.evolveum.midpoint.common.LocalizationService;
import com.evolveum.midpoint.util.LocalizableMessage;
import com.evolveum.midpoint.util.exception.CommonException;
import com.github.wadahiro.midpoint.tester.MidPoint;
import com.github.wadahiro.midpoint.tester.OperationFailed;

/**
 * An operation midPoint refused ({@link MidPointConfigTester#expectFailure}): its message and what kind of refusal it was.
 * Nothing of the operation was executed.
 */
public final class Failure {

    private final OperationFailed failure;
    private final MidPoint mp;

    Failure(OperationFailed failure, MidPoint mp) {
        this.failure = failure;
        this.mp = mp;
    }

    /**
     * midPoint's message (with the tester's description of the operation). A policy constraint's
     * {@code presentation/message} is in it untranslated: its {@code fallbackMessage}; for the text the user sees, in
     * their language, {@link #userMessage(Locale)}.
     */
    public String message() {
        return failure.getMessage();
    }

    /**
     * The message midPoint has for the user (its exception's user-friendly message: a policy constraint's
     * {@code presentation/message}, midPoint's own text of the constraint without one), translated into the language
     * as the GUI translates it for a user of that locale: by midPoint's localization (its bundles and the deployment's,
     * {@code config.localization}; a key none has a text for is its {@code fallbackMessage}); the messages of several
     * constraints joined. Without a user-friendly
     * message, midPoint's message.
     */
    public String userMessage(Locale locale) {
        LocalizableMessage message = userFriendlyMessage();
        if (message == null) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            return cause.getMessage();
        }
        return mp.bean(LocalizationService.class).translate(message, locale);
    }

    /** {@link #userMessage(Locale)} of a language tag ({@code "ja"}, {@code "en"}). */
    public String userMessage(String languageTag) {
        return userMessage(Locale.forLanguageTag(languageTag));
    }

    /** midPoint refused it by a policy rule (an exclusion, a password policy...); fails with the error if not. */
    public Failure isPolicyViolation() {
        return is(causedBy("PolicyViolationException"), "a policy violation");
    }

    /** midPoint refused an object of a name (or OID) that another object has. */
    public Failure isAlreadyExists() {
        return is(causedBy("ObjectAlreadyExistsException"), "an object that already exists");
    }

    /** The acting user was not authorized. */
    public Failure isNotAuthorized() {
        return is(causedBy("SecurityViolationException") || causedBy("AuthorizationException"), "not authorized");
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

    private Failure is(boolean holds, String what) {
        if (!holds) {
            throw new AssertionError("Expected the failure to be " + what + ", but it is: " + message(), failure);
        }
        return this;
    }

    private LocalizableMessage userFriendlyMessage() {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CommonException && ((CommonException) t).getUserFriendlyMessage() != null) {
                return ((CommonException) t).getUserFriendlyMessage();
            }
        }
        if (failure.run() != null) {
            for (Throwable t = failure.run().failure(); t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof CommonException && ((CommonException) t).getUserFriendlyMessage() != null) {
                    return ((CommonException) t).getUserFriendlyMessage();
                }
            }
        }
        return null;
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
