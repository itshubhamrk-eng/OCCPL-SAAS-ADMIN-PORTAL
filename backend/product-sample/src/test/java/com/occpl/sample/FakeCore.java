package com.occpl.sample;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Stands in for Core's token endpoint and service API; answers are set per path prefix. */
final class FakeCore implements AutoCloseable {

    record Call(String method, String path, String query, String authorization, String body) {
    }

    record Answer(int status, String body) {
    }

    private final HttpServer server;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Function<Call, Answer>> answers = new ConcurrentHashMap<>();
    volatile int tokenRequests;

    FakeCore() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void answer(String pathPrefix, Function<Call, Answer> answer) {
        answers.put(pathPrefix, answer);
    }

    List<Call> calls(String pathPrefix) {
        return calls.stream().filter(c -> c.path().startsWith(pathPrefix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Answer answer;
        if (path.equals("/token")) {
            tokenRequests++;
            answer = body.contains("client_id=product-sample-service") && body.contains("client_secret=sample-secret")
                    ? new Answer(200, "{\"access_token\":\"sample-service-token\",\"expires_in\":300}")
                    : new Answer(401, "{}");
        } else {
            Call call = new Call(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body);
            calls.add(call);
            answer = answers.entrySet().stream().filter(e -> path.startsWith(e.getKey())).findFirst()
                    .map(e -> e.getValue().apply(call)).orElse(new Answer(404, "{}"));
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
