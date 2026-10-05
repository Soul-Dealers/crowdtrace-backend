package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Reporter-owned detail view, including their sensitive input; visibility follows PRD §5.1. */
public record ReporterCaseResponse(
        Long id, String fullName, int age, Gender gender, LocalDate lastSeenDate, GhanaRegion region,
        String lastSeenLocation, String physicalDescription, String clothing, String circumstances,
        String publicContactNumber, ReviewStatus reviewStatus, CaseStatus caseStatus,
        String closingStatement, LocalDateTime submittedAt, LocalDateTime approvedAt,
        SensitiveDetailsResponse sensitiveDetails) {}
