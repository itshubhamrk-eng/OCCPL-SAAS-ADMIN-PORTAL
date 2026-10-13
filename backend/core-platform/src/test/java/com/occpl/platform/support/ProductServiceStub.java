package com.occpl.platform.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A fake product service (JDK HTTP server) for provisioning tests. Responses are scripted per path
 * fragment (e.g. a company id); unscripted requests get 200. Also serves an OAuth2 token endpoint at
 * {@code /token} for Core's client-credentials grant.
 */
public final class ProductServiceStub implements AutoCloseable {

    public record Request(String method, String path, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    public record Response(int status, Duration delay) {
        public static Response of(int status) {
            return new Response(status, Duration.ZERO);
        }
    }

    public static final String TOKEN = "stub-service-token";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Response>> scripts = new ConcurrentHashMap<>();
    private volatile int tokenRequests;

    public ProductServiceStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String tokenUri() {
        return baseUrl() + "/token";
    }

    /** Next responses for requests whose path contains {@code fragment}, in order. */
    public void script(String fragment, Response... responses) {
        scripts.computeIfAbsent(fragment, k -> new ArrayDeque<>()).addAll(List.of(responses));
    }

    public List<Request> requestsFor(String fragment) {
        return requests.stream().filter(r -> r.path().contains(fragment)).toList();
    }

    public int tokenRequests() {
        return tokenRequests;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/token")) {
            tokenRequests++;
            boolean ok = body.contains("grant_type=client_credentials") && body.contains("client_secret=test-secret");
            respond(exchange, ok ? 200 : 401, ok ? "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}" : "{}");
            return;
        }
        requests.add(new Request(exchange.getRequestMethod(), path, Map.copyOf(exchange.getRequestHeaders()), body));
        Response response = scripts.entrySet().stream().filter(e -> path.contains(e.getKey()))
                .map(e -> e.getValue().pollFirst()).filter(java.util.Objects::nonNull).findFirst()
                .orElse(Response.of(200));
        if (!response.delay().isZero()) {
            try {
                Thread.sleep(response.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, response.status(), "{\"status\":" + response.status() + "}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // client gave up (timeout test)
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
