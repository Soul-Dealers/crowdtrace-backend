package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GrantVerificationRequest(
        @NotBlank @Email String email,
        @NotNull VerificationType verificationType,
        @NotBlank @Size(max = 1024) String evidenceReference,
        @Size(max = 2000) String reviewNotes) {
}
