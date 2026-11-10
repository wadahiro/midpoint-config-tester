package com.github.wadahiro.midpoint.configtester;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.MultipleFailuresError;

import com.github.wadahiro.midpoint.tester.internal.Checkpoint;
import com.github.wadahiro.midpoint.tester.internal.Deployment;

/**
 * Lifecycle of {@link MidPointConfigTest}: loads the class's deployment (once per JVM and manifest), resets midPoint to
 * it before every test method and injects {@link MidPointConfigTester} parameters. A load that fails fails every test of the class
 * with a {@link DeploymentLoadException}.
 *
 * <p>A test that fails with an {@link AssertionError} (an AssertJ assertion on a view such as {@code user.roles()})
 * gets the mappings behind the values it read through the views appended to the message, the last read first; the
 * expected and actual values stay, for the IDE's diff; and the mappings that set an item of a user whose object it read
 * ({@code User.asObject()}) when the item's value appears in the failure. Off with
 * {@code -Dscenario.whyOnFailure=false}. During a test, AssertJ shows midPoint's values as {@link Describe} does
 * ({@code assignment of role Dept Sales}).</p>
 *
 * <p>A {@link Nested} class shares a prefix: its {@code @BeforeAll} methods (with
 * {@code @TestInstance(Lifecycle.PER_CLASS)}, a {@link MidPointConfigTester} parameter) run once, on a midPoint reset
 * to the deployment (or, in a nested class of a nested class, on the outer class's prefix), and each of its tests and
 * nested classes starts from midPoint as the prefix left it (a checkpoint, restored after each).</p>
 */
public class MidPointConfigTestExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback,
        AfterEachCallback, ParameterResolver, TestExecutionExceptionHandler {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(MidPointConfigTestExtension.class);
    private static final String DEPLOYMENT = "deployment";
    private static final String SCENARIO = "scenario";
    private static final String CONTAINER = "container";

    /** A nested class: its tester and the checkpoint after its prefix, taken when its first test or class starts. */
    private static final class Container {
        final MidPointConfigTester tester;
        Checkpoint checkpoint;

        Container(MidPointConfigTester tester) {
            this.tester = tester;
        }

        void childStarts() {
            if (checkpoint == null) {
                checkpoint = MidPointConfigTestLifecycle.checkpoint(tester);
            }
            MidPointConfigTestLifecycle.bind(tester);
        }

        void childEnded() {
            MidPointConfigTestLifecycle.restore(tester, checkpoint);
            MidPointConfigTestLifecycle.bind(tester);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!nested(context)) {
            context.getStore(NAMESPACE).put(DEPLOYMENT,
                    MidPointConfigTestLifecycle.load(context.getRequiredTestClass()));
            return;
        }
        Container outer = outerContainer(context);
        MidPointConfigTester tester;
        if (outer == null) {
            tester = MidPointConfigTestLifecycle.begin(deployment(context));
        } else {
            outer.childStarts();
            tester = outer.tester;
        }
        context.getStore(NAMESPACE).put(CONTAINER, new Container(tester));
        context.getStore(NAMESPACE).put(SCENARIO, tester);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (!nested(context)) {
            return;
        }
        Container container = context.getStore(NAMESPACE).get(CONTAINER, Container.class);
        if (container.checkpoint != null) {
            MidPointConfigTestLifecycle.release(container.tester, container.checkpoint);
        }
        Container outer = outerContainer(context);
        if (outer == null) {
            MidPointConfigTestLifecycle.end();
        } else {
            outer.childEnded();
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        Container container = outerContainer(context);
        if (container == null) {
            context.getStore(NAMESPACE).put(SCENARIO, MidPointConfigTestLifecycle.begin(deployment(context)));
        } else {
            container.childStarts();
            context.getStore(NAMESPACE).put(SCENARIO, container.tester);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Container container = outerContainer(context);
        if (container == null) {
            MidPointConfigTestLifecycle.end();
            return;
        }
        try {
            Deployment.checkNetwork("in this test");
        } finally {
            container.childEnded();
        }
    }

    private static boolean nested(ExtensionContext context) {
        return context.getTestClass().map(c -> AnnotationSupport.isAnnotated(c, Nested.class)).orElse(false)
                && context.getTestMethod().isEmpty();
    }

    /** The container of a test or of a nested class: its enclosing nested class's, none at the top. */
    private static Container outerContainer(ExtensionContext context) {
        return context.getParent().filter(MidPointConfigTestExtension::nested)
                .map(c -> c.getStore(NAMESPACE).get(CONTAINER, Container.class)).orElse(null);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == MidPointConfigTester.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        MidPointConfigTester tester = extensionContext.getStore(NAMESPACE).get(SCENARIO, MidPointConfigTester.class);
        if (tester == null) {
            throw new IllegalStateException("A MidPointConfigTester is injected into test methods only");
        }
        return tester;
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        throw MidPointConfigTestLifecycle.explained(context.getStore(NAMESPACE).get(SCENARIO, MidPointConfigTester.class),
                throwable);
    }

    /** The same failure (type kept where JUnit and the IDE read it) with another message. */
    static AssertionError withExplanations(AssertionError error, String message) {
        AssertionError rebuilt;
        if (error instanceof AssertionFailedError) {
            AssertionFailedError failed = (AssertionFailedError) error;
            rebuilt = new AssertionFailedError(message,
                    failed.isExpectedDefined() ? failed.getExpected().getValue() : null,
                    failed.isActualDefined() ? failed.getActual().getValue() : null, error.getCause());
            if (!failed.isExpectedDefined() && !failed.isActualDefined()) {
                rebuilt = new AssertionFailedError(message, error.getCause());
            }
        } else if (error instanceof MultipleFailuresError) {
            // soft assertions: the message already lists the failures, which stay reachable as the cause
            rebuilt = new AssertionError(message, error);
        } else {
            rebuilt = new AssertionError(message, error.getCause());
        }
        rebuilt.setStackTrace(error.getStackTrace());
        for (Throwable suppressed : error.getSuppressed()) {
            rebuilt.addSuppressed(suppressed);
        }
        return rebuilt;
    }

    private static Deployment deployment(ExtensionContext context) {
        for (ExtensionContext c = context; c != null; c = c.getParent().orElse(null)) {
            Deployment deployment = c.getStore(NAMESPACE).get(DEPLOYMENT, Deployment.class);
            if (deployment != null) {
                return deployment;
            }
        }
        throw new IllegalStateException("No deployment loaded for " + context.getDisplayName());
    }

    /** The manifest of the class, see {@link MidPointConfigTest#value()} and {@link AuthzMatrix#manifest()}. */
    static Path manifest(Class<?> testClass) {
        String value = AnnotationSupport.findAnnotation(testClass, MidPointConfigTest.class)
                .map(MidPointConfigTest::value)
                .orElse(AnnotationSupport.findAnnotation(testClass, AuthzMatrix.class).map(AuthzMatrix::manifest)
                        .orElse(""));
        if (value.isEmpty()) {
            value = System.getProperty("tester.manifest", "midpoint-config-tester.yaml");
        }
        if (value.startsWith("classpath:")) {
            String resource = value.substring("classpath:".length());
            URL url = testClass.getClassLoader().getResource(resource.startsWith("/") ? resource.substring(1) : resource);
            if (url == null || !"file".equals(url.getProtocol())) {
                throw new DeploymentLoadException("No manifest " + value + " on the test classpath (as a file)");
            }
            try {
                return Path.of(url.toURI());
            } catch (URISyntaxException e) {
                throw new DeploymentLoadException("Bad manifest URL " + url, e);
            }
        }
        return Path.of(value);
    }
}
