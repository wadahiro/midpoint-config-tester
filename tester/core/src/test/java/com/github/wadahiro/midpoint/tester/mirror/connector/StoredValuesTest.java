package com.github.wadahiro.midpoint.tester.mirror.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Uid;
import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.mirror.MirrorAttribute;
import com.github.wadahiro.midpoint.tester.mirror.MirrorClass;
import com.github.wadahiro.midpoint.tester.mirror.MirrorResource;
import com.github.wadahiro.midpoint.tester.mirror.MirrorStore;

/**
 * How the target system keeps the values of a multi-valued attribute: as a map ({@code keyedValues}: one value per key,
 * a value added replaces the value of its key, a value removed removes its key, as Guacamole does with a connection's
 * parameters), or as a Groovy expression says ({@code store}).
 */
class StoredValuesTest {

    @Test
    void oneValuePerKey() {
        MirrorConnector connector = connector("keyed-values", true);
        ObjectClass connection = new ObjectClass("Connection");

        Uid uid = connector.create(connection, Set.of(new Name("c1"), AttributeBuilder.build("parameters",
                "port=3389", "disable-paste=true", "port=3390")), null);
        assertThat(parameters(connector, uid)).containsExactly("disable-paste=true", "port=3390");

        connector.updateDelta(connection, uid, Set.of(AttributeDeltaBuilder.build("parameters",
                List.of("disable-paste=false"), null)), null);
        assertThat(parameters(connector, uid)).containsExactly("port=3390", "disable-paste=false");

        connector.updateDelta(connection, uid, Set.of(AttributeDeltaBuilder.build("parameters",
                null, List.of("port=0"))), null);
        assertThat(parameters(connector, uid)).containsExactly("disable-paste=false");
    }

    @Test
    void plainValuesWithoutKeys() {
        MirrorConnector connector = connector("plain-values", false);
        ObjectClass connection = new ObjectClass("Connection");
        Uid uid = connector.create(connection, Set.of(new Name("c1"), AttributeBuilder.build("parameters",
                "disable-paste=true")), null);

        connector.updateDelta(connection, uid, Set.of(AttributeDeltaBuilder.build("parameters",
                List.of("disable-paste=false"), null)), null);

        assertThat(parameters(connector, uid)).containsExactly("disable-paste=true", "disable-paste=false");
    }

    /** The expression gets the values kept, what midPoint writes and the object's name; what it returns is kept. */
    @Test
    void valuesAsAnExpressionMakesThem() {
        // lower case, sorted, with a value of the object's own
        MirrorConnector connector = connector("stored-values", c -> c.withStoreExpression("parameters",
                "(((replace ?: values) - remove + add).collect { it.toLowerCase() } + ('tag-of-' + name))"
                        + ".unique().sort()"));
        ObjectClass connection = new ObjectClass("Connection");

        Uid uid = connector.create(connection, Set.of(new Name("c1"), AttributeBuilder.build("parameters", "B", "a")),
                null);
        assertThat(parameters(connector, uid)).containsExactly("a", "b", "tag-of-c1");

        connector.updateDelta(connection, uid, Set.of(AttributeDeltaBuilder.build("parameters", List.of("C"),
                List.of("a"))), null);
        assertThat(parameters(connector, uid)).containsExactly("b", "c", "tag-of-c1");

        connector.updateDelta(connection, uid, Set.of(AttributeDeltaBuilder.build("parameters", List.of("Z"))), null);
        assertThat(parameters(connector, uid)).containsExactly("tag-of-c1", "z");
    }

    private static MirrorConnector connector(String instance, boolean keyed) {
        return connector(instance, c -> keyed ? c.withKeyedValues("parameters", "=") : c);
    }

    private static MirrorConnector connector(String instance, java.util.function.UnaryOperator<MirrorClass> change) {
        MirrorAttribute parameters = new MirrorAttribute("parameters", String.class, true, true, true, true);
        MirrorClass c = new MirrorClass("Connection", "Connection", null, null, false, false,
                Map.of("parameters", parameters));
        MirrorResource resource = MirrorStore.getOrCreate(instance);
        resource.define(List.of(change.apply(c)), List.of(), false, false);
        MirrorConfiguration configuration = new MirrorConfiguration();
        configuration.setInstanceId(instance);
        MirrorConnector connector = new MirrorConnector();
        connector.init(configuration);
        return connector;
    }

    private static List<Object> parameters(MirrorConnector connector, Uid uid) {
        MirrorResource resource = MirrorStore.get(((MirrorConfiguration) connector.getConfiguration()).getInstanceId());
        MirrorClass c = resource.byName("Connection");
        return resource.findByUid(c, uid.getUidValue()).attributes.get("parameters");
    }
}
