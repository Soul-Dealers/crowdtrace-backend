package com.souldealers.crowdtracebackend.modules.notification.internal.model;

import com.souldealers.crowdtracebackend.modules.notification.NotificationType;
import com.souldealers.crowdtracebackend.modules.notification.NotifiedCaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/** Read-only view; rows are written by InAppNotificationRepository's idempotent insert and mark-read update. */
@Entity
@Getter
@Table(name = "notifications")
public class InAppNotification {
    @Id
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private long recipientId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private long caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "comment_id", updatable = false)
    private Long commentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "case_status", updatable = false)
    private NotifiedCaseStatus caseStatus;

    @Column(name = "read_at", updatable = false)
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected InAppNotification() { }
}
