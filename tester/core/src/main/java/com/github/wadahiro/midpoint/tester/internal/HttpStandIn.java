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
 * JDK's HttpClient without a proxy) reaches the redirected URL's host, which does not resolve.
 */
public final class HttpStandIn {

    /** The tester's host of the redirected URLs (the {@code .invalid} TLD resolves nowhere). */
    public static final String HOST = "tester-http.invalid";

    private static HttpStandIn instance;

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
            requests.add(new HttpRequest(exchange.getRequestMethod(), original(exchange.getRequestURI()),
                    exchange.getRequestHeaders(), body));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        InetSocketAddress address = server.getAddress();
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
    }

    /** Clears the requests of the stand-in if a deployment started it (nothing else starts it). */
    static synchronized void clearIfStarted() {
        if (instance != null) {
            instance.clear();
        }
    }

    static String redirected(String prefix) {
        return "http://" + HOST + "/" + prefix.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
    }

    /** The request's URL as the configuration has it: the longest redirected prefix it starts with, given back. */
    private synchronized String original(URI uri) {
        String url = uri.isAbsolute() ? uri.toString() : "http://" + HOST + uri;
        String best = null;
        for (String redirected : originals.keySet()) {
            if (url.startsWith(redirected) && (best == null || redirected.length() > best.length())) {
                best = redirected;
            }
        }
        return best != null ? originals.get(best) + url.substring(best.length()) : url;
    }
}
