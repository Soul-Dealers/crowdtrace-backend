package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;

/** Reporter-owned list summary; omits sensitive input and PRD §5.1 admin-only flags. */
public record ReporterCaseSummaryResponse(Long id, String fullName, ReviewStatus reviewStatus,
        CaseStatus caseStatus, LocalDateTime submittedAt) {}
