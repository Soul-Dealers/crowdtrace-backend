package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.Length;

public record PasswordReset(
        @NotBlank
        @Email
        String email,

        @NotBlank
        @Length(min = 8, message = "Password must be at least 8 characters long")
        String password,

        @NotBlank
        @Length(min = 8, message = "Password must be at least 8 characters long")
        String confirmPassword,

        @NotBlank
        @Pattern(regexp = "[0-9]{6}")
        String code
) {
}
