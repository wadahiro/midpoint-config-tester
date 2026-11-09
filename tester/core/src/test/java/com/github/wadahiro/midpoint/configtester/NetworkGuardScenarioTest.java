package com.github.wadahiro.midpoint.configtester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.agent.NetworkGuard;
import com.github.wadahiro.midpoint.tester.internal.Deployment;

/**
 * The test JVM reaches no other machine: a connection to one is refused and recorded, and the test fails after it
 * even when the error was only logged; connections on this machine (the stand-ins) go through.
 */
@MidPointConfigTest("classpath:scenarios/hr/midpoint-config-tester.yaml")
class NetworkGuardScenarioTest {

    @Test
    void aConnectionToAnotherMachineIsRefused(MidPointConfigTester t) throws Exception {
        assertThat(NetworkGuard.active()).isTrue();
        try (Socket socket = new Socket()) {
            assertThatThrownBy(() -> socket.connect(new InetSocketAddress("192.0.2.1", 25), 1000))
                    .hasMessageContaining("The tester refuses connections to other machines (192.0.2.1:25");
        }
        assertThatThrownBy(() -> t.midpoint().script(
                "new java.net.URL('https://203.0.113.7/hook').openConnection().getResponseCode()").evaluate())
                .hasMessageContaining("refuses connections");

        assertThatThrownBy(() -> Deployment.checkNetwork("in this test"))
                .hasMessageContaining("192.0.2.1:25").hasMessageContaining("203.0.113.7:443");
        Deployment.checkNetwork("after the check");      // taken: nothing left for the test's end
    }

    @Test
    void connectionsOnThisMachineGoThrough(MidPointConfigTester t) throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0);
                Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
            assertThat(socket.isConnected()).isTrue();
        }
    }
}
