package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;

public record CaseLifecycleResponse(Long caseId, long caseVersion, ReviewStatus reviewStatus, CaseStatus caseStatus,
                                    String closingStatement, LocalDateTime resolvedAt, LocalDateTime closedAt,
                                    LocalDateTime occurredAt) { }
