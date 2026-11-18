package com.github.wadahiro.midpoint.tester.internal;

import java.util.List;
import java.util.Map;

/** midPoint's REST API on 4.4: not in the tester (it serves REST through CXF, with an authentication of its own). */
final class RestImpl {

    RestImpl(MidPointImpl mp) {
    }

    /** Fails: the line has no REST calls in the tester. */
    void available() {
        throw new UnsupportedOperationException("REST calls (t.rest()) need midPoint 4.8 or later: 4.4 serves its REST"
                + " API through CXF, which the tester does not run");
    }

    RestExchange perform(String method, String path, Map<String, List<String>> headers, String contentType,
            byte[] body) {
        available();
        return null;
    }
}
