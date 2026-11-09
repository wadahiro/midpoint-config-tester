package com.github.wadahiro.midpoint.tester.internal;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;

import com.github.wadahiro.midpoint.tester.agent.TimeShift;

/**
 * Shifts the JVM's wall clock for {@code mp.time()}: instruments, with inlined Byte Buddy advice that adds
 * {@link TimeShift#shift()},
 * <ul>
 *   <li>{@code new Date()} (its {@code fastTime});</li>
 *   <li>{@code new GregorianCalendar()} / {@code (TimeZone, Locale)} and the JDK's calendar provider
 *       ({@code Calendar.getInstance()});</li>
 *   <li>the system {@code java.time} clock: {@code Clock.currentInstant()} (JDK 21; {@code Instant.now()} and the
 *       system clocks' {@code instant()} call it) or the system clocks' {@code instant()} (JDK 11, 17), and their
 *       {@code millis()};</li>
 *   <li>{@code System.currentTimeMillis()} calls in prism's {@code XmlTypeConverter} ({@code fromNow},
 *       {@code createXMLGregorianCalendar()}), replaced by {@link TimeShift#currentTimeMillis()}.</li>
 * </ul>
 * Groovy's {@code System.currentTimeMillis()} goes through {@code System}'s meta class, which {@link #groovy}
 * replaces. {@code System.currentTimeMillis()} called from Java is native and not shifted, nor is
 * {@code System.nanoTime()}. midPoint's own {@code Clock} reads {@code System.currentTimeMillis()}: it keeps its test
 * override, which {@link #sync} copies into the JDK shift, so both say the same time.
 *
 * {@link #install} checks the result on this JVM ({@link #probe}); without it (no agent, or a JDK where a source is
 * not shifted) only midPoint's clock moves, as before.
 */
final class TimeAgent {

    static final String XML_TYPE_CONVERTER = "com.evolveum.midpoint.prism.xml.XmlTypeConverter";

    private static String failure = "not installed";
    private static String groovyFailure;

    private TimeAgent() {
    }

    /** Null when the JDK's wall clock follows {@code mp.time()}, else why not. */
    static String failure() {
        return failure;
    }

