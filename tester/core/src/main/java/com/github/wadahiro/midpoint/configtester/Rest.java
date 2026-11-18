package com.github.wadahiro.midpoint.configtester;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.wadahiro.midpoint.tester.internal.EffectsCapture;
import com.github.wadahiro.midpoint.tester.internal.RestExchange;

/**
 * A request to midPoint's REST API ({@link MidPointConfigTester#rest()}, midPoint 4.8 and later), as a client sends
 * it: a portal, a script, another system. It goes through midPoint's own authentication (the security policy's
 * sequence of the REST channel, e.g. HTTP Basic) and authorization, its controllers and its message converters, with
 * no HTTP server: in this JVM, like the other driver calls (its mappings recorded, the background work it starts
 * awaited). Immutable: each method returns a new request.
 *
 * <pre>{@code
 * t.user("jdoe").modify(m -> m.password("Secret-1"));
 * RestResponse self = t.rest().basic("jdoe", "Secret-1").get("/ws/rest/self");
 * assertThat(self.status()).isEqualTo(200);
 *
 * RestResponse r = t.rest().basic("portal", "Portal-1")
 *         .header("Switch-To-Principal", jdoeOid)
 *         .patch("/ws/rest/users/" + jdoeOid, "{\"objectModification\": {...}}");
 * r.effects().changes().of("jdoe").single();
 * }</pre>
 *
 * The caller is who the request's headers say: the test's login and {@link MidPointConfigTester#callAs} do not apply.
 * The deployment's security policy must give the REST channel an authentication sequence (midPoint's default policy,
 * referenced by the system configuration's {@code globalSecurityPolicyRef}, does).
 */
public final class Rest {

    private static final String JSON = "application/json";

    private final MidPointConfigTester tester;
    private final Map<String, List<String>> headers;

    Rest(MidPointConfigTester tester) {
        this(tester, Map.of("Accept", List.of(JSON)));
    }

    private Rest(MidPointConfigTester tester, Map<String, List<String>> headers) {
        this.tester = tester;
        this.headers = Collections.unmodifiableMap(headers);
    }

    /** HTTP Basic credentials ({@code Authorization: Basic ...}): the user's name and password in midPoint. */
    public Rest basic(String username, String password) {
        String token = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return withHeader("Authorization", "Basic " + token, true);
    }

    /** A header added to the request ({@code Switch-To-Principal}, a custom one), after those of the same name. */
    public Rest header(String name, String value) {
        return withHeader(name, value, false);
    }

    /** The media type asked for ({@code Accept}; {@code application/json} by default, or XML, YAML). */
    public Rest accept(String mediaType) {
        return withHeader("Accept", mediaType, true);
    }

    public RestResponse get(String path) {
        return send("GET", path, null, null);
    }

    public RestResponse delete(String path) {
        return send("DELETE", path, null, null);
    }

    /** A POST with a JSON body (null: none). */
    public RestResponse post(String path, String json) {
        return send("POST", path, JSON, json);
    }

    /** A POST with a body of the media type (XML, YAML). */
    public RestResponse post(String path, String contentType, String body) {
        return send("POST", path, contentType, body);
    }

    /** A PUT with a JSON body. */
    public RestResponse put(String path, String json) {
        return send("PUT", path, JSON, json);
    }

    public RestResponse put(String path, String contentType, String body) {
        return send("PUT", path, contentType, body);
    }

    /** A PATCH with a JSON body (midPoint's {@code objectModification}). */
    public RestResponse patch(String path, String json) {
        return send("PATCH", path, JSON, json);
    }

    public RestResponse patch(String path, String contentType, String body) {
        return send("PATCH", path, contentType, body);
    }

    private Rest withHeader(String name, String value, boolean replace) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((n, v) -> {
            if (!(replace && n.equalsIgnoreCase(name))) {
                copy.put(n, v);
            }
        });
        List<String> values = new ArrayList<>(copy.getOrDefault(name, List.of()));
        values.add(value);
        copy.put(name, Collections.unmodifiableList(values));
        return new Rest(tester, copy);
    }

    private RestResponse send(String method, String path, String contentType, String body) {
        byte[] bytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
        RestExchange[] exchange = new RestExchange[1];
        EffectsCapture capture = EffectsCapture.capture(tester.deployment().api(), () -> exchange[0] =
                RestExchange.send(tester.deployment().api(), method, path, headers, contentType, bytes));
        return new RestResponse(method, path, exchange[0], Effects.of(capture, tester));
    }
}
