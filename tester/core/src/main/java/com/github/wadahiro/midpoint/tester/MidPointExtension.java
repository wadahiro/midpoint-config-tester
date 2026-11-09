package com.github.wadahiro.midpoint.tester;

import java.util.List;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

import com.github.wadahiro.midpoint.tester.internal.Golden;
import com.github.wadahiro.midpoint.tester.internal.GoldenResults;
import com.github.wadahiro.midpoint.tester.internal.LineSupport;
import com.github.wadahiro.midpoint.tester.internal.TesterRuntime;
import com.github.wadahiro.midpoint.tester.internal.Timing;

/**
 * Lifecycle of {@link MidPointTest}: shares one midPoint per JVM, resets it around each class and (by default) after
 * each test method, and injects {@link MidPoint} parameters.
 *
 * It also hands the runs of each test method (and of the class's {@code @BeforeAll} methods) to the golden results
 * ({@code golden.mode}, see {@code internal.Golden}), before the reset.
 */
public class MidPointExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback,
        ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(MidPointExtension.class);
    private static final String CLASS_STATE = "classState";
    private static final String GOLDEN = "golden";
    private static final String SETUP_FAILURE = "setupFailure";
    private static final String TIMING_REPORT = "timingReport";
    private static final String TIMING_MARK = "timingMark";

    @Override
    public void beforeAll(ExtensionContext context) {
        TesterRuntime runtime = TesterRuntime.get();
        long t = Timing.start();
        // also repairs whatever a previous class may have left behind
        runtime.resetToBaseline();
        runtime.takeJournal();
        // a step of the previous class's last test must not name this class's @BeforeAll runs
        runtime.api().step(null);
        Golden.ClassResults golden = Golden.start(context.getRequiredTestClass());
        if (golden != null) {
            context.getStore(NAMESPACE).put(GOLDEN, golden);
        }
        // written once, after the last test class
        context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(TIMING_REPORT,
                k -> (ExtensionContext.Store.CloseableResource) () -> Timing.writeReport(
                        TesterRuntime.get().workDir().resolve("tester"), LineSupport.LINE),
                ExtensionContext.Store.CloseableResource.class);
        Timing.end("ext.beforeAll (reset to baseline)", t);
        context.getStore(NAMESPACE).put(TIMING_MARK, Timing.start());
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        TesterRuntime runtime = TesterRuntime.get();
        ExtensionContext.Store store = classContext(context).getStore(NAMESPACE);
        if (store.get(CLASS_STATE) == null) {
            Long mark = store.get(TIMING_MARK, Long.class);
            if (mark != null) {
                Timing.end("class.@BeforeAll methods", mark);
            }
            // the class's @BeforeAll methods have run by now: this is the state every method starts from
            long t = Timing.start();
            String failure = testFinished(context, GoldenResults.BEFORE_ALL, null);
            Timing.end("ext.testFinished (golden)", t);
            if (failure != null) {
                store.put(SETUP_FAILURE, failure);
            }
            t = Timing.start();
            store.put(CLASS_STATE, runtime.snapshot());
            Timing.end("ext.classSnapshot", t);
        } else {
            // what ran between the tests (e.g. a @AfterEach of the previous one) belongs to no test
            runtime.takeJournal();
        }
        runtime.login(TesterRuntime.ADMINISTRATOR);
        runtime.clearNotifications();
        runtime.api().step(null);
        context.getStore(NAMESPACE).put(TIMING_MARK, Timing.start());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Long mark = context.getStore(NAMESPACE).get(TIMING_MARK, Long.class);
        if (mark != null) {
            Timing.end("test.body (incl. @BeforeEach/@AfterEach)", mark);
        }
        String failure;
        try {
            // before the reset: identities and normalization use the configuration index of the test
            long t = Timing.start();
            failure = testFinished(context, testKey(context), context.getExecutionException().orElse(null));
            Timing.end("ext.testFinished (golden)", t);
        } finally {
            if (resetMode(context) == ResetMode.PER_METHOD) {
                long t = Timing.start();
                TesterRuntime.get().reset(classState(context));
                Timing.end("ext.afterEach reset", t);
            }
        }
        if (failure != null) {
            throw new AssertionError(failure);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        TesterRuntime runtime = TesterRuntime.get();
        long t = Timing.start();
        runtime.resetToBaseline();
        runtime.release(classState(context));
        Timing.end("ext.afterAll reset to baseline", t);
        ExtensionContext.Store store = classContext(context).getStore(NAMESPACE);
        t = Timing.start();
        String missing = Golden.classFinished(store.get(GOLDEN, Golden.ClassResults.class), runtime.workDir());
        Timing.end("golden.classFinished (write)", t);
        String failure = store.get(SETUP_FAILURE, String.class);
        if (failure != null || missing != null) {
            throw new AssertionError(failure != null ? failure : missing);
        }
    }

    /** Gives the runs since the last call to the golden results; the golden failure message or null. */
    private static String testFinished(ExtensionContext context, String test, Throwable testFailure) {
        List<Run> runs = TesterRuntime.get().takeJournal();
        return Golden.testFinished(classContext(context).getStore(NAMESPACE).get(GOLDEN, Golden.ClassResults.class),
                test, runs, testFailure);
    }

    /**
     * The method name, plus the invocation's display name for parameterized ({@code [1] ...}) and repeated
     * ({@code repetition 1 of 10}) tests, so that every invocation has results of its own.
     */
    private static String testKey(ExtensionContext context) {
        String method = context.getRequiredTestMethod().getName();
        String displayName = context.getDisplayName();
        return displayName.startsWith("[") || displayName.startsWith("repetition ") ? method + " " + displayName
                : method;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == MidPoint.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return TesterRuntime.get().api();
    }

    private static TesterRuntime.State classState(ExtensionContext context) {
        return classContext(context).getStore(NAMESPACE).get(CLASS_STATE, TesterRuntime.State.class);
    }

    private static ResetMode resetMode(ExtensionContext context) {
        return AnnotationSupport.findAnnotation(context.getRequiredTestClass(), MidPointTest.class)
                .map(MidPointTest::reset)
                .orElse(ResetMode.PER_METHOD);
    }

    /** The class-level context (a parameterized invocation's parent is the test template, not the class). */
    private static ExtensionContext classContext(ExtensionContext context) {
        ExtensionContext classContext = context;
        while (classContext.getTestMethod().isPresent()) {
            classContext = classContext.getParent().orElseThrow();
        }
        return classContext;
    }
}