    /** Instruments the JDK's time sources; {@link TimeShift} must be on the bootstrap class path. */
    static String install(Instrumentation instrumentation) {
        try {
            boolean currentInstant = hasMethod(Clock.class, "currentInstant");
            // loaded now, so that they are retransformed: the system clock's class loaded after the installation was
            // not transformed on JDK 21
            for (String name : List.of("java.time.Clock$SystemClock", "java.time.Clock$SystemInstantSource",
                    "sun.util.locale.provider.CalendarProviderImpl")) {
                try {
                    Class.forName(name, false, null);
                } catch (ClassNotFoundException e) {
                    // not on this JDK (SystemInstantSource is 17+)
                }
            }
            java.lang.reflect.Method shifted = TimeShift.class.getMethod("currentTimeMillis");
            List<Throwable> errors = new ArrayList<>();
            AgentBuilder.Identified.Extendable builder = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onError(String typeName, ClassLoader loader,
                                net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                            errors.add(throwable);
                        }
                    })
                    .ignore(none())
                    .type(named("java.util.Date"))
                    .transform((b, type, loader, module, domain) -> b.visit(Advice.to(DateAdvice.class)
                            .on(isConstructor().and(takesArguments(0)))))
                    .type(named("java.util.GregorianCalendar"))
                    .transform((b, type, loader, module, domain) -> b.visit(Advice.to(CalendarAdvice.class)
                            .on(isConstructor().and(takesArguments(TimeZone.class, Locale.class)))))
                    .type(named("sun.util.locale.provider.CalendarProviderImpl"))
                    .transform((b, type, loader, module, domain) -> b.visit(Advice.to(ProvidedCalendarAdvice.class)
                            .on(named("getInstance").and(takesArguments(TimeZone.class, Locale.class)))))
                    .type(named("java.time.Clock$SystemClock").or(named("java.time.Clock$SystemInstantSource")))
                    .transform((b, type, loader, module, domain) -> {
                        b = b.visit(Advice.to(MillisAdvice.class).on(named("millis").and(takesArguments(0))));
                        return currentInstant ? b
                                : b.visit(Advice.to(InstantAdvice.class).on(named("instant").and(takesArguments(0))));
                    });
            if (currentInstant) {
                builder = builder.type(named("java.time.Clock"))
                        .transform((b, type, loader, module, domain) -> b.visit(Advice.to(InstantAdvice.class)
                                .on(named("currentInstant").and(takesArguments(0)))));
            }
            builder.type(named(XML_TYPE_CONVERTER))
                    .transform((b, type, loader, module, domain) -> b.visit(MemberSubstitution.relaxed()
                            .method(named("currentTimeMillis").and(net.bytebuddy.matcher.ElementMatchers
                                    .isDeclaredBy(System.class)))
                            .replaceWith(shifted)
                            .on(isMethod().or(isConstructor()))))
                    .installOn(instrumentation);
            if (!errors.isEmpty()) {
                return failure = "Cannot instrument the JDK's time sources: " + errors.get(0);
            }
            return failure = probe(false);
        } catch (Throwable e) {
            return failure = "Cannot instrument the JDK's time sources: " + e;
        }
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every shifted source says real time + a test offset; null if so, else which do not. With
     * {@code midPointLoaded}, also prism's converter and Groovy (once midPoint's classes are there).
     */
    static String probe(boolean midPointLoaded) {
        long offset = 10L * 24 * 3600 * 1000;
        Map<String, Supplier<Long>> sources = new LinkedHashMap<>();
        sources.put("new Date()", () -> new Date().getTime());
        sources.put("Calendar.getInstance()", () -> Calendar.getInstance().getTimeInMillis());
        sources.put("new GregorianCalendar()", () -> new GregorianCalendar().getTimeInMillis());
        sources.put("Instant.now()", () -> Instant.now().toEpochMilli());
        sources.put("Clock.systemUTC().millis()", () -> Clock.systemUTC().millis());
        sources.put("Clock.systemDefaultZone().instant()", () -> Clock.systemDefaultZone().instant().toEpochMilli());
        sources.put("LocalDateTime.now()",
                () -> LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        sources.put("ZonedDateTime.now()", () -> ZonedDateTime.now().toInstant().toEpochMilli());
        if (midPointLoaded) {
            sources.put("XmlTypeConverter.createXMLGregorianCalendar()", () -> xmlTypeConverterNow());
            if (groovyFailure == null) {
                sources.put("Groovy System.currentTimeMillis()", () -> groovyNow());
            }
        }
        List<String> wrong = new ArrayList<>();
        long saved = TimeShift.offset();
        try {
            TimeShift.set(offset);
            for (Map.Entry<String, Supplier<Long>> source : sources.entrySet()) {
                long real = System.currentTimeMillis();
                long seen = source.getValue().get();
                if (Math.abs(seen - real - offset) > 60_000) {
                    wrong.add(source.getKey() + " (" + (seen - real) + " ms off the real time)");
                }
            }
        } catch (RuntimeException e) {
            wrong.add("the probe failed: " + e);
        } finally {
            TimeShift.set(saved);
        }
        return wrong.isEmpty() ? null : "The JDK's wall clock is not shifted through " + String.join(", ", wrong);
    }

    private static long xmlTypeConverterNow() {
        try {
            Object calendar = Class.forName(XML_TYPE_CONVERTER).getMethod("createXMLGregorianCalendar").invoke(null);
            return ((javax.xml.datatype.XMLGregorianCalendar) calendar).toGregorianCalendar().getTimeInMillis();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long groovyNow() {
        return ((Number) evaluateGroovy("System.currentTimeMillis()")).longValue();
    }

    private static Object evaluateGroovy(String script) {
        try {
            Class<?> shell = Class.forName("groovy.lang.GroovyShell");
            return shell.getMethod("evaluate", String.class).invoke(shell.getConstructor().newInstance(), script);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * Once midPoint's Groovy is loaded: Groovy's {@code System.currentTimeMillis()} (dynamic dispatch through
     * {@code System}'s meta class) follows the shift too. Then probes again, with prism and Groovy; null if all is
     * shifted.
     */
    static synchronized String groovy() {
        if (failure != null) {
            return failure;
        }
        try {
            evaluateGroovy("System.metaClass.static.currentTimeMillis = { -> "
                    + TimeShift.class.getName() + ".currentTimeMillis() }");
        } catch (RuntimeException e) {
            groovyFailure = "Groovy's System.currentTimeMillis() is not shifted: " + e;
        }
        failure = probe(true);
        if (failure == null && groovyFailure != null) {
            failure = groovyFailure;
        }
        if (failure != null) {
            TimeShift.set(0);
        }
        return failure;
    }

    /**
     * Copies midPoint's clock override (its {@code override}, a stopped clock, and {@code overrideOffset}: the same
     * fields on every line) into the JDK shift; nothing when the JDK's clock is not shifted.
     */
    static void sync(Object midPointClock) {
        if (failure != null) {
            TimeShift.set(0);
            return;
        }
        try {
            Field field = midPointClock.getClass().getDeclaredField("overrideOffset");
            field.setAccessible(true);
            Object value = field.get(midPointClock);
            long offset = value == null ? 0 : ((Number) value).longValue();
            Field fixed = midPointClock.getClass().getDeclaredField("override");
            fixed.setAccessible(true);
            Object stopped = fixed.get(midPointClock);
            if (stopped != null) {
                TimeShift.stop(((Number) stopped).longValue() + offset);
            } else {
                TimeShift.set(offset);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read midPoint's clock override", e);
        }
    }

    // ---- inlined advice: may refer to TimeShift (bootstrap) only ---------------------------------------------

    static final class DateAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.FieldValue(value = "fastTime", readOnly = false) long fastTime) {
            fastTime = TimeShift.millis(fastTime);
        }
    }

    static final class CalendarAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.This Calendar calendar) {
            long real = calendar.getTimeInMillis();
            long shifted = TimeShift.millis(real);
            if (shifted != real) {
                calendar.setTimeInMillis(shifted);
            }
        }
    }

    static final class ProvidedCalendarAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.Return Calendar calendar) {
            if (calendar != null) {
                long real = calendar.getTimeInMillis();
                long shifted = TimeShift.millis(real);
                if (shifted != real) {
                    calendar.setTimeInMillis(shifted);
                }
            }
        }
    }

    static final class MillisAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.Return(readOnly = false) long millis) {
            millis = TimeShift.millis(millis);
        }
    }

    static final class InstantAdvice {
        @Advice.OnMethodExit
        static void exit(@Advice.Return(readOnly = false) Instant instant) {
            instant = TimeShift.instant(instant);
        }
    }
}
