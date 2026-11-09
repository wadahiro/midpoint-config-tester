package com.github.wadahiro.midpoint.tester.mirror.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.AttributeInfo;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Uid;
import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.mirror.MirrorClass;
import com.github.wadahiro.midpoint.tester.mirror.MirrorResource;
import com.github.wadahiro.midpoint.tester.mirror.MirrorStore;

/**
 * An identifier without a native name ({@code icfs:uid}) that the target system issues: a UUID apart from the name
 * (or as the expression makes it), declared in the schema, kept by a rename.
 */
class GeneratedUidTest {

    static final ObjectClass ACCOUNT = ObjectClass.ACCOUNT;

    @Test
    void aUuidApartFromTheNameKeptByARename() {
        MirrorConnector connector = connector("generated-uid", c -> c.withGeneratedUid());

        Uid uid = connector.create(ACCOUNT, Set.of(new Name("jdoe")), null);
        assertThat(uid.getUidValue()).isNotEqualTo("jdoe")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}");
        Set<?> sideEffects = connector.updateDelta(ACCOUNT, uid, Set.of(AttributeDeltaBuilder.build(Name.NAME,
                List.of("john"))), null);
        assertThat(sideEffects).isEmpty();
        AttributeInfo uidInfo = connector.schema().findObjectClassInfo(ACCOUNT.getObjectClassValue())
                .getAttributeInfo().stream().filter(a -> a.is(Uid.NAME)).findFirst().get();
        assertThat(uidInfo.getNativeName()).isNull();
        assertThat(uidInfo.isCreateable()).isFalse();
    }

    @Test
    void asTheExpressionMakesIt() {
        MirrorConnector connector = connector("issued-uid", c -> c.withGeneratedUid().withUidValue("'id-' + name"));

        assertThat(connector.create(ACCOUNT, Set.of(new Name("jdoe")), null).getUidValue()).isEqualTo("id-jdoe");
    }

    /** An identifier with the naming attribute's native name (a user's username for both) is the name. */
    @Test
    void anIdentifierThatIsTheNamingAttributeIsTheName() {
        MirrorConnector connector = connector("uid-is-name", c -> new MirrorClass("User", "User", "username",
                "username", false, false, Map.of()));
        ObjectClass user = new ObjectClass("User");

        Uid uid = connector.create(user, Set.of(new Name("jdoe")), null);

        assertThat(uid.getUidValue()).isEqualTo("jdoe");
        AttributeInfo uidInfo = connector.schema().findObjectClassInfo("User").getAttributeInfo().stream()
                .filter(a -> a.is(Uid.NAME)).findFirst().get();
        assertThat(uidInfo.getNativeName()).isEqualTo("username");
    }

    @Test
    void theNameWithoutIt() {
        MirrorConnector connector = connector("name-uid", c -> c);

        assertThat(connector.create(ACCOUNT, Set.of(new Name("jdoe")), null).getUidValue()).isEqualTo("jdoe");
    }

    private static MirrorConnector connector(String instance, java.util.function.UnaryOperator<MirrorClass> change) {
        MirrorClass c = new MirrorClass("AccountObjectClass", ObjectClass.ACCOUNT_NAME, null, null, false, false,
                Map.of());
        MirrorResource resource = MirrorStore.getOrCreate(instance);
        resource.define(List.of(change.apply(c)), List.of(), false, false);
        MirrorConfiguration configuration = new MirrorConfiguration();
        configuration.setInstanceId(instance);
        MirrorConnector connector = new MirrorConnector();
        connector.init(configuration);
        return connector;
    }
}
