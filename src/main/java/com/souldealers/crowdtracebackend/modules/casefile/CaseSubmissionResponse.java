package com.souldealers.crowdtracebackend.modules.casefile;

import java.time.LocalDateTime;

public record CaseSubmissionResponse(Long caseId, ReviewStatus reviewStatus, LocalDateTime submittedAt) {
}
