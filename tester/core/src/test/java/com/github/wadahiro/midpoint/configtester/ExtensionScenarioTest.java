package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.configtester.names.ItemRef;
import com.github.wadahiro.midpoint.configtester.names.Lines;

/**
 * Structured items of the extension schema (a complexType annotated a:container, {@code tester-extension.xsd}): a
 * user's addresses, each with its phones, and a single home address, written through the model and past it as
 * {@link Struct}s, and claimed whole or by a field. Over the HR sample ({@code scenarios/hr}).
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class ExtensionScenarioTest {

    static final Struct MAIN = Struct.of("street", "Main", "city", "Prague", "floor", "3",
            "phone", Struct.of("kind", "mobile", "number", "123"));
    static final Struct SIDE = Struct.of("street", "Side", "city", "Brno");
    static final ItemRef ADDRESS = new ItemRef("UserType", "http://example.com/xml/ns/tester-extension",
            "extension/address", Lines.ALL);

    @Test
    void structuredValuesAreAddedAndClaimedWholeOrByAField(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.set("extension/address", MAIN, SIDE));

        t.user("jdoe").hasItem("extension/address", SIDE, MAIN)
                .hasItem("extension/address/city", "Prague", "Brno")
                .hasItem(ADDRESS.child("street"), "Main", "Side")
                .hasItem("extension/address/phone/number", "123");
        assertThat(t.user("jdoe").values("extension/address")).containsExactlyInAnyOrder(
                "(street Main, city Prague, floor 3, phone (kind mobile, number 123))", "(street Side, city Brno)");
        assertThatThrownBy(() -> t.user("jdoe").hasItem("extension/address", MAIN))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("extension/address expected [(street Main, city Prague, floor 3, phone (kind"
                        + " mobile, number 123))] but was");
    }

    @Test
    void structuredValuesAreAddedAndRemovedThroughTheModel(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.set("extension/address", MAIN));

        t.user("jdoe").modify(m -> m.addValues("extension/address", SIDE));
        t.user("jdoe").hasItem("extension/address", MAIN, SIDE);
        t.user("jdoe").modify(m -> m.removeValues("extension/address", Struct.of("city", "Brno", "street", "Side")));
        t.user("jdoe").hasItem("extension/address", MAIN);
        assertThatThrownBy(() -> t.user("jdoe").modify(m -> m.removeValues("extension/address",
                Struct.of("street", "Main")))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No value of extension/address equal to (street Main); it has: [(street Main,");
        assertThatThrownBy(() -> t.user("jdoe").modify(m -> m.set("extension/address/city", "Brno")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension/address/city is in a multi-valued container: set the whole value");
    }

    @Test
    void aFieldOfASingleValuedStructureIsSetByItsPath(MidPointConfigTester t) {
        t.user("jdoe").add(u -> u.set("extension/home",
                Struct.xml("<home><street>Home</street><city>Prague</city></home>")));
        t.user("jdoe").hasItem("extension/home", Struct.of("street", "Home", "city", "Prague"));

        t.user("jdoe").modify(m -> m.set("extension/home/city", "Brno"));
        t.user("jdoe").hasItem("extension/home", Struct.of("street", "Home", "city", "Brno"));
        t.user("jdoe").modify(m -> m.set("extension/home/floor", 2));
        t.user("jdoe").hasItem("extension/home/floor", "2");
    }

    @Test
    void mistakesOfTheTest(MidPointConfigTester t) {
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/address", "Main street")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension/address is structured: give its values as Struct.of(...)");
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/address", Struct.of("zip", "1"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No field zip in extension/address");
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/address", Struct.of("floor", "third"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension/address/floor (xsd:int): not a value: third");
        assertThatThrownBy(() -> t.user("jdoe").add(u -> u.set("extension/tags", Struct.of("street", "x"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("extension/tags is not structured");
    }

    @Test
    void structuredValuesPastTheModel(MidPointConfigTester t) {
        t.user("jdoe").add();
        t.raw().user("jdoe").set("extension/address", MAIN, SIDE);
        t.user("jdoe").hasItem("extension/address", MAIN, SIDE);
        t.raw().user("jdoe").removeValues("extension/address", SIDE);
        t.user("jdoe").hasItem("extension/address", MAIN);
        t.raw().user("jdoe").addValues("extension/address", SIDE);
        t.user("jdoe").hasItem("extension/address", MAIN, SIDE);
    }
}
