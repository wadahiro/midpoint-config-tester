package com.github.wadahiro.midpoint.tester;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a JUnit 5 test class that runs against a real midPoint (see {@link MidPoint}).
 *
 * One midPoint per JVM is shared by all test classes; isolation comes from resetting it (database snapshot, midPoint's
 * in-memory state and the target systems), which takes a few tens of milliseconds:
 * <ul>
 *   <li>before each class: back to the bare system (system configuration, administrator, the archetypes midPoint's
 *       services need);</li>
 *   <li>after each test method ({@link ResetMode#PER_METHOD}, the default): back to the state at the start of the
 *       first test method, i.e. after the class's {@code @BeforeAll} methods (import configuration there).</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(MidPointExtension.class)
public @interface MidPointTest {

    ResetMode reset() default ResetMode.PER_METHOD;
}
