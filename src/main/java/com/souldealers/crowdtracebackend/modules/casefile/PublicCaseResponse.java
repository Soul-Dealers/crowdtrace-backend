package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Public case view; only public fields from PRD §5.1. */
public record PublicCaseResponse(
        Long id, String fullName, int age, Gender gender, LocalDate lastSeenDate, GhanaRegion region,
        String lastSeenLocation, String physicalDescription, String clothing, String circumstances,
        CaseStatus caseStatus, String closingStatement, String publicContactNumber,
        LocalDateTime approvedAt, LocalDateTime resolvedAt) {}
