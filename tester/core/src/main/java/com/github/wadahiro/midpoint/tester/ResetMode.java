package com.github.wadahiro.midpoint.tester;

/** When {@link MidPointTest} resets midPoint within a test class. */
public enum ResetMode {

    /** After every test method: each method starts from the state left by the class's {@code @BeforeAll}. */
    PER_METHOD,

    /**
     * Only around the class: methods see each other's changes, as in midPoint's own integration tests. Use it with
     * {@code @TestMethodOrder}, since JUnit does not run methods in declaration order by default.
     */
    PER_CLASS
}
