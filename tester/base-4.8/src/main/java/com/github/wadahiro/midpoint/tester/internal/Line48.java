package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.prism.PrismContext;

/**
 * The parts of {@code LineSupport} that the 4.8+ lines (native repository) share: configuration, schema scripts and
 * baseline file names (the files themselves are the line's), harness startup. Each line's {@code LineSupport} refers
 * to these and adds its name and the adapters for the midPoint API that differs.
 */
final class Line48 {

    static final String CONFIG_FILE = "tester-config.xml";

    /** From midPoint's config/sql/native of the release the module is built against. */
    static final String[] SCHEMA_SCRIPTS = {
            "/tester/sql/postgres.sql", "/tester/sql/postgres-audit.sql", "/tester/sql/postgres-quartz.sql"
    };

    /**
     * Initial objects (config/initial-objects; each line module has the copies of its release) that midPoint's
     * services need: archetypes of manual provisioning,
     * operation request and approval cases, of certification, report, approval execution, validity and trigger scanner
     * tasks and of the bulk action tasks of asynchronous policy scripts; the Approver and Reviewer roles
     * (authorizations to complete one's own work items).
     */
    static final String[] BASELINE = {
            "/tester/baseline/021-archetype-system-role.xml",
            "/tester/baseline/023-archetype-manual-provisioning-case.xml",
            "/tester/baseline/024-archetype-operation-request.xml",
            "/tester/baseline/025-archetype-approval-case.xml",
            "/tester/baseline/027-archetype-correlation-case.xml",
            "/tester/baseline/041-role-approver.xml",
            "/tester/baseline/042-role-reviewer.xml",
            "/tester/baseline/507-archetype-task-report.xml",
            "/tester/baseline/508-archetype-task-single-bulk-action.xml",
            "/tester/baseline/509-archetype-task-iterative-bulk-action.xml",
            "/tester/baseline/511-archetype-task-report-export-classic.xml",
            "/tester/baseline/520-archetype-task-certification.xml",
            "/tester/baseline/521-archetype-task-approval.xml",
            "/tester/baseline/530-archetype-task-validity.xml",
            "/tester/baseline/531-archetype-task-trigger.xml"
    };

    private Line48() {
    }

    /** See {@code LineSupport.serializeRealValue}. */
    static String serializeRealValue(Object value) {
        try {
            return PrismContext.get().jsonSerializer().serializeRealValue(value).strip().replaceAll("\\R\\s*", " ");
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static String config(String jdbcUrl, String user, String extensionDir, String connectorDir) {
        return "<configuration>\n"
                + "  <midpoint>\n"
                + "    <global><extensionDir>" + xml(extensionDir) + "</extensionDir></global>\n"
                + "    <repository>\n"
                + "      <type>native</type>\n"
                + "      <database>postgresql</database>\n"
                + "      <jdbcUrl>" + xml(jdbcUrl) + "</jdbcUrl>\n"
                + "      <jdbcUsername>" + xml(user) + "</jdbcUsername>\n"
                + "      <jdbcPassword></jdbcPassword>\n"
                + "    </repository>\n"
                + "    <audit><auditService>"
                + "<auditServiceFactoryClass>com.evolveum.midpoint.repo.sqale.audit.SqaleAuditServiceFactory</auditServiceFactoryClass>"
                + "</auditService></audit>\n"
                + "    <taskManager><jdbcJobStore>false</jdbcJobStore><testMode>true</testMode></taskManager>\n"
                + "    <icf><scanClasspath>true</scanClasspath><scanDirectory>" + xml(connectorDir) + "</scanDirectory></icf>\n"
                + "    <keystore>\n"
                + "      <keyStorePath>${midpoint.home}/keystore.jceks</keyStorePath>\n"
                + "      <keyStorePassword>changeit</keyStorePassword>\n"
                + "      <encryptionKeyAlias>default</encryptionKeyAlias>\n"
                + "    </keystore>\n"
                + "  </midpoint>\n"
                + "</configuration>\n";
    }

    static Harness startHarness() {
        Baseline.defaultLogging();
        // ctx-configuration-test.xml loads this file from midPoint home
        System.setProperty("test.config.file", CONFIG_FILE);
        try {
            return Harness48.start();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start the midPoint Spring context", e);
        }
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * The channel of the operation an expression is evaluated in (the lens context's, else the current task's), for the
     * recorder; null when unknown.
     */
    static String currentChannel() {
        Object environment =
                com.evolveum.midpoint.repo.common.expression.ExpressionEnvironmentThreadLocalHolder.getExpressionEnvironment();
        if (environment instanceof com.evolveum.midpoint.model.common.expression.ModelExpressionEnvironment) {
            // raw: the environment's type parameters differ between 4.8 and 4.9+
            com.evolveum.midpoint.model.api.context.ModelContext<?> context =
                    ((com.evolveum.midpoint.model.common.expression.ModelExpressionEnvironment) environment)
                            .getLensContext();
            if (context != null && context.getChannel() != null) {
                return context.getChannel();
            }
        }
        com.evolveum.midpoint.task.api.Task task =
                com.evolveum.midpoint.repo.common.expression.ExpressionEnvironmentThreadLocalHolder.getCurrentTask();
        return task != null ? task.getChannel() : null;
    }
}
