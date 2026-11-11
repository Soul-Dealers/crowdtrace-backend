package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PublicCaseSummaryResponse(Long id, String fullName, int age, Gender gender, GhanaRegion region,
        LocalDate lastSeenDate, CaseStatus caseStatus, CasePresentation presentation,
        LocalDateTime approvedAt, LocalDateTime resolvedAt, Long thumbnailPhotoId) {}
