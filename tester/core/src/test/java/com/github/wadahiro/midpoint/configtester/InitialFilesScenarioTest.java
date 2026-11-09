package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * {@code files.initial}: a mapped directory starts every test with the repository's tree: its files, and its empty
 * directories (a {@code .gitkeep} makes the directory, it is not there itself).
 */
@MidPointConfigTest("classpath:scenarios/initial-files/midpoint-config-tester.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InitialFilesScenarioTest {

    @Test
    @Order(1)
    void theTreeIsThere(MidPointConfigTester t) {
        assertThat(t.midpoint().files().mapped()).containsExactly("/opt/data");
        assertThat(t.midpoint().files().read("/opt/data/in/hello.txt")).isEqualTo("hello\n");
        assertThat(t.midpoint().files().exists("/opt/data/out")).isTrue();
        assertThat(t.midpoint().files().list("/opt/data/out")).isEmpty();
        // a script writes in the empty directory, as midPoint's tasks do in a volume
        t.midpoint().script("new File('/opt/data/out/report.csv').text = 'x'; 'ok'").evaluate();
        assertThat(t.midpoint().files().list("/opt/data/out")).containsExactly("/opt/data/out/report.csv");
    }

    @Test
    @Order(2)
    void everyTestStartsWithTheTree(MidPointConfigTester t) {
        assertThat(t.midpoint().files().list("/opt/data/out")).isEmpty();
        assertThat(t.midpoint().files().read("/opt/data/in/hello.txt")).isEqualTo("hello\n");
    }
}
