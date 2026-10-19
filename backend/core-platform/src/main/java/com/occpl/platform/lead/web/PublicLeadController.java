package com.occpl.platform.lead.web;

import com.occpl.platform.lead.internal.LeadService;
import com.occpl.platform.lead.internal.LeadService.LeadData;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import com.occpl.platform.shared.ratelimit.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated intake for the public website's contact form. Creates NEW leads only. */
@RestController
@RequestMapping("/api/v1/public/leads")
class PublicLeadController {

    /** Stricter than the general anonymous limit: a contact form is submitted rarely. */
    static final long PER_MINUTE_PER_IP = 5;

    private final LeadService leads;
    private final ObjectProvider<RateLimiter> limiter;

    PublicLeadController(LeadService leads, ObjectProvider<RateLimiter> limiter) {
        this.leads = leads;
        this.limiter = limiter;
    }

    @PostMapping
    @Operation(summary = "Submit an enquiry from the public website (no sign-in)",
            description = "Creates a NEW lead with source 'Website' and emails the sender a confirmation. "
                    + "Rate limited per client IP. Always answers 202 with no lead details. "
                    + "Leave the 'website' field empty: it is a bot trap and submissions that fill it are discarded.")
    ResponseEntity<Map<String, Boolean>> submit(@Valid @RequestBody LeadRequests.PublicLead request,
            HttpServletRequest http) {
        RateLimiter.Decision decision = limiter.stream().findFirst()
                .map(l -> l.tryConsume("rl:public-leads:ip:" + http.getRemoteAddr(), PER_MINUTE_PER_IP))
                .orElse(null);
        if (decision != null && !decision.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many submissions. Retry after " + decision.retryAfterSeconds() + " seconds.");
        }
        if (request.website() == null || request.website().isBlank()) {
            leads.registerPublic(new LeadData(request.companyName(), request.contactName(), request.email(),
                    request.phone(), request.productInterest(), null, request.message()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("received", true));
    }
}
