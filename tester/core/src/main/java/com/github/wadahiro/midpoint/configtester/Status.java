package com.github.wadahiro.midpoint.configtester;

/**
 * Whether an account is enabled on its system ({@link Account#status()}):
 * {@code assertThat(ldap.account(JOHN).status()).isEqualTo(Status.ENABLED)}.
 */
public enum Status {
    ENABLED, DISABLED
}
