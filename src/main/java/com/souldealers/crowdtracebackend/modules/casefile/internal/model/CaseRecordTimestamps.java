package com.souldealers.crowdtracebackend.modules.casefile.internal.model;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Stamps case times from the injected UTC clock; Hibernate builds it through Spring's bean container.
 * JPA slice tests have no clock bean, so it falls back to the system UTC clock.
 */
public class CaseRecordTimestamps {
    private final Clock clock;

    public CaseRecordTimestamps(ObjectProvider<Clock> clock) {
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @PrePersist
    void onCreate(CaseRecord record) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (record.getSubmittedAt() == null) record.setSubmittedAt(now);
        if (record.getCreatedAt() == null) record.setCreatedAt(now);
        record.setUpdatedAt(now);
    }

    @PreUpdate
    void onUpdate(CaseRecord record) {
        record.setUpdatedAt(LocalDateTime.now(clock));
    }
}
