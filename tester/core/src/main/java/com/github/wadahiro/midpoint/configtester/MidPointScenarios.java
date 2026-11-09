package com.github.wadahiro.midpoint.configtester;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A class of scenario tests over one deployment: the deployment of the manifest is loaded once per JVM (midPoint's
 * post-initial import of the config directories, see {@code docs/next-design.md}), and every test method starts from
 * it, with a {@link Scenario} parameter.
 *
 * <pre>{@code
 * @MidPointScenarios("src/test/resources/midpoint-config-tester.yaml")
 * class JoinerTest {
 *     @Test
 *     void joiner(Scenario s) {
 *         s.source("HR").add("1001", Map.of("firstName", "John", "lastName", "Doe", "department", "Sales"));
 *         s.task("Reconcile HR").run();
 *         s.user("e1001").exists().hasFullName("John Doe").hasRole("Dept Sales");
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(ScenarioExtension.class)
public @interface MidPointScenarios {

    /**
     * The manifest: a path (relative to the working directory, i.e. the Maven module), or {@code classpath:<path>} for
     * a manifest among the test resources. Empty: the system property {@code tester.manifest}, else
     * {@code midpoint-config-tester.yaml} in the working directory.
     */
    String value() default "";
}
