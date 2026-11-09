package com.github.wadahiro.midpoint.tester.agent;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Refuses the connections of the test JVM to other machines: midPoint's stand-ins (database, LDAP, SMTP, HTTP) are
 * all on this one, and a mail server, an HTTP service or a cloud API a deployment names must never be reached by a
 * test. The instrumented {@code Socket.connect} and {@code SocketChannel.connect} call {@link #check}: a connection to
 * a host that is not a loopback or local address (nor allowed) fails with a {@code ConnectException} and is recorded,
 * so that the test fails even when midPoint or a script only logs the error (as midPoint does with a mail).
 *
 * Loaded by the bootstrap class loader (as {@link TimeShift}): the instrumented JDK classes call it. Not an API.
 */
public final class NetworkGuard {

    private static volatile boolean active;
    private static volatile String[] allowed = new String[0];
    private static final List<String> VIOLATIONS = new ArrayList<>();

    private NetworkGuard() {
    }

    /** From now on, connections to other machines are refused, but to these hosts (names or addresses). */
    public static void activate(String[] allowedHosts) {
        allowed = allowedHosts.clone();
        active = true;
    }

    /** Whether the guard refuses connections (false: the JDK could not be instrumented). */
    public static boolean active() {
        return active;
    }

    /** The refused connections since the last call. */
    public static List<String> takeViolations() {
        synchronized (VIOLATIONS) {
            List<String> out = new ArrayList<>(VIOLATIONS);
            VIOLATIONS.clear();
            return out;
        }
    }

    /** Called before a socket connects: fails for a host of another machine. */
    public static void check(SocketAddress endpoint) throws ConnectException {
        if (!active || !(endpoint instanceof InetSocketAddress)) {
            return;
        }
        InetSocketAddress address = (InetSocketAddress) endpoint;
        String host = address.getHostString();
        InetAddress ip = address.getAddress();
        if (ip != null && (ip.isLoopbackAddress() || ip.isAnyLocalAddress())) {
            return;
        }
        if (ip == null && "localhost".equalsIgnoreCase(host)) {
            return;
        }
        for (String allowedHost : allowed) {
            if (allowedHost.equalsIgnoreCase(host) || ip != null && allowedHost.equals(ip.getHostAddress())) {
                return;
            }
        }
        String violation = host + ":" + address.getPort() + " (thread " + Thread.currentThread().getName() + ")";
        synchronized (VIOLATIONS) {
            VIOLATIONS.add(violation);
        }
        throw new ConnectException("The tester refuses connections to other machines (" + violation
                + "): a test reaches only the tester's stand-ins");
    }
}
