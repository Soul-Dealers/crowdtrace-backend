package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PasswordReset(
        @NotNull
        @NotBlank
        String email,

        @NotNull
        @NotBlank
        String password,

        @NotNull
        @NotBlank
        String confirmPassword,

        @NotNull
        @NotBlank
        String code
) {
}
