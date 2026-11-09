package com.github.wadahiro.midpoint.tester.internal;

import com.evolveum.midpoint.prism.PrismContext;

/** midPoint 4.0 line of the tester runtime: old (Hibernate) repository on PostgreSQL, schema, baseline, harness startup. */
public final class LineSupport {

    public static final String LINE = "4.0";

    /** 4.0's ctx-configuration-test.xml always loads this file name from midPoint home. */
    static final String CONFIG_FILE = "test-config.xml";

    /** config/sql/postgresql-4.0-all.sql of midPoint 4.0.3, unchanged (it runs on PostgreSQL 16). */
    static final String[] SCHEMA_SCRIPTS = { "/tester/sql/postgresql-4.0-all.sql" };

    /**
     * Initial objects of 4.0 (config/initial-objects) that midPoint's services need: archetypes of manual provisioning,
     * operation request and approval cases; the Approver and Reviewer roles (authorizations to complete one's own work
     * items). 4.0 has no task archetypes.
     */
    static final String[] BASELINE = {
            "/tester/baseline/021-archetype-system-role.xml",
            "/tester/baseline/023-archetype-manual-provisioning-case.xml",
            "/tester/baseline/024-archetype-operation-request.xml",
            "/tester/baseline/025-archetype-approval-case.xml",
            "/tester/baseline/041-role-approver.xml",
            "/tester/baseline/042-role-reviewer.xml"
    };

    private LineSupport() {
    }

    /**
     * A container or bean value (e.g. {@code InformationType}) as one line of JSON, so that the normalized text
     * shows its content; null when prism cannot serialize it.
     */
    /** 4.0 has no {@code PrismContext.get()}: the harness's, once midPoint started. */
    private static PrismContext prismContext;

    public static String serializeRealValue(Object value) {
        try {
            return prismContext.jsonSerializer().serializeRealValue(value).strip().replaceAll("\\R\\s*", " ");
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static String config(String jdbcUrl, String user, String extensionDir, String connectorDir) {
        return "<configuration>\n"
                + "  <midpoint>\n"
                + "    <global><extensionDir>" + xml(extensionDir) + "</extensionDir></global>\n"
                + "    <repository>\n"
                + "      <repositoryServiceFactoryClass>com.evolveum.midpoint.repo.sql.testing.TestSqlRepositoryFactory"
                + "</repositoryServiceFactoryClass>\n"
                + "      <database>postgresql</database>\n"
                + "      <embedded>false</embedded>\n"
                + "      <jdbcUrl>" + xml(jdbcUrl) + "</jdbcUrl>\n"
                + "      <jdbcUsername>" + xml(user) + "</jdbcUsername>\n"
                + "      <jdbcPassword></jdbcPassword>\n"
                + "      <hibernateHbm2ddl>validate</hibernateHbm2ddl>\n"
                + "    </repository>\n"
                + "    <audit><auditService>"
                + "<auditServiceFactoryClass>com.evolveum.midpoint.repo.sql.SqlAuditServiceFactory</auditServiceFactoryClass>"
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
        try {
            Harness40 harness = Harness40.start();
            prismContext = harness.bean(PrismContext.class);
            return harness;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start the midPoint Spring context", e);
        }
    }

    static LineDrivers drivers(Harness harness) {
        return new Drivers40((Harness40) harness);
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
                com.evolveum.midpoint.model.impl.expr.ModelExpressionThreadLocalHolder.getLensContext();
        if (context != null && context.getChannel() != null) {
            return context.getChannel();
        }
        com.evolveum.midpoint.task.api.Task task =
                com.evolveum.midpoint.model.impl.expr.ModelExpressionThreadLocalHolder.getCurrentTask();
        return task != null ? task.getChannel() : null;
    }
}
