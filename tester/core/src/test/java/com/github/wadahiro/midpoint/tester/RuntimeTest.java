package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.evolveum.midpoint.model.common.mapping.MappingFactory;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;

/** The runtime: baseline, recorder wiring, and the reset after each method back to the class's configuration. */
@MidPointTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuntimeTest {

    static final String NS = "xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'";

    @BeforeAll
    static void configure(MidPoint mp) {
        mp.importObject(new File(Fixtures.ROOT, "role-dept-it.xml"));
    }

    @Test
    @Order(1)
    void baselineAndWiring(MidPoint mp) throws Exception {
        // the line of the midPoint release the module is built against (-Dmidpoint48.version=... etc.)
        assertThat(System.getProperty("tester.midpointVersion")).startsWith(mp.line() + ".");
        // archetypes cases, approvals, certification and reports need
        assertThat(mp.object(ArchetypeType.class, "Approval case")).isNotNull();
        if (Lines.atLeast(mp, "4.4")) {
            // the certification task archetype (4.0 has no task archetypes)
            assertThat(mp.object(ArchetypeType.class, "00000000-0000-0000-0000-000000000520")).isNotNull();
            // the bulk action task archetypes, which the tasks of asynchronous policy scripts get
            assertThat(mp.object(ArchetypeType.class, "00000000-0000-0000-0000-000000000508")).isNotNull();
            assertThat(mp.object(ArchetypeType.class, "00000000-0000-0000-0000-000000000509")).isNotNull();
        }
        if (Lines.atLeast(mp, "4.4")) {
            // every mapping midPoint builds comes from the recording factory, also through ModelBeans
            assertThat(mp.bean(MappingFactory.class).getClass().getSimpleName()).isEqualTo("RecordingMappingFactory");
            Object modelBeans = Class.forName("com.evolveum.midpoint.model.impl.ModelBeans").getMethod("get").invoke(null);
            assertThat(modelBeans.getClass().getField("mappingFactory").get(modelBeans).getClass().getSimpleName())
                    .isEqualTo("RecordingMappingFactory");
        } else {
            // 4.0: a mapping cannot be subclassed; the model evaluates every mapping through its mapping evaluator
            Object evaluator = mp.bean(Class.forName(
                    "com.evolveum.midpoint.model.impl.lens.projector.mappings.MappingEvaluator"));
            assertThat(evaluator.getClass().getSimpleName()).isEqualTo("RecordingMappingEvaluator");
        }
    }

    @Test
    @Order(2)
    void changeSomething(MidPoint mp) {
        mp.addObjectXml("<user " + NS + "><name>temp</name></user>");
        mp.importObjectXml("<role " + NS + " oid='10000000-0000-0000-0000-00000000c002'><name>Renamed</name></role>");
        assertThat(mp.user("temp")).isNotNull();
    }

    @Test
    @Order(3)
    void startsFromTheClassState(MidPoint mp) {
        assertThat(mp.user("temp")).isNull();
        assertThat(mp.object(RoleType.class, "10000000-0000-0000-0000-00000000c002").getName().getOrig())
                .isEqualTo("Dept IT");
    }
}
