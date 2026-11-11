package com.souldealers.crowdtracebackend.shared.audit.internal;

import com.souldealers.crowdtracebackend.shared.audit.AuditActor;
import com.souldealers.crowdtracebackend.shared.audit.AuditAction;
import com.souldealers.crowdtracebackend.shared.audit.AuditTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

/** Internal, immutable representation of one persisted audit event. */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_role", nullable = false, length = 32, updatable = false)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 64, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, length = 32, updatable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private long targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    protected AuditEvent() { }

    static AuditEvent create(AuditActor actor, AuditAction action, long targetId,
            Map<String, Object> metadata, String correlationId, LocalDateTime occurredAt) {
        var event = new AuditEvent();
        event.actorId = actor.id();
        event.actorRole = actor.role().name();
        event.action = action.code();
        event.targetType = action.targetType().code();
        event.targetId = targetId;
        event.metadata = Map.copyOf(metadata);
        event.correlationId = correlationId;
        event.occurredAt = occurredAt;
        return event;
    }

    public Long id() { return id; }
    public Long actorId() { return actorId; }
    public String actorRole() { return actorRole; }
    public String action() { return action; }
    public String targetType() { return targetType; }
    public long targetId() { return targetId; }
    public Map<String, Object> metadata() { return metadata == null ? null : Collections.unmodifiableMap(metadata); }
    public String correlationId() { return correlationId; }
    public LocalDateTime occurredAt() { return occurredAt; }
}
