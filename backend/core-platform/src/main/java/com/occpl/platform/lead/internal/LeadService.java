package com.occpl.platform.lead.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.identity.api.CurrentUser;
import com.occpl.platform.lead.api.LeadStatus;
import com.occpl.platform.notification.api.Notifications;
import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.paging.Likes;
import com.occpl.platform.shared.paging.PageRequests;
import com.occpl.platform.shared.web.Etags;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lead registration and status tracking. Endpoint authorization is done by controllers. */
@Service
public class LeadService {

    private static final Map<String, String> SORTS = Map.of("companyName", "companyName", "contactName",
            "contactName", "status", "status", "createdAt", "createdAt");

    private final LeadRepository leads;
    private final AuditLogger audit;
    private final Notifications notifications;
    private final Clock clock;

    LeadService(LeadRepository leads, AuditLogger audit, Notifications notifications, Clock clock) {
        this.leads = leads;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    public record LeadView(UUID id, String companyName, String contactName, String email, String phone,
            String productInterest, String source, LeadStatus status, String notes, Instant createdAt,
            Instant updatedAt, long version) {
    }

    /** Omitted (null) fields stay unchanged on update; send an empty string to clear an optional field. */
    public record LeadData(String companyName, String contactName, String email, String phone,
            String productInterest, String source, String notes) {
    }

    @Transactional
    public LeadView register(LeadData data) {
        UUID actor = CurrentUser.require().userId();
        Lead lead = leads.save(new Lead(UuidV7.generate(clock), data.companyName().strip(),
                data.contactName().strip(), data.email().strip().toLowerCase(), blankToNull(data.phone()),
                blankToNull(data.productInterest()), blankToNull(data.source()), blankToNull(data.notes()), actor,
                clock.instant()));
        audit.record(AuditEvent.success("lead.registered", "lead", lead.id(), null,
                Map.of("companyName", lead.companyName(), "email", lead.email())));
        notifications.enqueue(LeadEmails.registered(lead));
        return view(lead);
    }

    @Transactional(readOnly = true)
    public Page<LeadView> search(LeadStatus status, String q, Integer page, Integer size, List<String> sort) {
        return leads.search(status, Likes.prefix(q),
                PageRequests.of(page, size, sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt"))).map(this::view);
    }

    @Transactional(readOnly = true)
    public LeadView get(UUID leadId) {
        return view(require(leadId));
    }

    @Transactional
    public LeadView update(UUID leadId, LeadData changes, String ifMatch) {
        Lead lead = require(leadId);
        Etags.check(ifMatch, lead.version());
        String companyName = changes.companyName() == null ? lead.companyName() : changes.companyName().strip();
        String contactName = changes.contactName() == null ? lead.contactName() : changes.contactName().strip();
        String email = changes.email() == null ? lead.email() : changes.email().strip().toLowerCase();
        String phone = changes.phone() == null ? lead.phone() : blankToNull(changes.phone());
        String interest = changes.productInterest() == null ? lead.productInterest()
                : blankToNull(changes.productInterest());
        String source = changes.source() == null ? lead.source() : blankToNull(changes.source());
        String notes = changes.notes() == null ? lead.notes() : blankToNull(changes.notes());
        Map<String, Object> diff = new LinkedHashMap<>();
        diffField(diff, "companyName", lead.companyName(), companyName);
        diffField(diff, "contactName", lead.contactName(), contactName);
        diffField(diff, "email", lead.email(), email);
        diffField(diff, "phone", lead.phone(), phone);
        diffField(diff, "productInterest", lead.productInterest(), interest);
        diffField(diff, "source", lead.source(), source);
        if (!Objects.equals(lead.notes(), notes)) {
            diff.put("notes", "changed");
        }
        if (!diff.isEmpty()) {
            lead.update(companyName, contactName, email, phone, interest, source, notes,
                    CurrentUser.require().userId(), clock.instant());
            leads.flush();
            audit.record(AuditEvent.success("lead.updated", "lead", leadId, null, diff));
        }
        return view(lead);
    }

    @Transactional
    public LeadView changeStatus(UUID leadId, LeadStatus target, String reason) {
        Lead lead = require(leadId);
        LeadStatus previous = lead.changeStatus(target, CurrentUser.require().userId(), clock.instant());
        leads.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", previous.name());
        details.put("to", target.name());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.record(AuditEvent.success("lead.status_changed", "lead", leadId, null, details));
        return view(lead);
    }

    private Lead require(UUID leadId) {
        return leads.findById(leadId).orElseThrow(() -> ApiException.notFound("Lead"));
    }

    private LeadView view(Lead l) {
        return new LeadView(l.id(), l.companyName(), l.contactName(), l.email(), l.phone(), l.productInterest(),
                l.source(), l.status(), l.notes(), l.createdAt(), l.updatedAt(), l.version());
    }

    private static void diffField(Map<String, Object> diff, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            diff.put(field, after == null ? "" : after);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
