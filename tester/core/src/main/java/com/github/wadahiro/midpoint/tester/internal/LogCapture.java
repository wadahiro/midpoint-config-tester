package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.LoggerFactory;

import com.github.wadahiro.midpoint.tester.LogEntry;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

/**
 * What the configuration's scripts (and the loggers the manifest adds) log during a test, INFO and up: scripts' {@code
 * log} is midPoint's {@code com.evolveum.midpoint.expression} logger. midPoint resets logback when it applies the
 * system configuration's logging, so the appender is attached again before every test ({@link #start}).
 */
public final class LogCapture {

    /** The logger of scripts' {@code log} ({@code LogExpressionFunctions}) on every line. */
    public static final String SCRIPTS = "com.evolveum.midpoint.expression";
    private static final String NAME = "tester-log-capture";

    private static final List<LogEntry> ENTRIES = new CopyOnWriteArrayList<>();
    private static Appender appender;
    private static String unavailable;

    private LogCapture() {
    }

    /** Captures the scripts' logger and these, from INFO on; forgets what was captured. */
    public static synchronized void start(Collection<String> loggers) {
        ENTRIES.clear();
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext)) {
            unavailable = "logging is not logback (" + LoggerFactory.getILoggerFactory().getClass().getName() + ")";
            return;
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        if (appender == null || appender.getContext() != context || !appender.isStarted()) {
            appender = new Appender();
            appender.setName(NAME);
            appender.setContext(context);
            appender.start();
        }
        List<String> names = new ArrayList<>();
        names.add(SCRIPTS);
        names.addAll(loggers);
        for (String name : names) {
            Logger logger = context.getLogger(name);
            if (!logger.isInfoEnabled()) {
                logger.setLevel(Level.INFO);
            }
            if (logger.getAppender(NAME) == null) {
                logger.addAppender(appender);
            }
        }
    }

    /** How many entries were captured so far (a checkpoint of the test). */
    static int count() {
        return ENTRIES.size();
    }

    /** Forgets the entries after the first {@code count} (a checkpoint of the test, restored). */
    static synchronized void keep(int count) {
        while (ENTRIES.size() > count) {
            ENTRIES.remove(ENTRIES.size() - 1);
        }
    }

    /** What was captured since {@link #start}, in the order logged. */
    public static List<LogEntry> entries() {
        if (unavailable != null) {
            throw new IllegalStateException("The tester cannot capture logs: " + unavailable);
        }
        return List.copyOf(ENTRIES);
    }

    private static final class Appender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            if (!event.getLevel().isGreaterOrEqual(Level.INFO)) {
                return;
            }
            IThrowableProxy t = event.getThrowableProxy();
            ENTRIES.add(new LogEntry(event.getLevel().toString(), event.getLoggerName(), event.getFormattedMessage(),
                    t == null ? null : t.getClassName() + ": " + t.getMessage()));
        }
    }
}
