package com.souldealers.crowdtracebackend.shared.audit.internal;

import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditMetadata;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecorder;
import com.souldealers.crowdtracebackend.shared.audit.AuditRecordingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
class JpaAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaAuditRecorder.class);
    private static final Pattern CORRELATION_ID = Pattern.compile(CORRELATION_ID_PATTERN);

    private final AuditEventRepository repository;
    private final Clock clock;

    JpaAuditRecorder(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long record(AuditActor actor, AuditAction action, long targetId, AuditMetadata metadata) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AuditRecordingException("Audit recording requires an active transaction");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new AuditRecordingException("Audit recording is not allowed in a read-only transaction");
        }
        if (actor == null || action == null || metadata == null || targetId <= 0) {
            throw new AuditRecordingException("Audit event fields are invalid");
        }
        if (metadata.action() != action) {
            throw new AuditRecordingException("Audit metadata does not match the requested action");
        }
        try {
            AuditObligations.trackTransaction();
            AuditMetadataSerializer.validateSize(metadata.values());
            String correlationId = correlationId();
            AuditEvent event = AuditEvent.create(actor, action, targetId, metadata.values(),
                    correlationId, LocalDateTime.now(clock));
            AuditEvent saved = repository.saveAndFlush(event);
            long eventId = saved.id();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("audit_recorded eventId={} action={} targetType={} targetId={} actorId={}",
                            eventId, action.code(), action.targetType().code(), targetId, actor.id());
                }
            });
            AuditObligations.discharge(action);
            return eventId;
        } catch (AuditRecordingException exception) {
            throw exception;
        } catch (DataAccessException | jakarta.persistence.PersistenceException exception) {
            throw new AuditRecordingException("Audit event could not be recorded", exception);
        } catch (IllegalArgumentException exception) {
            throw new AuditRecordingException("Audit event could not be recorded", exception);
        }
    }

    private String correlationId() {
        String value = org.slf4j.MDC.get("correlationId");
        return value != null && CORRELATION_ID.matcher(value).matches() ? value : null;
    }
}
