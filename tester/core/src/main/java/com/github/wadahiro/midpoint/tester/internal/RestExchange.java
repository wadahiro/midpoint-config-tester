package com.github.wadahiro.midpoint.tester.internal;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.github.wadahiro.midpoint.tester.Answer;
import com.github.wadahiro.midpoint.tester.MidPoint;

/**
 * One request to midPoint's REST API and its response ({@code MidPointConfigTester#rest}): the request goes through
 * midPoint's own security filter chain and controllers in this JVM (the line's {@code RestImpl}), as a driver call.
 */
public final class RestExchange {

    public final int status;
    /** The response's headers, by name (case-insensitive). */
    public final Map<String, List<String>> headers;
    public final byte[] body;

    RestExchange(int status, Map<String, List<String>> headers, byte[] body) {
        this.status = status;
        Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.putAll(headers);
        this.headers = Collections.unmodifiableMap(h);
        this.body = body;
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Sends the request as a driver call ({@code MidPoint.invoke}): the recorder on, the background work it starts
     * awaited, the tester's login kept. midPoint's filter chain decides who calls, from the request's headers: the
     * login of the test and {@code callAs} do not apply.
     */
    public static RestExchange send(MidPoint mp, String method, String path, Map<String, List<String>> headers,
            String contentType, byte[] body) {
        MidPointImpl impl = (MidPointImpl) mp;
        // before the driver call: a line without REST fails as such, not as a failed call
        impl.rest().available();
        Answer<RestExchange> answer = impl.invoke("REST " + method + " " + path,
                (task, result) -> impl.rest().perform(method, path, new LinkedHashMap<>(headers), contentType, body));
        return answer.value();
    }
}
