package com.occpl.platform.audit.internal;

import com.occpl.platform.audit.api.AuditEvent;
import com.occpl.platform.audit.api.AuditLogger;
import com.occpl.platform.shared.id.UuidV7;
import com.occpl.platform.shared.security.Actor;
import com.occpl.platform.shared.security.ServicePrincipal;
import com.occpl.platform.shared.web.CorrelationId;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class JpaAuditLogger implements AuditLogger {

    private final AuditLogRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JpaAuditLogger(AuditLogRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        Optional<Actor> user = Actor.current();
        if (user.isPresent()) {
            save(event, "USER", user.get().userId(), null);
            return;
        }
        String client = ServicePrincipal.current().map(ServicePrincipal::clientId)
                .orElseThrow(() -> new IllegalStateException("No authenticated actor; use recordSystem"));
        save(event, "SERVICE", null, client);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSystem(AuditEvent event) {
        save(event, "SYSTEM", null, null);
    }

    private void save(AuditEvent event, String actorType, UUID actorUserId, String actorClientId) {
        String details = event.details().isEmpty() ? null : jsonMapper.writeValueAsString(event.details());
        repository.save(new AuditLogEntry(UuidV7.generate(clock), clock.instant(), actorType, actorUserId,
                actorClientId, event.companyId(), event.action(), event.targetType(), event.targetId(), event.outcome().name(),
                CorrelationId.current().orElse(null), details));
    }
}
