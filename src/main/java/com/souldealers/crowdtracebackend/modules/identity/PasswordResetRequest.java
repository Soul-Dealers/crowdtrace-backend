package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
        @NotBlank
        @Email
        String email
) {
}
