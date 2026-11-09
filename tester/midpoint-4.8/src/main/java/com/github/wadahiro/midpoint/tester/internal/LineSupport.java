package com.github.wadahiro.midpoint.tester.internal;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.model.common.mapping.AbstractMappingImpl;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;

/** midPoint 4.8 line of the tester runtime; the native-repository parts are shared ({@link Line48}). */
public final class LineSupport {

    public static final String LINE = "4.8";

    static final String CONFIG_FILE = Line48.CONFIG_FILE;
    static final String[] SCHEMA_SCRIPTS = Line48.SCHEMA_SCRIPTS;
    static final String[] BASELINE = Line48.BASELINE;

    private LineSupport() {
    }

    /**
     * A container or bean value (e.g. {@code InformationType}) as one line of JSON, so that the normalized text
     * shows its content; null when prism cannot serialize it.
     */
    public static String serializeRealValue(Object value) {
        return Line48.serializeRealValue(value);
    }

    static String config(String jdbcUrl, String user, String extensionDir, String connectorDir) {
        return Line48.config(jdbcUrl, user, extensionDir, connectorDir);
    }

    static Harness startHarness() {
        return Line48.startHarness();
    }

    static LineDrivers drivers(Harness harness) {
        return new Drivers48((Harness48) harness);
    }

    // ---- midPoint API that differs between the 4.8+ lines -------------------------------------------------------

    /** The repository's own caches (ext items, URIs), reloaded from the restored database. */
    static void clearRepositoryCaches(SqaleRepoContext context) {
        context.clearCaches();
    }

    /** A multi-valued, frozen definition of an expression's output ({@code toMutable()}). */
    static <T> PrismPropertyDefinition<T> outputDefinition(QName name, QName type) {
        PrismPropertyDefinition<T> definition = PrismContext.get().definitionFactory().createPropertyDefinition(name, type);
        definition.toMutable().setMaxOccurs(-1);
        definition.freeze();
        return definition;
    }

    /** The mapping's target item name as midPoint gives it to expressions ({@code getMappingQName()}). */
    static QName mappingTargetName(AbstractMappingImpl<?, ?, ?> mapping) {
        return mapping.getMappingQName();
    }

    /** See {@code Line48.currentChannel}. */
    static String currentChannel() {
        return Line48.currentChannel();
    }
}
