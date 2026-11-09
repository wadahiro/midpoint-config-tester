package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.prism.PrismContext;

/** midPoint 4.4 line of the tester runtime: native repository (repo-sqale) configuration, schema, baseline and harness startup. */
public final class LineSupport {

    public static final String LINE = "4.4";

    static final String CONFIG_FILE = "tester-config.xml";

    /** From midPoint's config/sql/native-new of 4.4.11. */
    static final String[] SCHEMA_SCRIPTS = {
            "/tester/sql/postgres-new.sql", "/tester/sql/postgres-new-audit.sql", "/tester/sql/postgres-new-quartz.sql"
    };

    /**
     * Initial objects of 4.4 (config/initial-objects) that midPoint's services need: archetypes of manual provisioning,
     * operation request and approval cases, of certification, report, approval execution, validity and trigger scanner
     * tasks and of the bulk action tasks of asynchronous policy scripts; the Approver and Reviewer roles
     * (authorizations to complete one's own work items).
     */
    static final String[] BASELINE = {
            "/tester/baseline/021-archetype-system-role.xml",
            "/tester/baseline/023-archetype-manual-provisioning-case.xml",
            "/tester/baseline/024-archetype-operation-request.xml",
            "/tester/baseline/025-archetype-approval-case.xml",
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

    private LineSupport() {
    }

    /**
     * A container or bean value (e.g. {@code InformationType}) as one line of JSON, so that the normalized text
     * shows its content; null when prism cannot serialize it.
     */
    public static String serializeRealValue(Object value) {
        try {
            return PrismContext.get().jsonSerializer().serializeRealValue(value).strip().replaceAll("\\R\\s*", " ");
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /**
     * The repository type is {@code sqale}, as in 4.4's own test-config-new-repo.xml: 4.4's
     * {@code TestSqaleRepositoryBeanConfig} (which midPoint's test base classes need) does not match {@code native}.
     */
    static String config(String jdbcUrl, String user, String extensionDir, String connectorDir) {
        return "<configuration>\n"
                + "  <midpoint>\n"
                + "    <global><extensionDir>" + xml(extensionDir) + "</extensionDir></global>\n"
                + "    <repository>\n"
                + "      <type>sqale</type>\n"
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
            return Harness44.start();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start the midPoint Spring context", e);
        }
    }

    static LineDrivers drivers(Harness harness) {
        return new Drivers44((Harness44) harness);
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * The channel of the operation an expression is evaluated in (the lens context's, else the current task's), for the
     * recorder; null when unknown.
     */
    static String currentChannel() {
        com.evolveum.midpoint.model.api.context.ModelContext<?> context =
                com.evolveum.midpoint.model.common.expression.ModelExpressionThreadLocalHolder.getLensContext();
        if (context != null && context.getChannel() != null) {
            return context.getChannel();
        }
        com.evolveum.midpoint.task.api.Task task =
                com.evolveum.midpoint.model.common.expression.ModelExpressionThreadLocalHolder.getCurrentTask();
        return task != null ? task.getChannel() : null;
    }
}
