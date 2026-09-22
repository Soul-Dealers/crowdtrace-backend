package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpDto(
        @NotBlank
        @Pattern(regexp = "[0-9]{6}")
        String code,
        @NotBlank
        @Email
        String email
) {
}
