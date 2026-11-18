package com.github.wadahiro.midpoint.configtester;

import java.util.List;

import com.github.wadahiro.midpoint.tester.internal.RestExchange;

/**
 * midPoint's response to a {@link Rest} request: the status, the headers and the body as midPoint's converters wrote
 * them, and what midPoint did while it handled the request ({@link #effects()}: the objects it changed, its calls to
 * the connectors, the mails and audit records, with the background work it waited for).
 */
public final class RestResponse {

    private final String method;
    private final String path;
    private final RestExchange exchange;
    private final Effects effects;

    RestResponse(String method, String path, RestExchange exchange, Effects effects) {
        this.method = method;
        this.path = path;
        this.exchange = exchange;
        this.effects = effects;
    }

    public int status() {
        return exchange.status;
    }

    /** The first value of the header (case-insensitive), or null. */
    public String header(String name) {
        List<String> values = exchange.headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Every value of the header (case-insensitive), empty when none. */
    public List<String> headers(String name) {
        return exchange.headers.getOrDefault(name, List.of());
    }

    /** The body as text (UTF-8), empty when none. */
    public String body() {
        return exchange.bodyText();
    }

    /**
     * The body parsed as JSON (Jackson's tree; a missing node: {@code path(...)} returns a missing node, {@code get}
     * null), e.g. a bulk action's output:
     * {@code r.json().path("object").path("output").path("dataOutput").path("item").path(0).path("value")}.
     * Fails when the body is not JSON.
     */
    public com.fasterxml.jackson.databind.JsonNode json() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.body);
        } catch (java.io.IOException e) {
            throw new AssertionError("The body of " + method + " " + path + " is not JSON: " + e.getMessage()
                    + "\n" + this, e);
        }
    }

    public Effects effects() {
        return effects;
    }

    @Override
    public String toString() {
        String body = body();
        return method + " " + path + " -> " + status() + (body.isEmpty() ? ""
                : " " + (body.length() > 500 ? body.substring(0, 500) + "..." : body));
    }
}
