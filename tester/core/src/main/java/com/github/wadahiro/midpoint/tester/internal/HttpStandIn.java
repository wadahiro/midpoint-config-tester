package com.github.wadahiro.midpoint.tester.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.github.wadahiro.midpoint.tester.HttpRequest;
import com.sun.net.httpserver.HttpServer;

/**
 * The stand-in for the HTTP services of the manifest's {@code http.redirect}: the deployment's copy names them by a
 * host of the tester's own ({@link #HOST}, e.g. {@code https://hooks.example.com/x} becomes
 * {@code http://tester-http.invalid/hooks.example.com/x}), and the JVM's proxy selector sends that host to an
 * in-memory HTTP server, which records the request and answers {@code 200}. The server's port is the JVM's, never in
 * the database (a cached deployment restored in another JVM works as is); HTTPS needs no certificate (the copy says
 * {@code http}). Clients that ask the default {@link ProxySelector} get it: {@code java.net.URL}'s connections, so a
 * Groovy script's {@code new URL(url).openConnection()}; a client that does not (Apache HttpClient by default, the
 * JDK's HttpClient without a proxy) reaches the redirected URL's host, which does not resolve. Such a client is given
 * the server's own address instead: the system property {@value #URL_PROPERTY} ({@code http://127.0.0.1:<port>}, set
 * before midPoint starts), e.g. {@code issuer.base-url=${tester.http.url}/issuer} in the properties of the
 * deployment's beans. A test sets the answers ({@link #respond}); without one a request gets {@code 200} and no body.
 */
public final class HttpStandIn {

    /** The tester's host of the redirected URLs (the {@code .invalid} TLD resolves nowhere). */
    public static final String HOST = "tester-http.invalid";

    /** The system property with the server's own address ({@code http://127.0.0.1:<port>}). */
    public static final String URL_PROPERTY = "tester.http.url";

    private static HttpStandIn instance;

    /** An answer of the test: to the requests of the method whose URL or path starts with the prefix. */
    private static final class Answer {
        final String method;
        final String prefix;
        final int status;
        final String body;

        Answer(String method, String prefix, int status, String body) {
            this.method = method;
            this.prefix = prefix;
            this.status = status;
            this.body = body;
        }

        boolean matches(String method, String url, String path) {
            return this.method.equalsIgnoreCase(method)
                    && (prefix.contains("://") ? url.startsWith(prefix) : path.startsWith(prefix));
        }
    }

    private final List<Answer> answers = new CopyOnWriteArrayList<>();

    private final HttpServer server;
    private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    /** The redirected URL prefixes as written, by their redirected form; for the original URL of a request. */
    private final Map<String, String> originals = new LinkedHashMap<>();

    private HttpStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String url = original(exchange.getRequestURI());
            requests.add(new HttpRequest(exchange.getRequestMethod(), url, exchange.getRequestHeaders(), body));
            Answer answer = answer(exchange.getRequestMethod(), url);
            if (answer == null || answer.body == null) {
                exchange.sendResponseHeaders(answer == null ? 200 : answer.status, -1);
            } else {
                byte[] bytes = answer.body.getBytes(StandardCharsets.UTF_8);
                String trimmed = answer.body.trim();
                exchange.getResponseHeaders().set("Content-Type", trimmed.startsWith("{") || trimmed.startsWith("[")
                        ? "application/json; charset=UTF-8" : "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(answer.status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        InetSocketAddress address = server.getAddress();
        System.setProperty(URL_PROPERTY, "http://" + address.getAddress().getHostAddress() + ":" + address.getPort());
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (uri != null && HOST.equalsIgnoreCase(uri.getHost())) {
                    return List.of(new Proxy(Proxy.Type.HTTP, address));
                }
                return previous != null ? previous.select(uri) : List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress socket, IOException e) {
                if (previous != null) {
                    previous.connectFailed(uri, socket, e);
                }
            }
        });
    }

    /** The JVM's stand-in, started (and the proxy selector installed) on first use. */
    public static synchronized HttpStandIn get() {
        if (instance == null) {
            try {
                instance = new HttpStandIn();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Cannot start the tester's HTTP stand-in", e);
            }
        }
        return instance;
    }

    /** The text with each URL prefix (e.g. {@code https://hooks.example.com}) moved to {@link #HOST}. */
    public synchronized String redirect(String text, List<String> prefixes) {
        for (String prefix : prefixes) {
            String redirected = redirected(prefix);
            originals.put(redirected, prefix);
            text = text.replace(prefix, redirected);
        }
        return text;
    }

    /** The requests received since {@link #clear()}, in the order received. */
    public List<HttpRequest> requests() {
        return new ArrayList<>(requests);
    }

    public void clear() {
        requests.clear();
        answers.clear();
    }

    /** The requests received and the answers given so far, to go back to ({@link #restore}): a checkpoint of the test. */
    static synchronized Object[] captureIfStarted() {
        return instance == null ? null
                : new Object[] { instance.requests.size(), new ArrayList<>(instance.answers) };
    }

    /** Back to what {@link #captureIfStarted} took: the later requests forgotten, the answers as then. */
    @SuppressWarnings("unchecked")
    static synchronized void restore(Object[] captured) {
        if (instance == null || captured == null) {
            return;
        }
        int requests = (Integer) captured[0];
        while (instance.requests.size() > requests) {
            instance.requests.remove(instance.requests.size() - 1);
        }
        instance.answers.clear();
        instance.answers.addAll((List<Answer>) captured[1]);
    }

    /**
     * Answers the requests of the method whose URL (a prefix with a scheme, as the configuration has it) or path (a
     * prefix starting with {@code /}) matches: the status and the body ({@code application/json} when it is an object
     * or an array, else text; null: none), until {@link #clear()}. The last answer given for a request wins.
     */
    public void respond(String method, String prefix, int status, String body) {
        answers.add(0, new Answer(method, prefix, status, body));
    }

    private Answer answer(String method, String url) {
        String path;
        try {
            path = URI.create(url).getRawPath();
        } catch (IllegalArgumentException e) {
            path = url;
        }
        for (Answer a : answers) {
            if (a.matches(method, url, path == null ? "" : path)) {
                return a;
            }
        }
        return null;
    }

    /** Clears the requests and answers of the stand-in, if it was started (the runtime starts it before midPoint). */
    static synchronized void clearIfStarted() {
        if (instance != null) {
            instance.clear();
        }
    }

    static String redirected(String prefix) {
        return "http://" + HOST + "/" + prefix.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
    }

    /**
     * The request's URL as the configuration has it: the longest redirected prefix it starts with, given back; a
     * request made to the server's own address keeps it.
     */
    private synchronized String original(URI uri) {
        if (!uri.isAbsolute()) {
            // not through the proxy selector: a client given the server's own address
            return System.getProperty(URL_PROPERTY) + uri;
        }
        String url = uri.toString();
        String best = null;
        for (String redirected : originals.keySet()) {
            if (url.startsWith(redirected) && (best == null || redirected.length() > best.length())) {
                best = redirected;
            }
        }
        return best != null ? originals.get(best) + url.substring(best.length()) : url;
    }
}
