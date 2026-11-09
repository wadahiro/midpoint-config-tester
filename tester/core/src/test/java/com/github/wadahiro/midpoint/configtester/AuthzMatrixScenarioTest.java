package com.github.wadahiro.midpoint.configtester;

/**
 * The authorization matrix of the sample deployment {@code scenarios/access} ({@code authz-matrix.yaml}): what an
 * End user, an Approver, the Help desk and the IT manager may open, find, get, change and assign, one test per
 * cell, and its HTML report.
 */
@MidPointScenarios("classpath:scenarios/access/midpoint-config-tester.yaml")
@AuthzMatrix("classpath:scenarios/access/authz-matrix.yaml")
class AuthzMatrixScenarioTest extends AuthzMatrixTest {
}
