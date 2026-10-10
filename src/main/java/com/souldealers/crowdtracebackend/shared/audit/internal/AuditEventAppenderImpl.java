package com.souldealers.crowdtracebackend.shared.audit.internal;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/** Uses persist exclusively; merge would create an update path for immutable rows. */
@Repository
class AuditEventAppenderImpl implements AuditEventAppender {

    private final EntityManager entityManager;

    AuditEventAppenderImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public AuditEvent saveAndFlush(AuditEvent event) {
        if (event.id() != null) {
            throw new IllegalArgumentException("Cannot append an audit event that already has an id");
        }
        entityManager.persist(event);
        entityManager.flush();
        return event;
    }
}
