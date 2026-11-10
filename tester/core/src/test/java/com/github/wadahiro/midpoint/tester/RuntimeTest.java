package com.github.wadahiro.midpoint.tester;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.evolveum.midpoint.model.common.mapping.MappingFactory;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;

import com.github.wadahiro.midpoint.configtester.MidPointConfigTest;
import com.github.wadahiro.midpoint.configtester.MidPointConfigTester;

/**
 * The runtime: baseline, recorder wiring, and the reset after each method back to the deployment (the configuration a
 * method imports is gone after it).
 */
@MidPointConfigTest(Fixtures.BARE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuntimeTest {

    static final String NS = "xmlns='http://midpoint.evolveum.com/xml/ns/public/common/common-3'";

    @BeforeEach
    void configure(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.importObject(new File(Fixtures.ROOT, "role-dept-it.xml"));
    }

    @Test
    @Order(1)
    void baselineAndWiring(MidPointConfigTester t) throws Exception {
        MidPoint mp = t.midpoint();
        // the line of the midPoint release the module is built against (-Dmidpoint48.version=... etc.)
        assertThat(System.getProperty("tester.midpointVersion")).startsWith(mp.line() + ".");
        // archetypes cases, approvals, certification and reports need
        assertThat(mp.find(ArchetypeType.class, "Approval case")).isNotNull();
        if (Lines.atLeast(mp, "4.4")) {
            // the certification task archetype (4.0 has no task archetypes)
            assertThat(mp.find(ArchetypeType.class, "00000000-0000-0000-0000-000000000520")).isNotNull();
            // the bulk action task archetypes, which the tasks of asynchronous policy scripts get
            assertThat(mp.find(ArchetypeType.class, "00000000-0000-0000-0000-000000000508")).isNotNull();
            assertThat(mp.find(ArchetypeType.class, "00000000-0000-0000-0000-000000000509")).isNotNull();
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
    void changeSomething(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        mp.addObjectXml("<user " + NS + "><name>temp</name></user>");
        mp.importObjectXml("<role " + NS + " oid='10000000-0000-0000-0000-00000000c002'><name>Renamed</name></role>");
        assertThat(mp.user("temp")).isNotNull();
    }

    @Test
    @Order(3)
    void startsFromTheDeployment(MidPointConfigTester t) {
        MidPoint mp = t.midpoint();
        assertThat(mp.user("temp")).isNull();
        assertThat(mp.find(RoleType.class, "10000000-0000-0000-0000-00000000c002").getName().getOrig())
                .isEqualTo("Dept IT");
    }
}
