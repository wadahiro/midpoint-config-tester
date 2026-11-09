package com.github.wadahiro.midpoint.tester.check;

import com.github.wadahiro.midpoint.tester.Identity;

/**
 * One result of a static check. Identified by {@link #identity()}: the owner object and the element path, as the
 * tester identifies evaluations ({@link Identity}); {@link #file()} and {@link #line()} are for display only.
 */
public final class Finding {

    /** Which check found it. */
    public enum Check {
        /** Groovy compilation on the line's classpath. */
        COMPILE("Groovy compilation"),
        /** Parsing with the line's prism, strict (as an import without compatMode) and in compatibility mode. */
        SCHEMA("Schema (strict and compatibility parsing)"),
        /** ninja verify's upgrade validator ({@code ObjectUpgradeValidator}, 4.4+). */
        UPGRADE("Upgrade validator (ninja verify)"),
        /** Configuration lint: duplicate mapping names, identities by position, unknown evaluators. */
        LINT("Configuration lint");

        public final String title;

        Check(String title) {
            this.title = title;
        }
    }

    public enum Severity {
        /** midPoint of this line fails on it (compilation, import, evaluation, a critical upgrade item). */
        ERROR,
        /** Accepted, but likely wrong or soon not accepted. */
        WARNING,
        /** Advice. */
        NOTE
    }

    private final Check check;
    private final Severity severity;
    private final String rule;
    private final Identity identity;
    private final String file;
    private final int line;
    private final String message;

    Finding(Check check, Severity severity, String rule, Identity identity, String file, int line, String message) {
        this.check = check;
        this.severity = severity;
        this.rule = rule;
        this.identity = identity;
        this.file = file;
        this.line = line;
        this.message = message;
    }

    public Check check() {
        return check;
    }

    public Severity severity() {
        return severity;
    }

    /**
     * What exactly: {@code groovy}; {@code unreadable}, {@code strict}, {@code warning}; the validator's priority
     * ({@code CRITICAL}, {@code NECESSARY}, {@code OPTIONAL}) or status; {@code no-evaluator},
     * {@code duplicate-mapping-name}, {@code ordinal}.
     */
    public String rule() {
        return rule;
    }

    /** The place, element or object; null when not even the object is known (a file that is no readable XML). */
    public Identity identity() {
        return identity;
    }

    /** For display: the file, relative to the checked directory. */
    public String file() {
        return file;
    }

    /** For display: the line of the element's start tag; 0 when unknown. */
    public int line() {
        return line;
    }

    public String message() {
        return message;
    }

    /** {@code identity} or the file. */
    public String where() {
        return identity != null ? identity.toString() : file;
    }

    /** For display: {@code file:line}. */
    public String position() {
        return line > 0 ? file + ":" + line : file;
    }

    /** {@code ERROR COMPILE groovy <identity>: <message> (file:line)}. */
    @Override
    public String toString() {
        return severity + " " + check + " " + rule + " " + where() + ": " + message + " (" + position() + ")";
    }
}
