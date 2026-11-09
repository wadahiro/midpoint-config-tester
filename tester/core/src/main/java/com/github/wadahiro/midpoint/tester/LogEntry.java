package com.github.wadahiro.midpoint.tester;

/** One message logged during a test (INFO and up), as the tester captured it. */
public final class LogEntry {

    private final String level;
    private final String logger;
    private final String message;
    private final String error;

    public LogEntry(String level, String logger, String message, String error) {
        this.level = level;
        this.logger = logger;
        this.message = message;
        this.error = error;
    }

    /** {@code INFO}, {@code WARN} or {@code ERROR}. */
    public String level() {
        return level;
    }

    public String logger() {
        return logger;
    }

    /** The message with its arguments filled in. */
    public String message() {
        return message;
    }

    /** The exception logged with it ({@code class: message}), or null. */
    public String error() {
        return error;
    }

    @Override
    public String toString() {
        return level + " " + message + (error != null ? " (" + error + ")" : "");
    }
}
