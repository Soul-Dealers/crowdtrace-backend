package com.souldealers.crowdtracebackend.modules.identity;

import java.time.LocalDateTime;

/**
 * Applicant-facing verification projection. Reviewer identity and review notes are
 * deliberately absent because reviewers remain anonymous to applicants and notes are
 * reserved for internal review use.
 */
public record VerificationRequestResponse(
        Long id,
        VerificationType verificationType,
        String evidenceReference,
        VerificationStatus status,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt) {
}
