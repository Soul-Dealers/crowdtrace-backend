package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitVerificationRequest(
        @NotNull VerificationType verificationType,
        @NotBlank @Size(max = 1024) String evidenceReference) {
}
