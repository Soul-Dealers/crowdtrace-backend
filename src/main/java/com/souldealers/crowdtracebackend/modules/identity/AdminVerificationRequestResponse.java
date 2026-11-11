package com.souldealers.crowdtracebackend.modules.identity;

import java.time.LocalDateTime;

public record AdminVerificationRequestResponse(
        Long id,
        Long userId,
        String displayName,
        VerificationType verificationType,
        String evidenceReference,
        VerificationStatus status,
        String reviewNotes,
        LocalDateTime createdAt,
        LocalDateTime reviewedAt,
        LocalDateTime revokedAt,
        String revocationNotes) {
}
