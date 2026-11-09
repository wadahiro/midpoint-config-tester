package com.github.wadahiro.midpoint.tester.internal;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import com.github.wadahiro.midpoint.tester.agent.NetworkGuard;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Instruments the JDK's socket connections for {@link NetworkGuard}: {@code java.net.Socket.connect(SocketAddress,
 * int)} (JavaMail, Apache HttpClient, {@code java.net.URL}, JDBC) and {@code sun.nio.ch.SocketChannelImpl.connect}
 * (NIO clients). {@code NetworkGuard} must be on the bootstrap class path.
 */
final class NetworkAgent {

    private NetworkAgent() {
    }

    /** Null on success, else why not. */
    static String install(Instrumentation instrumentation) {
        List<Throwable> errors = new ArrayList<>();
        try {
            Class.forName("sun.nio.ch.SocketChannelImpl", false, null);
        } catch (ClassNotFoundException e) {
            // not on this JDK
        }
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader loader, net.bytebuddy.utility.JavaModule module,
                            boolean loaded, Throwable throwable) {
                        errors.add(throwable);
                    }
                })
                .ignore(none())
                .type(named("java.net.Socket"))
                .transform((b, type, loader, module, domain) -> b.visit(Advice.to(ConnectAdvice.class)
                        .on(named("connect").and(takesArguments(2)).and(takesArgument(0, SocketAddress.class)))))
                .type(named("sun.nio.ch.SocketChannelImpl"))
                .transform((b, type, loader, module, domain) -> b.visit(Advice.to(ConnectAdvice.class)
                        .on(named("connect").and(takesArguments(1)).and(takesArgument(0, SocketAddress.class)))))
                .installOn(instrumentation);
        return errors.isEmpty() ? null : "Cannot guard the JVM's connections: " + errors.get(0);
    }

    // ---- inlined advice: may refer to NetworkGuard (bootstrap) only ----------------------------------------------

    static final class ConnectAdvice {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) SocketAddress endpoint) throws java.net.ConnectException {
            NetworkGuard.check(endpoint);
        }
    }
}
