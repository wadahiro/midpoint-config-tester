package com.github.wadahiro.midpoint.configtester;

import com.github.wadahiro.midpoint.tester.internal.HttpStandIn;

/**
 * The tester's HTTP stand-in ({@link MidPointConfigTester#httpServer()}): the services the manifest's {@code http.redirect} moves
 * there, and any client given its address ({@code ${tester.http.url}}, e.g. the base URL in the properties of a
 * deployment's beans). A test sets what it answers; the requests are in {@link MidPointConfigTester#httpRequests()}. The answers
 * are forgotten after the test.
 *
 * <pre>{@code
 * t.httpServer().on("POST", "/issuer/api/issue").respond(200, "{\"serial\": \"01\"}");
 * t.httpServer().on("POST", "https://hooks.example.com/notify").respond(503, null);
 * }</pre>
 */
public final class HttpServer {

    HttpServer() {
    }

    /**
     * The requests of the method (GET, POST...) whose URL starts with the prefix (with a scheme: as the configuration
     * has it) or whose path does (starting with {@code /}).
     */
    public Requests on(String method, String prefix) {
        return new Requests(method, prefix);
    }

    /** The address of the stand-in for clients that do not use the JVM's proxy selector. */
    public String url() {
        return System.getProperty(HttpStandIn.URL_PROPERTY);
    }

    public static final class Requests {
        private final String method;
        private final String prefix;

        Requests(String method, String prefix) {
            this.method = method;
            this.prefix = prefix;
        }

        /** Answers them with the status and the body (JSON when an object or an array; null: no body). */
        public HttpServer respond(int status, String body) {
            HttpStandIn.get().respond(method, prefix, status, body);
            return new HttpServer();
        }
    }
}
