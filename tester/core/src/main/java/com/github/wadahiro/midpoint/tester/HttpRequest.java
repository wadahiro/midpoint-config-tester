package com.github.wadahiro.midpoint.tester;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An HTTP request the configuration made to a URL the manifest redirects to the tester ({@code http.redirect}): a
 * script's {@code new URL(url).openConnection()}, a webhook of a notification. The tester's stand-in records it and
 * answers {@code 200} with an empty body; nothing leaves the test JVM.
 */
public final class HttpRequest {

    private final String method;
    private final String url;
    private final Map<String, List<String>> headers;
    private final String body;

    public HttpRequest(String method, String url, Map<String, List<String>> headers, String body) {
        this.method = method;
        this.url = url;
        Map<String, List<String>> h = new LinkedHashMap<>();
        headers.forEach((k, v) -> h.put(k, List.copyOf(v)));
        this.headers = Collections.unmodifiableMap(h);
        this.body = body;
    }

    /** {@code GET}, {@code POST}... */
    public String method() {
        return method;
    }

    /** The URL as the configuration has it (before the tester redirected it), with the query. */
    public String url() {
        return url;
    }

    /** The request headers by name (as the client sent their names). */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** The first value of the header (by name, ignoring case), or null. */
    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    /** The body as UTF-8 text; empty without one. */
    public String body() {
        return body;
    }

    @Override
    public String toString() {
        return method + " " + url + (body.isEmpty() ? "" : " " + body);
    }
}
