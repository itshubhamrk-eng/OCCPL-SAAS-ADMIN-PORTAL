package com.occpl.platform.billing.web;

import com.occpl.platform.billing.internal.PaymentEvents;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.error.Problems;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay webhook endpoint. No user authentication: the request is authenticated by its HMAC
 * signature over the raw body (verified before anything is parsed or stored).
 */
@RestController
@Tag(name = "Webhooks")
class RazorpayWebhookController {

    private final PaymentEvents events;

    RazorpayWebhookController(PaymentEvents events) {
        this.events = events;
    }

    @PostMapping(value = "/api/v1/webhooks/payments/razorpay", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "Razorpay webhook (signed with the webhook secret)")
    ResponseEntity<Map<String, String>> receive(@RequestBody byte[] body,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "x-razorpay-event-id", required = false) String eventId) {
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
