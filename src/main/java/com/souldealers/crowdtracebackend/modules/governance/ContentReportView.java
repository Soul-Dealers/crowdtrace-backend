package com.souldealers.crowdtracebackend.modules.governance;

import java.time.LocalDateTime;

/** A report as moderators see it, open or resolved. */
public record ContentReportView(long id, long reporterId, ReportReason reason, String details, String status,
                                String resolution, Long moderatorId, String resolutionNotes,
                                LocalDateTime createdAt, LocalDateTime resolvedAt) {
}
