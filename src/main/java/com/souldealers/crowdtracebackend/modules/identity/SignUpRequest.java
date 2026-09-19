package com.souldealers.crowdtracebackend.modules.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.hibernate.validator.constraints.Length;

@Builder
public record SignUpRequest (
        @NotBlank
        @Email
        String email,

        @NotBlank
        @NotNull
        @Length(min = 8, message = "Password must be at least 8 characters long")
        String password,

        @NotBlank
        @NotNull
        String displayName

        ) {
}
