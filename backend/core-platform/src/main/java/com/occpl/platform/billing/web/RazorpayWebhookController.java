package com.occpl.platform.billing.web;

import com.occpl.platform.billing.internal.PaymentEvents;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.error.Problems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay webhook endpoint. No user authentication: the request is authenticated by its HMAC
 * signature over the raw body (verified before anything is parsed or stored).
 */
@RestController
@Tag(name = "Webhooks")
class RazorpayWebhookController {

    /** Razorpay events are a few kilobytes. */
    static final int MAX_BODY = 64 * 1024;

    private final PaymentEvents events;

    RazorpayWebhookController(PaymentEvents events) {
        this.events = events;
    }

    @PostMapping(value = "/api/v1/webhooks/payments/razorpay", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "Razorpay webhook (signed with the webhook secret)")
    ResponseEntity<Map<String, String>> receive(HttpServletRequest request,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "x-razorpay-event-id", required = false) String eventId) throws IOException {
        // Anonymous endpoint: read at most MAX_BODY bytes instead of whatever a caller sends.
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            throw ApiException.validation("The webhook body exceeds " + MAX_BODY + " bytes.");
        }
        PaymentEvents.Outcome outcome = events.receive(body, signature, eventId);
        return ResponseEntity.ok(Map.of("outcome", outcome.name()));
    }

    @ExceptionHandler(PaymentEvents.WebhookRejected.class)
    ResponseEntity<ProblemDetail> rejected(HttpServletRequest request) {
        ProblemDetail problem = Problems.of(ErrorCode.UNAUTHENTICATED, "Invalid webhook signature.",
                request.getRequestURI());
        return ResponseEntity.status(401).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
