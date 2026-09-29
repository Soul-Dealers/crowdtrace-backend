package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.Size;

public record VerificationDecisionRequest(
        @Size(max = 2000) String reviewNotes) {
}
