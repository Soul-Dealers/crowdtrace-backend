package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Admin detail view, including identity, flags, and private inputs from PRD §5.1. */
public record AdminCaseResponse(
        Long id, Long reporterId, String reporterAccountEmail,
        String fullName, int age, Gender gender, LocalDate lastSeenDate, GhanaRegion region,
        String lastSeenLocation, String physicalDescription, String clothing, String circumstances,
        String publicContactNumber, ReviewStatus reviewStatus, CaseStatus caseStatus,
        String closingStatement, boolean priorityMinor, boolean duplicateFlag,
        LocalDateTime submittedAt, LocalDateTime approvedAt, LocalDateTime resolvedAt, LocalDateTime closedAt,
        SensitiveDetailsResponse sensitiveDetails, List<CaseFileMetadataResponse> files,
        List<CaseConsentResponse> consents) {}
