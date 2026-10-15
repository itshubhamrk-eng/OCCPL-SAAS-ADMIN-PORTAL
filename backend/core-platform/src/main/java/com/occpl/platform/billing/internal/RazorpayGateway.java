package com.occpl.platform.billing.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay Invoices API (https://razorpay.com/docs/api/payments/invoices/): HTTP Basic auth with the
 * key id and secret; amounts in the smallest currency unit (paise). Invoices are created issued (not
 * draft), so Razorpay emails the payment link and returns {@code short_url}.
 */
@Component
class RazorpayGateway implements PaymentGateway {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_ERROR = 300;

    private final BillingProperties properties;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    RazorpayGateway(BillingProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public String name() {
        return "RAZORPAY";
    }

    @Override
    public GatewayInvoice createInvoice(InvoiceRequest request) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", request.customerName());
        customer.put("email", request.customerEmail());
        if (request.customerPhone() != null) {
            customer.put("contact", request.customerPhone());
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", request.lineItemName());
        item.put("description", request.lineItemDescription());
        item.put("amount", request.amountMinor());
        item.put("currency", request.currency());
        item.put("quantity", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "invoice");
        body.put("description", request.lineItemName());
        body.put("customer", customer);
        body.put("line_items", List.of(item));
        body.put("currency", request.currency());
        body.put("receipt", request.receipt());
        body.put("expire_by", request.expiresAt().getEpochSecond());
        body.put("email_notify", request.emailCustomer() ? 1 : 0);
        body.put("sms_notify", 0);
        body.put("partial_payment", false);
        body.put("notes", request.notes());
        return toInvoice(send(HttpRequest.newBuilder(uri("/v1/invoices")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))));
    }

    @Override
    public GatewayInvoice fetchInvoice(String gatewayInvoiceId) {
        return toInvoice(send(HttpRequest.newBuilder(uri("/v1/invoices/" + safeId(gatewayInvoiceId))).GET()));
    }

    @Override
    public GatewayInvoice cancelInvoice(String gatewayInvoiceId) {
        return toInvoice(send(HttpRequest.newBuilder(uri("/v1/invoices/" + safeId(gatewayInvoiceId) + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())));
    }

    private JsonNode send(HttpRequest.Builder request) {
        String credentials = properties.razorpayKeyId() + ":" + properties.razorpayKeySecret();
        request.timeout(TIMEOUT)
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .header("Accept", "application/json");
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return json.readTree(response.body());
            }
            throw new GatewayException("Razorpay returned HTTP " + status + ": " + errorDescription(response.body()),
                    status, status >= 500 || status == 429);
        } catch (HttpTimeoutException e) {
            throw new GatewayException("Razorpay did not answer within " + TIMEOUT.toSeconds() + " s.", null, true);
        } catch (IOException e) {
            throw new GatewayException("Razorpay unreachable: " + e.getClass().getSimpleName(), null, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Interrupted.", null, true);
        }
    }

    private String errorDescription(String body) {
        try {
            String description = json.readTree(body).path("error").path("description").asString("");
            if (!description.isBlank()) {
                return description.length() > MAX_ERROR ? description.substring(0, MAX_ERROR) : description;
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "(no description)";
    }

    private GatewayInvoice toInvoice(JsonNode node) {
        return new GatewayInvoice(node.path("id").asString(), node.path("invoice_number").asString(null),
                node.path("status").asString(""), node.path("short_url").asString(null),
                node.path("amount_paid").asLong(0), epoch(node.path("issued_at")), epoch(node.path("expire_by")),
                epoch(node.path("paid_at")));
    }

    private static Instant epoch(JsonNode node) {
        return node.isNumber() && node.asLong() > 0 ? Instant.ofEpochSecond(node.asLong()) : null;
    }

    private URI uri(String path) {
        return URI.create(properties.razorpayApiUrl().replaceAll("/+$", "") + path);
    }

    private static String safeId(String id) {
        if (!id.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Unexpected gateway invoice id");
        }
        return id;
    }
}
