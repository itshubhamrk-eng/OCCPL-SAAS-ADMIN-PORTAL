package com.occpl.platform.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for the Razorpay Invoices API following its documented contract (create, fetch, cancel;
 * Basic auth; amounts in paise). Failures can be scripted; invoice states can be changed by tests.
 */
public final class RazorpayStub implements AutoCloseable {

    public record Request(String method, String path, String authorization, JsonNode body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final AtomicInteger counter = new AtomicInteger();
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> states = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> created = new ConcurrentHashMap<>();
    private final Deque<int[]> failures = new ArrayDeque<>();

    public RazorpayStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** The next create call fails with this HTTP status. */
    public synchronized void failNextCreate(int status) {
        failures.add(new int[] {status});
    }

    public void setState(String invoiceId, String state) {
        states.put(invoiceId, state);
    }

    public List<Request> creates() {
        return requests.stream().filter(r -> r.method().equals("POST") && r.path().equals("/v1/invoices")).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode body = raw.isBlank() ? null : JSON.readTree(raw);
        requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"), body));
        if (path.equals("/v1/invoices") && exchange.getRequestMethod().equals("POST")) {
            int[] failure;
            synchronized (this) {
                failure = failures.poll();
            }
            if (failure != null) {
                respond(exchange, failure[0], "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The customer email is invalid\"}}");
                return;
            }
            String id = "inv_TEST" + counter.incrementAndGet();
            created.put(id, body);
            states.put(id, "issued");
            respond(exchange, 200, invoice(id));
            return;
        }
        String[] parts = path.split("/");
        if (parts.length >= 4 && parts[2].equals("invoices") && states.containsKey(parts[3])) {
            String id = parts[3];
            if (parts.length == 5 && parts[4].equals("cancel")) {
                if (states.get(id).equals("paid")) {
                    respond(exchange, 400, "{\"error\":{\"description\":\"Invoice is paid\"}}");
                    return;
                }
                states.put(id, "cancelled");
            }
            respond(exchange, 200, invoice(id));
            return;
        }
        respond(exchange, 404, "{\"error\":{\"description\":\"The id provided does not exist\"}}");
    }

    private String invoice(String id) {
        JsonNode request = created.get(id);
        long amount = request.path("line_items").get(0).path("amount").asLong();
        String state = states.get(id);
        long now = Instant.now().getEpochSecond();
        Map<String, Object> invoice = new java.util.LinkedHashMap<>();
        invoice.put("id", id);
        invoice.put("entity", "invoice");
        invoice.put("invoice_number", "OCC/" + id.substring(4));
        invoice.put("status", state);
        invoice.put("short_url", "https://rzp.io/i/" + id);
        invoice.put("amount", amount);
        invoice.put("amount_paid", state.equals("paid") ? amount : 0);
        invoice.put("receipt", request.path("receipt").asString());
        invoice.put("issued_at", now);
        invoice.put("expire_by", request.path("expire_by").asLong());
        invoice.put("paid_at", state.equals("paid") ? now : 0);
        return JSON.writeValueAsString(invoice);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
