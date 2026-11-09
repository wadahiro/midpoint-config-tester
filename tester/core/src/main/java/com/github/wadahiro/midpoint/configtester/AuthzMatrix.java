package com.github.wadahiro.midpoint.configtester;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A class that checks the deployment's authorization matrix, cell by cell ({@link AuthzMatrixTest}): the matrix is
 * {@code classpath:authz-matrix.yaml} or a file path (relative to the working directory). The annotation loads the
 * deployment as {@link MidPointConfigTest} does, of the project's manifest unless {@link #manifest()} names another:
 *
 * <pre>{@code
 * @AuthzMatrix("classpath:authz-matrix.yaml")
 * class AuthzMatrixCheck extends AuthzMatrixTest {
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(MidPointConfigTestExtension.class)
public @interface AuthzMatrix {

    /** The matrix. */
    String value();

    /** The manifest, as {@link MidPointConfigTest#value()}; empty: the project's. */
    String manifest() default "";
}
