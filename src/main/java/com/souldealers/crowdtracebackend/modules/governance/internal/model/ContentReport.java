package com.souldealers.crowdtracebackend.modules.governance.internal.model;

import com.souldealers.crowdtracebackend.modules.governance.ReportReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * A user's report on a comment. Inserted open; the V17 trigger allows only one change, OPEN to RESOLVED, which
 * ContentReportRepository applies with a bulk update. No setters.
 */
@Entity
@Getter
@Table(name = "content_reports")
public class ContentReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "comment_id", nullable = false, updatable = false)
    private long commentId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32, updatable = false)
    private ReportReason reason;

    @Column(name = "details", length = 1000, updatable = false)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32, updatable = false)
    private ReportStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 32, updatable = false)
    private ReportResolution resolution;

    @Column(name = "moderator_id", updatable = false)
    private Long moderatorId;

    @Column(name = "resolution_notes", length = 2000, updatable = false)
    private String resolutionNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at", updatable = false)
    private LocalDateTime resolvedAt;

    protected ContentReport() { }

    public static ContentReport open(long commentId, long reporterId, ReportReason reason, String details,
                                     LocalDateTime createdAt) {
        var report = new ContentReport();
        report.commentId = commentId;
        report.reporterId = reporterId;
        report.reason = reason;
        report.details = details;
        report.status = ReportStatus.OPEN;
        report.createdAt = createdAt;
        return report;
    }
}
