package com.github.wadahiro.midpoint.configtester;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

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

import com.github.wadahiro.midpoint.tester.internal.Deployment;

/**
 * Lifecycle of {@link MidPointScenarios}: loads the class's deployment (once per JVM and manifest), resets midPoint to
 * it before every test method and injects {@link Scenario} parameters. A load that fails fails every test of the class
 * with a {@link DeploymentLoadException}.
 *
 * <p>A test that fails with an {@link AssertionError} (an AssertJ assertion on a view such as {@code user.roles()})
 * gets the mappings behind the values it read through the views appended to the message, the last read first; the
 * expected and actual values stay, for the IDE's diff; and the mappings that set an item of a user whose object it read
 * ({@code User.object()}) when the item's value appears in the failure. Off with
 * {@code -Dscenario.whyOnFailure=false}. During a test, AssertJ shows midPoint's values as {@link Describe} does
 * ({@code assignment of role Dept Sales}).</p>
 */
public class ScenarioExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver,
        TestExecutionExceptionHandler {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ScenarioExtension.class);
    private static final String DEPLOYMENT = "deployment";
    private static final String SCENARIO = "scenario";

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getStore(NAMESPACE).put(DEPLOYMENT, Deployment.load(manifest(context.getRequiredTestClass())));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        Deployment deployment = deployment(context);
        // also after a test class of the PoC API, which resets to the bare startup state
        deployment.reset();
        Scenario scenario = new Scenario(deployment);
        context.getStore(NAMESPACE).put(SCENARIO, scenario);
        Scenario.setCurrent(scenario);
        Representations.use();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Scenario.setCurrent(null);
        Representations.reset();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Scenario.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Scenario scenario = extensionContext.getStore(NAMESPACE).get(SCENARIO, Scenario.class);
        if (scenario == null) {
            throw new IllegalStateException("A Scenario is injected into test methods only");
        }
        return scenario;
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        Scenario scenario = context.getStore(NAMESPACE).get(SCENARIO, Scenario.class);
        if (scenario == null || !(throwable instanceof AssertionError)
                || !Boolean.parseBoolean(System.getProperty("scenario.whyOnFailure", "true"))) {
            throw throwable;
        }
        String message = throwable.getMessage() != null ? throwable.getMessage() : "";
        // the assertions of the API already end with the mappings behind them
        String explanations = message.contains("\nMappings that set ") ? "" : scenario.explanations(throwable);
        if (explanations.isEmpty()) {
            throw throwable;
        }
        throw withExplanations((AssertionError) throwable, message + "\n\n" + explanations);
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
                Class.forName(className, false, ScenarioExtension.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
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

    /** The manifest of the class, see {@link MidPointScenarios#value()}. */
    static Path manifest(Class<?> testClass) {
        String value = AnnotationSupport.findAnnotation(testClass, MidPointScenarios.class)
                .map(MidPointScenarios::value).orElse("");
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
