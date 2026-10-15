package com.occpl.platform.notification.internal;

import com.occpl.platform.notification.api.EmailMessage;
import com.occpl.platform.notification.api.Notifications;
import com.occpl.platform.shared.id.UuidV7;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService implements Notifications {

    private final NotificationRepository repository;
    private final Clock clock;

    NotificationService(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void enqueue(EmailMessage message) {
        repository.save(new Notification(UuidV7.generate(clock), message.companyId(), message.template(),
                message.to(), message.subject(), message.textBody(), message.htmlBody(), clock.instant()));
    }
}
