package com.github.wadahiro.midpoint.configtester;

import com.github.wadahiro.midpoint.tester.internal.Checkpoint;
import com.github.wadahiro.midpoint.tester.internal.Deployment;

/**
 * The lifecycle of a test over a deployment, as {@link MidPointConfigTestExtension} runs it for JUnit: for another
 * test framework's integration (the Kotest layer), not for tests. {@link #load} once per test class, {@link #begin}
 * before each test (midPoint reset to the deployment, the {@link MidPointConfigTester}), {@link #end} after it,
 * {@link #explained} for a failure; {@link #checkpoint} and {@link #restore} for a point in the middle of a test.
 */
public final class MidPointConfigTestLifecycle {

    private MidPointConfigTestLifecycle() {
    }

    /** The deployment of the class's manifest ({@link MidPointConfigTest}), loaded once per JVM and manifest. */
    public static Deployment load(Class<?> testClass) {
        return Deployment.load(MidPointConfigTestExtension.manifest(testClass));
    }

    /** midPoint reset to the deployment, and the tester of the test that starts now. */
    public static MidPointConfigTester begin(Deployment deployment) {
        // also after a test class of the PoC API, which resets to the bare startup state
        deployment.reset();
        MidPointConfigTester tester = new MidPointConfigTester(deployment);
        bind(tester);
        Representations.use();
        return tester;
    }

    /**
     * Makes the tester the current one of this thread (what AssertJ's representation and the views read); a framework
     * that resumes a test on another thread calls it there.
     */
    public static void bind(MidPointConfigTester tester) {
        MidPointConfigTester.setCurrent(tester);
    }

    /** After the test: fails if midPoint tried to reach another machine during it. */
    public static void end() {
        MidPointConfigTester.setCurrent(null);
        Representations.reset();
        Deployment.checkNetwork("in this test");
    }

    /**
     * The failure to report: an {@link AssertionError} with the mappings behind the values the test read appended
     * (off with {@code -Dscenario.whyOnFailure=false}), else the failure itself.
     */
    public static Throwable explained(MidPointConfigTester tester, Throwable throwable) {
        if (tester == null || !(throwable instanceof AssertionError)
                || !Boolean.parseBoolean(System.getProperty("scenario.whyOnFailure", "true"))) {
            return throwable;
        }
        String message = throwable.getMessage() != null ? throwable.getMessage() : "";
        // the assertions of the API already end with the mappings behind them
        String explanations = message.contains("\nMappings that set ") ? "" : tester.explanations(throwable);
        if (explanations.isEmpty()) {
            return throwable;
        }
        return MidPointConfigTestExtension.withExplanations((AssertionError) throwable, message + "\n\n" + explanations);
    }

    /** midPoint as the test has it now ({@link Deployment#checkpoint()}). */
    public static Checkpoint checkpoint(MidPointConfigTester tester) {
        return tester.deployment().checkpoint();
    }

    /** Back to the checkpoint ({@link Deployment#restore(Checkpoint)}). */
    public static void restore(MidPointConfigTester tester, Checkpoint checkpoint) {
        tester.deployment().restore(checkpoint);
    }

    public static void release(MidPointConfigTester tester, Checkpoint checkpoint) {
        tester.deployment().release(checkpoint);
    }

    /** midPoint's activity so far (driver calls, API calls, task starts): a container's work between its tests. */
    public static long activity(MidPointConfigTester tester) {
        return tester.deployment().activity();
    }

    /** midPoint's values in AssertJ's messages, when AssertJ is on the classpath (it is, through the tester). */
    private static final class Representations {

        private static final boolean ASSERTJ = present("org.assertj.core.api.Assertions");

        static void use() {
            if (ASSERTJ) {
                org.assertj.core.api.Assertions.useRepresentation(
                        new com.github.wadahiro.midpoint.configtester.assertj.MidPointRepresentation());
            }
        }

        static void reset() {
            if (ASSERTJ) {
                org.assertj.core.api.Assertions.useDefaultRepresentation();
            }
        }

        private static boolean present(String className) {
            try {
                Class.forName(className, false, MidPointConfigTestLifecycle.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
    }

}
