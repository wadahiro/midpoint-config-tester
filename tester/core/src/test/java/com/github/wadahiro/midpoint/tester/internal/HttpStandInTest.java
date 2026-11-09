package com.github.wadahiro.midpoint.tester.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.wadahiro.midpoint.tester.HttpRequest;

/** The HTTP stand-in on its own (no midPoint needed): what a script's {@code new URL(url).openConnection()} does. */
class HttpStandInTest {

    @Test
    void aRedirectedUrlIsAnsweredAndRecordedWithItsOriginalUrl() throws Exception {
        HttpStandIn http = HttpStandIn.get();
        http.clear();
        String script = http.redirect("url = \"https://hooks.example.com/hooks/abc\"",
                List.of("https://hooks.example.com"));
        assertThat(script).isEqualTo("url = \"http://" + HttpStandIn.HOST + "/hooks.example.com/hooks/abc\"");
        String url = script.substring(script.indexOf('"') + 1, script.lastIndexOf('"')) + "?channel=ops";

        HttpURLConnection post = (HttpURLConnection) new URL(url).openConnection();
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = post.getOutputStream()) {
            out.write("{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(post.getResponseCode()).isEqualTo(200);
        post.disconnect();

        assertThat(http.requests()).hasSize(1);
        HttpRequest request = http.requests().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.url()).isEqualTo("https://hooks.example.com/hooks/abc?channel=ops");
        assertThat(request.header("content-type")).isEqualTo("application/json");
        assertThat(request.body()).isEqualTo("{\"text\":\"hello\"}");
        http.clear();
        assertThat(http.requests()).isEmpty();
    }

    /**
     * A client that does not use the proxy selector is given the server's own address (the system property
     * {@code tester.http.url}); the test's answer (status, JSON body) matches the path, the last answer wins, and
     * the request is recorded with that address. Without an answer: 200 and no body.
     */
    @Test
    void aClientGivenTheOwnAddressGetsTheTestsAnswer() throws Exception {
        HttpStandIn http = HttpStandIn.get();
        http.clear();
        String base = System.getProperty(HttpStandIn.URL_PROPERTY);
        assertThat(base).startsWith("http://127.0.0.1:");
        http.respond("POST", "/issuer/api/issue", 500, "{\"error\":\"busy\"}");
        http.respond("POST", "/issuer/api/issue", 200, "{\"serial\":\"01\"}");

        HttpURLConnection post = (HttpURLConnection) new URL(base + "/issuer/api/issue").openConnection(
                java.net.Proxy.NO_PROXY);
        post.setRequestMethod("POST");
        post.setDoOutput(true);
        try (OutputStream out = post.getOutputStream()) {
            out.write("{\"cn\":\"alice\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(post.getResponseCode()).isEqualTo(200);
        assertThat(post.getContentType()).startsWith("application/json");
        assertThat(new String(post.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{\"serial\":\"01\"}");
        post.disconnect();
        assertThat(http.requests()).extracting(HttpRequest::url).containsExactly(base + "/issuer/api/issue");

        HttpURLConnection other = (HttpURLConnection) new URL(base + "/other").openConnection(java.net.Proxy.NO_PROXY);
        assertThat(other.getResponseCode()).isEqualTo(200);
        other.disconnect();
        http.clear();
    }
}
