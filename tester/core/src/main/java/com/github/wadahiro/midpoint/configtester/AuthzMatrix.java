package com.github.wadahiro.midpoint.configtester;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The authorization matrix an {@link AuthzMatrixTest} checks: {@code classpath:authz-matrix.yaml} or a file path
 * (relative to the working directory).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface AuthzMatrix {

    String value();
}
